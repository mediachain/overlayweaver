(ns hamming-dht.scenarios
  (:require
    [clojure.set :refer [map-invert]]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.string :as string])
  (:import [ow.id ID]))

;; utils for generating scenario files for the OW DHT emulator tools

(def id-size-bytes 2)

(defn long->id-str [_key long-val]
  (str "id:" (format "%h" long-val)))


(defn filenames-and-hashes
  [file]
  (json/read-str (slurp file) :value-fn long->id-str))


(def sample-data-path (io/file (io/resource "phash-sample.json")))
(def sample-data (filenames-and-hashes sample-data-path))
(def samples-by-id (map-invert sample-data))

(defn join-lines [& lines]
  (string/join \newline lines))

(def stat-collector
  (join-lines
    "# Start stat collector"
    "class ow.tool.msgcounter.Main"
    "schedule 0 invoke"
    ""))

(defn make-nodes
  [n &
   {:keys [algorithm routing-style stat-collector-addr id-byte-len wait-ms]
    :or {algorithm "HammingKademlia"
         routing-style "Iterative"
         id-byte-len 20
         stat-collector-addr "emu0"
         wait-ms 3000}}]
  (assert (> n 0) "First arg to `make-nodes` must be >= 1")
  (join-lines
    "# Start dht instances"
    "class hamming_dht.shell.HammingShell"
    (str "arg"
         " -m " stat-collector-addr
         " -r " routing-style
         " -a " algorithm
         " -z " id-byte-len)
    "schedule 0 invoke"
    (str "schedule " wait-ms ",1," n " invoke")
    ""))

(defn schedule-cmds
  [cmds num-nodes wait-ms]
  (let [node-nums (cycle (range 1 (+ 1 num-nodes)))
        wait-times (iterate (partial + wait-ms) wait-ms)]
    (apply join-lines
      (map (fn [[cmd node-num wait]]
             (str "schedule " wait
                  " control " node-num
                  " " cmd))
           (partition 3 (interleave cmds node-nums wait-times))))))

(defn phash-scenario
  [num-nodes]
  (let [data samples-by-id ; TODO accept `data` as arg, don't hardcode
        put-cmds
        (map (fn [[k v]] (str "put " k " " v)) data)]
    (join-lines
      stat-collector
      (make-nodes num-nodes)
      (schedule-cmds put-cmds num-nodes 500))))