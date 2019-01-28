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

  (:gen-class))

(def ^:private private-key (slurp "/home/thach/Downloads/ov2/oci_api_key.pem"))

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

(defn ora-get [url key-id date]
  (let [signature (Signature. key-id "rsa-sha256" nil ["date" "(request-target)" "host"])
        private (PEM/readPrivateKey (string->stream private-key))
        signer (Signer. private signature)
        headers {"date" date
                 "host" (host url)
                 }
        ]
    (-> (.sign signer "get" (path url) headers)
        (.toString)
        (println))
    ))

(defn -main []
  (let [key-id "ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq/ocid1.user.oc1..aaaaaaaaxfimk2hemd2k5flcziablsdfbecayjdzqphuzggprgkfzlvyst2a/db:ea:6a:28:91:ed:39:3a:11:fc:4f:26:f3:c3:05:83"
         url "https://iaas.eu-frankfurt-1.oraclecloud.com/20160918/instances?availabilityDomain=gDFj%3AEU-FRANKFURT-1-AD-1&compartmentId=ocid1.tenancy.oc1..aaaaaaaaxrooypj3r3gmztrxvplqzq3gwdkfsblpn465m5d7d4pxrp5rzigq"
         date "Fri, 25 Jan 2019 15:52:27 GMT"
        ]
    (ora-get url key-id date)))
