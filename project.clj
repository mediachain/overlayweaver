(defproject nyc.mine/hamming-dht "0.1.0-SNAPSHOT"
  :description "A DHT with similarity search"
  :plugins [[lein-expand-resource-paths "0.0.1"]]
  :resource-paths ["lib/*" "src/resources"]
  :source-paths ["clj/src" "clj/test"]
  :java-source-paths ["src"]
  :javac-options ["-target" "8" "-source" "8"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.cli "0.3.3"]
                 [org.clojure/core.async "0.2.374"]]
  :jvm-opts [; VisualVM options (connect to localhost, port 43210, no authentication)
             "-Dcom.sun.management.jmxremote"
             "-Dcom.sun.management.jmxremote.ssl=false"
             "-Dcom.sun.management.jmxremote.authenticate=false"
             "-Dcom.sun.management.jmxremote.port=43210"

             ; Increase max heap size to test large overlays / lots of keys
             "-Xms512m"
             "-Xmx4096m"]
  :aot [hamming-dht.shell]
  :main hamming-dht.shell
  :profiles {:dev {:dependencies [[org.clojure/test.check "0.9.0"]
                                  [org.clojure/math.combinatorics "0.1.1"]]}})
