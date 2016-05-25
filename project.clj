(defproject partition "0.1.0-SNAPSHOT"
  :description "A tool solving partition problem"
  :url "http://example.com/FIXME"
  :license {:name "MIT License"
            :url "http://www.opensource.org/licenses/mit-license.php"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [http-kit "2.1.18"]
                 [org.clojure/tools.cli "0.3.5"]]
  :main ^:skip-aot partition.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
