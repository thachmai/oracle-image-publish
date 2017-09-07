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
                  :body (cheshire/generate-string {:user (str "/Compute-" domain "/" user) :password password})} )
    cs))
(spec/fdef compute-authenticate
           :args ::authenticate-args
           :ret #(instance? org.apache.http.impl.client.BasicCookieStore %))
(comment
  (compute-authenticate compute-endpoint t-domain t-user t-password)
  (def t-cs (compute-authenticate compute-endpoint t-domain t-user t-password))
  )

(defn hack-auth [domain user password]
  (let [cs (clj-http.cookies/cookie-store)
        formdata {:username user
                  :userid user
                  :password password
                  :tenantName domain
                  :tenantDisplayName domain
                  }
        f2 "username=ORACLECLOUD%40USHARESOFT.COM&password=%21USSOraUForge01&userid=ORACLECLOUD%40USHARESOFT.COM&request_id=-720858685962184372&error_code=null&buttonAction=local&oam_mt=true&ovm=null&cloud=null&forgotPasswordUrl=https%3A%2F%2Flogin.em2.oraclecloud.com%3A443%2Fidentity%2Ffaces%2Fforgotpassword%3FbackUrl%3Dhttps%253A%252F%252Fcomputeui.emea.oraclecloud.com%252Fmycompute%252Fconsole%252Fview.html%253Fpage%253Dinstances%2526tab%253Dinstances%26checksum%3DBD3B664E7DDFC6AD95696A91A09058A36F05D42A7F53E7AE5A8F7C63DA8D76B3&registrationUrl=https%3A%2F%2Flogin.em2.oraclecloud.com%3A443%2Fidentity%2Ffaces%2Fregister%3FbackUrl%3Dhttps%253A%252F%252Fcomputeui.emea.oraclecloud.com%252Fmycompute%252Fconsole%252Fview.html%253Fpage%253Dinstances%2526tab%253Dinstances%26checksum%3DBD3B664E7DDFC6AD95696A91A09058A36F05D42A7F53E7AE5A8F7C63DA8D76B3&trackRegistrationUrl=https%3A%2F%2Flogin.em2.oraclecloud.com%3A443%2Fidentity%2Ffaces%2Ftrackregistration%3FbackUrl%3Dhttps%253A%252F%252Fcomputeui.emea.oraclecloud.com%252Fmycompute%252Fconsole%252Fview.html%253Fpage%253Dinstances%2526tab%253Dinstances%26checksum%3DBD3B664E7DDFC6AD95696A91A09058A36F05D42A7F53E7AE5A8F7C63DA8D76B3&tenantDisplayName=a491487&tenantName=a491487&troubleShootFlow=null"
        ]
    (println "Form data:" (cheshire/generate-string formdata))
    (-> (client/post "https://login.em2.oraclecloud.com/oam/server/auth_cred_submit"
                     {:content-type compute-content-json
                      :cookie-store cs
                      :debug true
                      :body f2 }) ;(cheshire/generate-string formdata)})
        ;:headers
        (#(print %1))
        )
    cs))

(comment
  "zone experiment"
  (def t-cs (hack-auth t-domain t-user t-password))
  (client/get "https://computeui.emea.oraclecloud.com/mycompute/rest/context/zonesStats" {:cookie-store t-cs})
  (client/get "https://computeui.emea.oraclecloud.com/mycompute/rest/context/pageContext" {:cookie-store t-cs})
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

(defn storage-empty-container [headers domain container-name]
  (->> (client/get (storage-url domain "/" container-name) {:headers headers})
       :body
       clojure.string/split-lines
       (map #(client/delete (storage-url domain "/" container-name "/" %) {:headers headers}))
       ))
(comment (storage-empty-container h t-domain "ChunckYann")
         (storage-empty-container h t-domain "ImageYann")
         (storage-empty-container h t-domain "compute_images_segments")
         (def deletes (storage-empty-container h t-domain "uforge_segments")))

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
(comment (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/data/Downloads/tmp/centos7-2.tar.gz" "/home/thach/tmp" "test-full-cycle" "everything from api")
         (-main "https://compute.gbcom-south-1.oraclecloud.com/" "a491487" "oraclecloud@usharesoft.com" "!USSOraUForge01" "/home/thach/Downloads/tmp/win2012.tar.gz" "/home/thach/tmp" "uforgewin" "everything from api"))
