(ns cloud.publish
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.spec.gen.alpha :as gen]
            [clojure.spec.test.alpha :as spectest]
            [clj-http.client :as client]
            [cheshire.core :as cheshire]))

(def base "http://10.1.2.124:8080/ufws/")
  (def guest-publish "users/root/pimages")
(def authorization "Basic cm9vdDp1Zm9yZ2VkZW1v")
(def accept "application/json")

(defn images [url]
  (-> (client/get url {:headers {"Accept" accept "Authorization" authorization}})
      :body
      (cheshire/parse-string true)
      :publishImages
      :publishImages
      :publishImage
      (#(map :uri %))
      ))
                                        ; (images (str base guest-publish))

(defn del-image [base image-uri]
  (time (client/delete (str base image-uri) {:headers {"Authorization" authorization}}))
  )

(defn mass-del []
  (let [uris (images (str base guest-publish))]
    (doseq [uri uris]
      (future (del-image base uri)))
    ))
