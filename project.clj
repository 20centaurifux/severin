(defproject zcfux/severin "0.1.0-SNAPSHOT"
  :description "A library of implementing resource pools."
  :url "https://github.com/20centaurifux/severin"
  :license {:name "GPL3"
            :url "https://www.gnu.org/licenses/gpl-3.0.txt"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :profiles {:test {:dependencies [[org.clojure/test.check "0.9.0"]]}
             :lein-codox {:target-path "doc"}}
  :plugins [[lein-codox "0.10.3"]])
