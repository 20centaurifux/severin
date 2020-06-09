(defproject zcfux/severin "0.2.0"
  :description "A library for implementing resource pools."
  :url "https://github.com/20centaurifux/severin"
  :license {:name "GPL3"
            :url "https://www.gnu.org/licenses/gpl-3.0.txt"}
  :dependencies [[org.clojure/clojure "1.10.1"]]
  :profiles {:test {:dependencies [[org.clojure/test.check "1.0.0"]]}
             :lein-codox {:target-path "doc"}}
  :plugins [[lein-codox "0.10.7"]
            [lein-cljfmt "0.6.7"]])
