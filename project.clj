(defproject nyc.mine/hamming-dht "0.1.0-SNAPSHOT"
  :description "A DHT with similarity search"
  :plugins [[lein-expand-resource-paths "0.0.1"]]
  :resource-paths ["lib/*" "src/resources"]
  :source-paths ["clj/src" "clj/test"]
  :java-source-paths ["src"]
  :javac-options ["-target" "8" "-source" "8"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.json "0.2.6"]]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]
                                  [org.clojure/math.combinatorics "0.1.1"]]}})
