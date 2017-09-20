(ns cloud.core
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as spectest]
            [clj-http.client :as client]
            [cheshire.core :as cheshire])
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
(def t-password "!USSOraUForge02")

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
                  :body (cheshire/generate-string {:user (str "/Compute-" domain "/" user) :password password})} )
    cs))
(spec/fdef compute-authenticate
           :args ::authenticate-args
           :ret #(instance? org.apache.http.impl.client.BasicCookieStore %))
(comment
  (compute-authenticate compute-endpoint t-domain t-user t-password)
  (def t-cs (compute-authenticate compute-endpoint t-domain t-user t-password))
  )

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
    (println "compute-create-machine-image: POST body ->" body-str)
    (let [response (client/post (str endpoint "machineimage/") {:accept compute-content-json :body body-str :cookie-store cs :throw-exceptions false})]
      (println "compute-create-machine-image: HTTP STATUS" (:status response))
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

;; the compute instances are still in the "classic" orchestration
(defn compute-orc-list [cs endpoint domain user]
  (println "compute-orc-list: retrieving for " user)
  (-> (client/get (str endpoint "orchestration/Compute-" domain "/" user "/")
              {:content-type compute-content-json :accept compute-content-directory
               :cookie-store cs})
      :body
      (cheshire/parse-string true)
      :result
      ))
(comment
  (compute-orc-list t-cs compute-endpoint t-domain t-user)
  )

(defn compute-orc-get [cs endpoint orc]
  (println "compute-orc-get: status for" orc)
  (-> (client/get (str endpoint "orchestration" orc)
                  {:accept compute-content-json :cookie-store cs})
      :body
      (cheshire/parse-string true)
  ))
(comment
  (compute-orc-get t-cs compute-endpoint "/Compute-a491487/oraclecloud@usharesoft.com/thach-dup-1_20170919134748_master")
  )

(defn- -compute-orc-put [cs endpoint orc action]
  (println "-compute-orc-put" orc action)
  (client/put (str endpoint "orchestration" orc "?action=" action)
              {:accept compute-content-json :content-type compute-content-json
               :cookie-store cs}))
(defn compute-orc-stop [cs endpoint orc] (-compute-orc-put cs endpoint orc "STOP"))
(defn compute-orc-start [cs endpoint orc] (-compute-orc-put cs endpoint orc "START"))
(defn compute-orc-delete [cs endpoint orc]
  (println "compute-orc-delete" orc)
  (client/delete (str endpoint "orchestration" orc) {:cookie-store cs}))

;; predicate gets passed the name of the orchestration and must return true to delete it
(defn compute-orc-clean [endpoint domain user password predicate]
  (let [cs (compute-authenticate endpoint domain user password)
        extract-orc (fn [orc-name] (nth (re-find #"(/[^/]+$)" orc-name) 1))]
    (loop [orcs (filter predicate (compute-orc-list cs endpoint domain user))]
      (let [orc-details (map (partial compute-orc-get cs endpoint) orcs)
            ready (filter #(= "ready" (:status %)) orc-details)
            stoppings (filter #(= "stopping" (:status %)) orc-details)
            stoppeds (filter #(= "stopped" (:status %)) orc-details)]
        (println (map #(select-keys % [:name :status]) orc-details))
        (when (not-empty ready)
          (do
            (println "Stopping orchestrations:" (map :name ready))
            (doseq [r ready]
              (compute-orc-stop cs endpoint (:name r)))))
        (when (not-empty stoppeds)
          (do
            (println "Deleting orchestrations:" (map :name stoppeds))
            (doseq [s stoppeds]
              (compute-orc-delete cs endpoint (:name s)))))
        (when (not-empty orc-details)
          (do
            (println "zzz for 60 seconds")
            (Thread/sleep 60000)
            (recur (filter predicate (compute-orc-list cs endpoint domain user)))))
      ))))
(comment
  (compute-orc-clean compute-endpoint t-domain t-user t-password #(.contains % "thach"))
  )

;; ==================== storage ==================
(def storage-image-container "compute_images")
(def storage-segment-container "uforge_segments")
(defn- storage-url [domain & api] (apply str "https://" domain ".storage.oraclecloud.com/v1/Storage-" domain api))
(defn- storage-auth-url [domain] (str "https://" domain ".storage.oraclecloud.com/auth/v1.0"))
(spec/fdef storage-url :args (spec/cat :domain ::domain))
(defn storage-authenticate [domain user password]
  (println "storage-authenticate: getting token for " domain user)
  (-> (client/get (storage-auth-url domain)
                  {:headers {"X-Storage-User" (str "Storage-" domain ":" user) "X-Storage-Pass" password}})
      :headers
      (select-keys ["X-Auth-Token"])))
(comment (def h (storage-authenticate "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01")))

(defn- storage-create-container [headers domain container-name]
  (client/put (storage-url domain "/" container-name) {:headers headers}))

(defn- storage-create-object [headers domain object-path file-path]
  (println "storage-create-object: creating " object-path " from " file-path)
  (let [request (client/put (storage-url domain "/" object-path)
                            {:headers headers :body (io/input-stream file-path)})]
    (println "storage-create-object: HTTP STATUS" object-path (:status request))
    request))

(defn- split-file [file-path tmp-dir]
  (let [name (.getName (io/file file-path))
        split-dest (str tmp-dir "/" name "_segment_")]
    (println "split-file: " file-path " using tmp " tmp-dir)
    (shell/sh "split" "-b 20m" file-path split-dest)
    (->> (.list (io/file tmp-dir))
         (filter #(.startsWith % (str name "_segment_")))
         (map #(str tmp-dir "/" %))
         )))

(defn- storage-throttle-auth-headers [domain user password throttle]
  (let [now (.getTime (new java.util.Date))]
    (if (nil? @throttle)
      (reset! throttle {:time now :headers (storage-authenticate domain user password)})
      (when (< (* 20 60 1000) (- now (:time @throttle)))
        (reset! throttle {:time now :headers (storage-authenticate domain user password)}))))
  (:headers @throttle))

(defn storage-upload-image [domain username password imagepath tmpdir]
  ;; start by getting the authentication header for all subsequent request
  ;; split the file to 200m chunks
  ;; then upload each chunk, retrieving the etags header
  ;; then construct the manifest.json
  ;; and construct the final object by uploading the manifest
  (let [last (atom (.getTime (new java.util.Date)))
        last-headers (atom (storage-authenticate domain username password))
        headers #(let [now (.getTime (new java.util.Date))]
                   (when (< (* 20 60 1000) (- now @last))
                     (do
                       (reset! last-headers (storage-authenticate domain username password))
                       (reset! last now)))
                   @last-headers)
        name (.getName (io/file imagepath))
        chunks (split-file imagepath tmpdir)]
    (->> chunks
         (map #(let [file (io/file %)
                     object-name (.getName file)
                     object-path (str storage-segment-container "/" object-name)]
                 (println "mapping chunk " object-path)
                 {:path object-path
                  :size_bytes (.length file)
                  :etag (-> (storage-create-object (headers) domain object-path %)
                            :headers
                            (get "Etag"))}))
         (sort-by :path)
         cheshire/generate-string
         ;(#(client/put (storage-url domain "/" storage-image-container "/" name "?multipart-manifest=put")
         ;            {:headers (headers) :body %}))
         ; dynamic upload
         (#(client/put (storage-url domain "/" storage-image-container "/" name)
                       {:headers (merge (headers)
                                        {"X-Object-Manifest" (str storage-segment-container "/" name)})}))
         )))
(comment (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/centos7.raw.tar.gz" "/home/thach/tmp")
         (storage-upload-image "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/win2016.tar.gz" "/home/thach/tmp"))

(defn storage-download [domain user password objects files]
  (let [os (if (string? objects) [objects] objects)
        fs (if (string? files) [files] files)
        throttle (atom nil)]
    (when (not= (count os) (count fs)) (new Exception "objects and files must be symetric"))
    (doseq [i (range (count os))]
      (println "downloading" (nth os i) "to" (nth fs i))
      (clojure.java.io/copy
       (:body (client/get (storage-url domain "/" (nth os i))
                          {:as :stream
                           :headers (storage-throttle-auth-headers domain user password throttle)}))
       (java.io.File. (nth fs i))))))

(defn storage-get-container [h domain container]
  (println "storage-get-container " container)
  (let [body (:body (client/get (storage-url domain "/" container) {:headers h}))]
    (if (nil? body) [] (clojure.string/split-lines body))))

(defn storage-empty-container
  ([headers domain container predicate]
   (doseq [container-file (storage-get-container headers domain container)]
     (when (predicate container-file)
       (println "Deleting " container "/" container-file "...")
       (client/delete (storage-url domain "/" container "/" container-file) {:headers headers}))))

  ([headers domain container]
   (storage-empty-container headers domain container (constantly true))))

(comment (storage-empty-container h t-domain "ChunckYann")
         (storage-empty-container h t-domain "ImageYann")
         (storage-empty-container h t-domain "compute_images_segments")
         (storage-empty-container h t-domain "compute_images")
         (def deletes (storage-empty-container h t-domain "uforge_segments")))

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
(comment (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/thach-win3.tar.gz" "/home/thach/tmp" "thach-win3" "everything from api")
         (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/home/thach/Downloads/tmp/thach-2016-1.tar.gz" "/home/thach/tmp" "thach-2016-1" "dynamic upload")
         (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/home/thach/Downloads/tmp/win2012.tar.gz" "/home/thach/tmp" "uforgewin" "everything from api"))
