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

;; not working, should find a way to work with stream instead of byte array
(defn- body-sha256 [input-stream]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
       ]
    (.digest digest input-stream)
    (println (.digest digest))
  ))
;(body-sha256 (.getBytes (slurp "/home/thach/Downloads/ov2/oci_api_key.pem")))


(defn- ora-get [url publish]
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

(defn -main [tenant-id user-id fingerprint private-key-file home-region]
  (let [p-info (publish-info tenant-id user-id fingerprint (slurp private-key-file))
        url-namespace (str "https://objectstorage." home-region ".oraclecloud.com/n/")
        url-tenant (str "https://identity." home-region ".oraclecloud.com/20160918/tenancies/" tenant-id)
        tenant-info (cheshire/parse-string (ora-get url-tenant p-info) true)
        ora-namespace (ora-get url-namespace p-info)
        url-regions (str "https://identity." home-region ".oraclecloud.com/20160918/regions/")
        compartment-id (:compartmentId tenant-info)
        url-compartments (str "https://identity." home-region ".oraclecloud.com/20160918/compartments/?compartmentId=" compartment-id)
        url-images (str "https://iaas." home-region ".oraclecloud.com/20160918/images?compartmentId=" compartment-id)
        ]
    (println (ora-get url-regions p-info))
    (println ora-namespace)
    (println tenant-info)
    (println (ora-get url-compartments p-info))
    (println (take 5 (cheshire/parse-string (ora-get url-images p-info))))
    ))

#_(-main "ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq"
       "ocid1.user.oc1..aaaaaaaaxfimk2hemd2k5flcziablsdfbecayjdzqphuzggprgkfzlvyst2a"
       "db:ea:6a:28:91:ed:39:3a:11:fc:4f:26:f3:c3:05:83"
       "/home/thach/Downloads/ov2/oci_api_key.pem"
       "eu-frankfurt-1")
