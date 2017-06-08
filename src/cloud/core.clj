(ns cloud.core
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as spectest]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]
            )
  (:gen-class))

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
(def compute-content-json "application/oracle-compute-v3+json")
(def compute-content-directory "application/oracle-compute-v3+directory+json")
(defn compute-authenticate [endpoint domain user password]
  "returns a clj-http cookie-store to be used in later request; or ::bad-credentials / ::invalid-endpoint"
  (spec/assert ::cloud-endpoint endpoint)
  (let [cs (clj-http.cookies/cookie-store)]
    (client/post (str endpoint "/authenticate/")
                 {:content-type compute-content-json
                  :cookie-store cs
                  :body (cheshire/generate-string {:user (str "/Compute-" domain "/" user) :password password})})
    cs))
(spec/fdef compute-authenticate
           :args ::authenticate-args
           :ret #(instance? org.apache.http.impl.client.BasicCookieStore %))
(comment (compute-authenticate compute-endpoint "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01"))

(defn compute-create-machine-image [endpoint domain user password file-name]
  "Creates an machine image from a storage object. The storage object must be a raw disk compressed as .tar.gz
   Returns the machine-image name (typically the same as the file-name)"
  (let [cs (compute-authenticate endpoint domain user password)
        body {:account (str "/Compute-" domain "/cloud_storage")
              :name (str "/Compute-" domain "/" user "/" file-name)
              :no_upload true
              :file file-name
              :sizes {:total 0}}
        body-str (cheshire/generate-string body)]
    (println "compute-create-machine-image: PUT body ->" body-str)
    (let [response (client/post (str endpoint "machineimage/") {:accept compute-content-json :body body-str :cookie-store cs :throw-exceptions false})]
      (println "COMPUTE machineimage/ reponse status: " (:status response))
      response)))
(comment (compute-create-machine-image compute-endpoint t-domain t-user t-password "win2016.tar.gz"))

(defn compute-create-image-list [endpoint domain user password image-list-name image-list-description machine-image-name]
  "Creates machine image list and its image list entry for the provided machine-image"
  (let [cs (compute-authenticate endpoint domain user password)
        imagelist-body {:default 1 :description image-list-description :name (str "/Compute-" domain "/" user "/" image-list-name)}
        imagelist-body-str (cheshire/generate-string imagelist-body)
        entry-body {:attributes {} :version 1 :machineimages [(str "/Compute-" domain "/" user "/" machine-image-name)]}
        entry-body-str (cheshire/generate-string entry-body)]
    ;; create the imagelist
    (println "imagelist: POST body ->" imagelist-body-str)
    (client/post (str endpoint "imagelist/Compute-" domain "/" user "/") {:accept compute-content-json :body imagelist-body-str :cookie-store cs})
    ;; create the imagelist entry
    (println "imagelist entry: POST body ->" entry-body-str)
    (client/post (str endpoint "imagelist/Compute-" domain "/" user "/" image-list-name "/entry/") {:accept compute-content-json :body entry-body-str :cookie-store cs})))
(comment (compute-create-image-list compute-endpoint t-domain t-user t-password "thach-test-image-list" "testing image list" "win2016.tar.gz"))

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
  (println "storage-create-object: creating " object-path " from " file-path)
  (let [request (client/put (storage-url domain "/" object-path)
                            {:headers headers :body (io/input-stream file-path)})]
    (println "storage-create-object: http status for " object-path (:status request))
    request))

(defn- split-file [file-path tmp-dir]
  (let [name (.getName (io/file file-path))
        split-dest (str tmp-dir "/" name "_segment_")]
    (println "split-file: " file-path " using tmp " tmp-dir)
    (shell/sh "split" "-b 200m" file-path split-dest)
    (->> (.list (io/file tmp-dir))
         (filter #(.startsWith % (str name "_segment_")))
         (map #(str tmp-dir "/" %))
         )))

(defn storage-upload-image [domain username password imagepath tmpdir]
  ;; start by getting the authentication header for all subsequent request
  ;; split the file to 200m chunks
  ;; then upload each chunk, retrieving the etags header
  ;; then construct the manifest.json
  ;; and construct the final object by uploading the manifest
  (let [headers #(storage-authenticate domain username password)
        name (.getName (io/file imagepath))
        chunks (split-file imagepath tmpdir)]
    (->> chunks
         (map #(let [file (io/file %)
                     object-name (.getName file)
                     object-path (str storage-segment-container "/" object-name)]
                 {:path object-path
                  :size_bytes (.length file)
                  :etag (-> (storage-create-object (headers) domain object-path %)
                            :headers
                            (get "Etag"))}))
         (sort-by :path)
         cheshire/generate-string
         (#(client/put (storage-url domain "/" storage-image-container "/" name "?multipart-manifest=put")
                     {:headers (headers) :body %}))
         )))
(comment (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/centos7.raw.tar.gz" "/home/thach/tmp")
         (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/win2016.tar.gz" "/home/thach/tmp"))

;; ==================== fusion ==================
(defn -main
  [compute-endpoint domain user password image-path tmp-dir image-name image-description]
  (let [file-name (.getName (io/file image-path))]
    (println "================================")
    (println "Creating Oracle Cloud image" image-name "from" image-path)
    (println "Using account" (str domain "/" user))
    (println "================================")
    (storage-upload-image domain user password image-path tmp-dir)
    (compute-create-machine-image compute-endpoint domain user password file-name)
    (compute-create-image-list compute-endpoint domain user password image-name image-description file-name)
    ))
(comment (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/centos7.tar.gz" "/home/thach/tmp" "test-full-cycle" "everything from api"))
