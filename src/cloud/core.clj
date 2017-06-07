(ns cloud.core
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as spectest]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            ))

(spec/def ::cloud-endpoint (comp not nil? (partial re-find #"^https://([a-z0-9-]+\.)+oraclecloud.com/$")))
(spec/def ::domain (comp not nil? (partial re-find #"^[a-zA-Z0-9]+$")))
(spec/def ::authenticate-args
  (spec/cat :endpoint ::cloud-endpoint
            :domain ::domain
            :user string?
            :password string?))
(spec/check-asserts true)

(def compute-endpoint "https://compute.gbcom-south-1.oraclecloud.com/")
(def t-domain "a491487")
(def t-user "oraclecloud@usharesoft.com")
(def t-password "!USSOraUForge01")

;; ==================== compute ==================
(def compute-content-type-json "application/oracle-compute-v3+json")
(def compute-content-type-directory "application/oracle-compute-v3+directory+json")
(defn compute-authenticate [endpoint domain user password]
  "returns a clj-http cookie-store to be used in later request; or ::bad-credentials / ::invalid-endpoint"
  (spec/assert ::cloud-endpoint endpoint)
  (let [cs (clj-http.cookies/cookie-store)]
    (client/post (str endpoint "/authenticate/")
                 {:content-type "application/oracle-compute-v3+json"
                  :cookie-store cs
                  :body (cheshire/generate-string {:user (str "/Compute-" domain "/" user) :password password})})
    cs))
(spec/fdef compute-authenticate
           :args ::authenticate-args
           :ret #(instance? org.apache.http.impl.client.BasicCookieStore %))
(comment (compute-authenticate compute-endpoint "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01"))

(defn compute-image-list [endpoint cookie-store]
  (let [user-instances "instance/Compute-a491487/oraclecloud@usharesoft.com/"
        imagelist "imagelist/Compute-a491487/oraclecloud@usharesoft.com/thach-apo/entry/1"
        machineimage "machineimage/Compute-a491487/oraclecloud@usharesoft.com/thach-apo"]
  (client/get (str endpoint machineimage) {:cookie-store cookie-store :accept compute-content-type-json})))

(defn compute-create-machine-image [endpoint domain user password image-name file-name]
  (let [cs (compute-authenticate endpoint domain user password)
        body {:account (str "/Compute-" domain "/cloud_storage")
              :name (str "/Compute-" domain "/" user "/" image-name)
              :no_upload true
              :file file-name
              :sizes {:total 0}
              }]
    (print (cheshire/generate-string body))
    ))
(comment (compute-create-machine-image compute-endpoint t-domain t-user t-password "acme-image" "acme-file"))

;; ==================== storage ==================
(def storage-image-container "compute_images")
(def storage-segment-container "uforge_segments")
(defn- storage-url [domain & api] (apply str "https://" domain ".storage.oraclecloud.com/v1/Storage-" domain api))
(defn- storage-auth-url [domain] (str "https://" domain ".storage.oraclecloud.com/auth/v1.0"))
(spec/fdef storage-url :args (spec/cat :domain ::domain))

(defn storage-authenticate [domain user password]
  (-> (client/get (storage-auth-url domain)
                  {:headers {"X-Storage-User" (str "Storage-" domain ":" user) "X-Storage-Pass" password}})
      :headers
      (select-keys ["X-Auth-Token"])))
(comment (storage-authenticate "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01"))

(defn- storage-create-container [headers domain container-name]
  (client/put (storage-url domain "/" container-name) {:headers headers}))

(defn- storage-create-object [headers domain object-path file-path]
  (client/put (storage-url domain "/" object-path)
              {:headers headers :body (io/input-stream file-path)}))

(defn- split-file [file-path tmp-dir]
  (let [name (.getName (io/file file-path))
        split-dest (str tmp-dir "/" name "_segment_")]
    (shell/sh "split" "-b 50m" file-path split-dest)
    (->> (.list (io/file tmp-dir))
         (filter #(.startsWith % (str name "_segment_")))
         (map #(str tmp-dir "/" %))
         )))

(defn storage-upload-image [domain username password imagepath tmpdir]
  ;; start by getting the authentication header for all subsequent request
  ;; split the file to 50m chunks
  ;; then upload each chunk, retrieving the etags header
  ;; then construct the manifest.json
  ;; and construct the final object by uploading the manifest
  (let [headers (storage-authenticate domain username password)
        name (.getName (io/file imagepath))
        chunks (split-file imagepath tmpdir)]
    (->> chunks
         (map #(let [file (io/file %)
                     object-name (.getName file)
                     object-path (str storage-segment-container "/" object-name)]
                 {:path object-path
                  :size_bytes (.length file)
                  :etag (-> (storage-create-object headers domain object-path %)
                            :headers
                            (get "Etag"))}))
         (sort-by :path)
         cheshire/generate-string
         (#(client/put (storage-url domain "/" storage-image-container "/" name "?multipart-manifest=put")
                     {:headers headers :body %}))
         )))
(comment (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/centos7.raw.tar.gz" "/tmp")
         (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/win2016.tar.gz" "/tmp"))
