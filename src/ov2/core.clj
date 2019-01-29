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

  (:gen-class))

(def ^:private private-key (slurp "/home/thach/Downloads/ov2/oci_api_key.pem"))

#_(def ^:private date-format (-> (java.text.SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss zzz" java.util.Locale/US)
                               (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))))
(def ^:private date-format (doto (java.text.SimpleDateFormat. "EEE, dd MMM yyyy HH:mm:ss zzz")
                             (.setTimeZone (java.util.TimeZone/getTimeZone "GMT"))
                             ))

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

(defn ora-get [url tenant-id user-id fingerprint compartement-id namespace]
  (let [key (key-id tenant-id user-id fingerprint)
        signature (Signature. key "rsa-sha256" nil ["date" "(request-target)" "host"])
        private (PEM/readPrivateKey (string->stream private-key))
        signer (Signer. private signature)
        date (.format date-format (java.util.Date.))
        headers {"date" date
                 "host" (host url)
                 }
        authorization (-> (.sign signer "get" (path url) headers)
                          (.toString))
        ]
    (println authorization)
    (-> (client/get url {:debug true :headers (assoc headers "Authorization" authorization)})
        :body)))

(defn -main []
  (let [tenant-id "ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq"
        user-id "ocid1.user.oc1..aaaaaaaaxfimk2hemd2k5flcziablsdfbecayjdzqphuzggprgkfzlvyst2a"
        fingerprint "db:ea:6a:28:91:ed:39:3a:11:fc:4f:26:f3:c3:05:83"
        compartment-id "ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq"
        namespace "oraclecloudnew"
        ]
    (ora-get url tenant-id user-id fingerprint compartement-id namespace)))
