(ns ov2.core
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as spectest]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            )
  (:import (org.tomitribe.auth.signatures Signer))
  (:import (org.tomitribe.auth.signatures Signature))
  (:import (org.tomitribe.auth.signatures PEM))
  (:import (javax.crypto Mac))
  (:import (java.security Key))
  (:import (java.security PrivateKey))
  (:import (java.text.SimpleDateFormat))
  (:import (java.security.MessageDigest))
  (:import (java.io RandomAccessFile))
  (:import (java.nio.channels.FileChannel$MapMode))
  (:import (java.nio ByteBuffer))
  (:import (java.util Base64))
  (:import (java.time LocalDateTime))
  (:import (java.time.format DateTimeFormatter))
  (:gen-class))

(def ^:private date-format (doto (java.text.SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss zzz")
                             (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))))

;; https://stackoverflow.com/questions/38283891/how-to-wrap-a-string-in-an-input-stream
(defn- string->stream
  ([s] (string->stream s "UTF-8"))
  ([s encoding]
   (-> s
       (.getBytes encoding)
       (java.io.ByteArrayInputStream.))))

(defn- host [url] (-> (java.net.URI. url) (.getHost)))
(defn- path [url]
  (let [uri (java.net.URI. url)
        path (.getRawPath uri)
        query (.getRawQuery uri)]
    (str path (when (and query (not (.isEmpty query))) \?) query)
    ))
;(host "https://iaas.region.oraclecloud.com/20160918/instances?availabilityDomain=123:456")
;(path "https://iaas.region.oraclecloud.com/20160918/instances?availabilityDomain=123:456")
;(path "https://iaas.region.oraclecloud.com/20160918/instances")

(defn- key-id [tenant-id user-id fingerprint]
  (str tenant-id \/ user-id \/ fingerprint))

(defn- publish-info [tenant-id user-id fingerprint private-key]
  {:key-id (key-id tenant-id user-id fingerprint)
   :tenant-id tenant-id
   :user-id user-id
   :fingerprint fingerprint
   :private-key private-key
   })

;; https://howtodoinjava.com/java7/nio/3-ways-to-read-files-using-java-nio/
(defn- body-sha256 [file-path]
  ;; slurp the content into memory, this works
  #_(let [digest (java.security.MessageDigest/getInstance "SHA-256")
        file (RandomAccessFile. file-path "r")
        channel (.getChannel file)
        buffer (.map channel (java.nio.channels.FileChannel$MapMode/READ_ONLY) 0 (.length file))
        ]
    (->> (slurp file-path)
         (.getBytes)
         (.digest digest)
         (.encodeToString (Base64/getEncoder))
         println))
  ;; trying with a ByteBuffer of the size of the file
  #_(let [digest (java.security.MessageDigest/getInstance "SHA-256")
        file (RandomAccessFile. file-path "r")
        channel (.getChannel file)
        b (ByteBuffer/allocate (.length file))]
    (.read channel b)
    (.flip b)
    (.update digest b)
    (.close file)
    (.close channel)
    (println (.encodeToString (Base64/getEncoder) (.digest digest))))
  ;; trying with a segment of 10 bytes
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        file (RandomAccessFile. file-path "r")
        channel (.getChannel file)
        ;; buffer size of 10MB
        b (ByteBuffer/allocate (* 1024 1024 10))]
    (while (> (.read channel b) 0)
      ;;(println "position" (.position b))
      (.flip b)
      (.update digest b)
      (.clear b))
    (.close file)
    (.close channel)
    (.encodeToString (Base64/getEncoder) (.digest digest))))
;;(body-sha256 "/home/thach/Downloads/ov2/oci_api_key.pem")
;;(body-sha256 "/home/thach/Downloads/ov2/parameters.json")

(defn- content-sha256 [content]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
      (->> (.getBytes content)
           (.digest digest)
           (.encodeToString (Base64/getEncoder)))))
;;(content-sha256 "abc")

(defn- ora-get [url publish]
  "HTTP GET to Oracle API requires signature of the headers: (request-target) host date"
  (let [key (key-id (:tenant-id publish) (:user-id publish) (:fingerprint publish))
        signature (Signature. key "rsa-sha256" nil ["(request-target)" "date" "host"])
        private (PEM/readPrivateKey (string->stream (:private-key publish)))
        signer (Signer. private signature)
        date (.format date-format (java.util.Date.))
        headers {"date" date
                 "host" (host url)
                 }
        authorization (-> (.sign signer "get" (path url) headers)
                          (.toString))
        ]
    (-> (client/get url {:debug false :headers (assoc headers "Authorization" authorization)})
        :body)))

(defn- ora-put [url publish body-file]
  (let [key (key-id (:tenant-id publish) (:user-id publish) (:fingerprint publish))
        signature (Signature. key "rsa-sha256" nil ["(request-target)" "date" "host" "Content-Length" "content-type" "x-content-sha256"])
        private (PEM/readPrivateKey (string->stream (:private-key publish)))
        signer (Signer. private signature)
        date (.format date-format (java.util.Date.))
        file (clojure.java.io/file body-file)
        headers {"date" date
                 "host" (host url)
                 "Content-Length" (str (.length file))
                 "content-type" "application/json"
                 "x-content-sha256" (time (body-sha256 body-file))
                 }
        authorization (-> (.sign signer "put" (path url) headers)
                          (.toString))
        put-headers (dissoc (assoc headers "Authorization" authorization) "Content-Length")
        ]
    (-> (client/put url {:debug false :headers put-headers :body (clojure.java.io/input-stream body-file) :length (.length file)})
        :body)))

(defn- ora-post [url publish body-content]
  (let [key (key-id (:tenant-id publish) (:user-id publish) (:fingerprint publish))
        length (count (.getBytes body-content))
        signature (Signature. key "rsa-sha256" nil ["(request-target)" "date" "host" "Content-Length" "content-type" "x-content-sha256"])
        private (PEM/readPrivateKey (string->stream (:private-key publish)))
        signer (Signer. private signature)
        date (.format date-format (java.util.Date.))
        headers {"date" date
                 "host" (host url)
                 "Content-Length" (str length)
                 "content-type" "application/json"
                 "x-content-sha256" (content-sha256 body-content)
                 }
        authorization (-> (.sign signer "post" (path url) headers)
                          (.toString))
        http-headers (dissoc (assoc headers "Authorization" authorization) "Content-Length")
        ]
    (println (str "POST BODY:::" body-content))
    (-> (client/post url {:debug false :headers http-headers :body body-content :length length})
        :body)))

(defn -main [tenant-id user-id fingerprint private-key-file home-region compartment-id region bucket-name image-name image-path]
  (println "tenant" tenant-id)
  (println "user" user-id)
  (println "fingerprint" fingerprint)
  (println "private" private-key-file)
  (println "home" home-region)
  (println "compartment" compartment-id)
  (println "region" region)
  (println "bucket" bucket-name)
  (println "image-name" image-name)
  (println "image-path" image-path)
  (let [p-info (publish-info tenant-id user-id fingerprint (slurp private-key-file))
        url-namespace (str "https://objectstorage." home-region ".oraclecloud.com/n/")
        url-tenant (str "https://identity." home-region ".oraclecloud.com/20160918/tenancies/" tenant-id)
        tenant-info (cheshire/parse-string (ora-get url-tenant p-info) true)
        ora-namespace (cheshire/parse-string (ora-get url-namespace p-info))
        url-regions (str "https://identity." home-region ".oraclecloud.com/20160918/regions/")
        url-bucket (str "https://objectstorage." region ".oraclecloud.com/n/" ora-namespace "/b/" bucket-name)
        url-post-bucket (str "https://objectstorage." region ".oraclecloud.com/n/" ora-namespace "/b/")
        ;;compartment-id (:compartmentId tenant-info)
        url-compartments (str "https://identity." home-region ".oraclecloud.com/20160918/compartments/?compartmentId=" compartment-id)
        url-images (str "https://iaas." region ".oraclecloud.com/20160918/images?compartmentId=" compartment-id)
        url-put-object (str "https://objectstorage." region ".oraclecloud.com/n/" ora-namespace "/b/" bucket-name "/o/" image-name)
        url-post-image (str "https://iaas." region ".oraclecloud.com/20160918/images")
        post-image-body {:compartmentId compartment-id
                         ;; launchMode is very important to make image bootable, it defaults to NATIVE which doesn't work correctly
                         :launchMode "EMULATED"
                         :displayName image-name
                         :imageSourceDetails {:sourceUri url-put-object
                                              :sourceType "objectStorageUri"}}
        ]
    #_((println (ora-get url-regions p-info))
       (println ora-namespace)
       (println tenant-info)
       (println (ora-get url-compartments p-info))
       (println (take 5 (cheshire/parse-string (ora-get url-images p-info)))))
    (println "Checking if bucket exists...")
    (try
      (ora-get url-bucket p-info)
      (catch Exception e
        (println "Cannot retrieve bucket (assumed non-existant), creating new bucket...")
        (let [post-bucket-body {:compartmentId compartment-id
                                :namespace ora-namespace
                                :name bucket-name}]
          (ora-post url-post-bucket p-info (cheshire/generate-string post-bucket-body)))))
    (println "Uploading image to object storage...")
    (time (ora-put url-put-object p-info image-path))
    (println "Creating image from object storage...")
    ;;(println post-image-body)
    ;;(println (cheshire/generate-string post-image-body))
    ;;(spit "/tmp/ora-spit.json" (cheshire/generate-string post-image-body))
    (time (ora-post url-post-image p-info (cheshire/generate-string post-image-body)))
    ))

#_(-main "ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq"
       "ocid1.user.oc1..aaaaaaaaxfimk2hemd2k5flcziablsdfbecayjdzqphuzggprgkfzlvyst2a"
       "db:ea:6a:28:91:ed:39:3a:11:fc:4f:26:f3:c3:05:83"
       "/home/thach/Downloads/ov2/oci_api_key.pem"
       "eu-frankfurt-1"
       "ocid1.compartment.oc1..aaaaaaaa4vt34sarwn3j4czxypud3oulshkzr7gmqqbga4xpzp7wkjruqgiq"
       "eu-frankfurt-1" 
       "thach-bucket"
       "thach-vanilla-tar-gz"
       "/home/thach/Downloads/ov2/vanilla.tar.gz"
       )

;;"ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq" "ocid1.user.oc1..aaaaaaaaxfimk2hemd2k5flcziablsdfbecayjdzqphuzggprgkfzlvyst2a" "db:ea:6a:28:91:ed:39:3a:11:fc:4f:26:f3:c3:05:83" "/tmp/workDir/oci_api_key.pem" "eu-frankfurt-1" "ocid1.compartment.oc1..aaaaaaaa4vt34sarwn3j4czxypud3oulshkzr7gmqqbga4xpzp7wkjruqgiq" "eu-frankfurt-1" "thach-bucket" "thach-clojure-qcow2" "/tmp/workDir/thach1.vmdk"
