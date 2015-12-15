(defproject nyc.mine/hamming-dht "0.1.0-SNAPSHOT"
  :description "A DHT with similarity search"
  :plugins [[lein-expand-resource-paths "0.0.1"]]
  :resource-paths ["lib/*"]
  :source-paths ["clj/src" "clj/test"]
  :java-source-paths ["src"]
  :dependencies [[org.clojure/clojure "1.7.0"]]

  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]]}})
