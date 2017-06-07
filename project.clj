(defproject cloud "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Proprietary"
            :url ""}

  :dependencies [[org.clojure/clojure "1.9.0-alpha16"]
                 [org.clojure/test.check "0.9.0"]
                 [clj-http "3.6.0"]
                 [cheshire "5.7.1"]
                 ]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]]}})
