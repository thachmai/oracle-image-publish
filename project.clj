(defproject cloud "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Proprietary"
            :url ""}
  :resource-paths ["/home/thach/UShareSoft/WKS/kata/gildedrose-refactoring-kata/target/gildedrose-refactoring-kata-1.0-SNAPSHOT.jar"]
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/test.check "0.9.0"]
                 [clj-http "3.6.0"]
                 [cheshire "5.7.1"]
                 [org.tomitribe/tomitribe-http-signatures "1.0"]
                 ]
  :aot :all
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]]}}
  :main ov2.core
  :eval-in-leiningen true)
