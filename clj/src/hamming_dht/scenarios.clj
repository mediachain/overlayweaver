(ns hamming-dht.scenarios
  (:require
    [clojure.set :refer [map-invert]]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.string :as string]
    [clojure.edn :as edn])
  (:import [ow.id ID]
           [java.util Base64$Encoder Base64$Decoder Base64]))

;; utils for generating scenario files for the OW DHT emulator tools

(def id-size-bytes 4)

(defn long->id-str [_key long-val]
  (let [s (format "%h" (bigint long-val))
        digits (count s)
        needed-digits (* id-size-bytes 2)
        needed-leading-zeros (- needed-digits digits)
        zeros (apply str (repeat needed-leading-zeros \0))]
    (str "id:" zeros s)))

(defn filenames-and-hashes
  [file]
  (json/read-str (slurp file) :value-fn long->id-str))

(defn invert-data
  "
  Given a map `data` of filenames -> perceptual hashes,
  return a map of hashes -> set of filenames.
  Similar to core/map-invert, but preserves mappings between
  multiple filenames and the same perceptual hash.
  "
  [data]
  (reduce-kv
    (fn [m filename hash]
      (let [val-set (m hash #{})]
        (assoc m hash (conj val-set filename))))
    {}
    data))

(defn data-from-json-file
  [filepath]
  (-> filepath filenames-and-hashes invert-data))

(def sample-data-path (io/file (io/resource "phash-sample.json")))

(def sample-data
  (data-from-json-file sample-data-path))


(defn join-lines [& lines]
  (string/join \newline lines))

(def stat-collector
  ["class ow.tool.msgcounter.Main"
   "schedule 0 invoke"])

(defn visualizer
  [id-size-bytes]
  ["class ow.tool.visualizer.Main"
   (str "arg -i " (* 8 id-size-bytes))
   "schedule 0 invoke"])

(defn make-nodes
  [n &
   {:keys [algorithm routing-style stat-collector-addr id-byte-len wait-ms shell-port]
    :or {algorithm "HammingKademlia"
         routing-style "Iterative"
         id-byte-len id-size-bytes
         stat-collector-addr "emu0"
         wait-ms 3000
         shell-port 10000}}]
  (assert (> n 0) "First arg to `make-nodes` must be >= 1")
  (let [shell-args (str "arg"
                         " -m " stat-collector-addr
                         " -r " routing-style
                         " -a " algorithm
                         " -z " id-byte-len)]
    ["class hamming_dht.shell.HammingShell"
     (str "# First node listens on port " shell-port " for control commands")
     (str shell-args " -p " shell-port)
     "schedule 0 invoke"
     (if (> (dec n) 0)
       (join-lines
         shell-args
         (str "schedule " wait-ms ",1," (dec n) " invoke"))
       "")]))

(defn schedule-cmds
  [cmds node-nums wait-ms]
  (let [node-nums (cycle node-nums)
        wait-times (iterate (partial + wait-ms) 0)]
    (map (fn [[cmd node-num wait]]
           (str "schedule " wait
                " control " node-num
                " " cmd))
         (partition 3 (interleave cmds node-nums wait-times)))))

(defn join-overlay
  [node-nums wait-ms]
  (let [num-nodes (count node-nums)]
    (schedule-cmds (repeat num-nodes "init emu1")
                   node-nums
                   wait-ms)))

(defn base-64-encode
  "Returns a base 64 encoded version of the string `s`"
  [^String s]
  (-> (Base64/getEncoder)
    (.encodeToString (.getBytes s "UTF-8"))))

(defn base-64-decode
  "Returns the base 64 decoded version of string `s`."
  [^String s]
  (->
    (Base64/getDecoder)
    (.decode s)
    (String. "UTF-8")))

(defn encode-val
  "Prints `val` to a string, then base-64 encodes it.
  Used so that spaces in the output value don't confuse
  the overlayweaver emulator's scenario parser"
  [val]
  (-> val pr-str base-64-encode))

(defn decode-val
  "Returns the value of `val` after base-64 decoding and
  reading as an EDN value."
  [val]
  (-> val base-64-decode edn/read-string))

(defn cmd-block
  [time-offset comment command-lines]
  (join-lines
    (str "# " comment)
    (str "timeoffset " time-offset)
    (if (string? command-lines)
      command-lines
      (apply join-lines command-lines))
    ""))

(defn phash-scenario
  [num-nodes data]
  (let [put-cmds
        (map (fn [[k v]] (str "put " k " " (encode-val v))) data)]
    (join-lines
      #_(cmd-block 0 "Start stat collector (emu0)" stat-collector)
      (cmd-block 2000 "Start visualizer (emu0)" (visualizer id-size-bytes))
      (cmd-block 5000
                 (str "Make " num-nodes " DHT nodes (emu1 - emu" num-nodes ")")
                 (make-nodes num-nodes))
      (cmd-block 20000
                 (str "Nodes emu2 - emu" num-nodes " join overlay by contacting node at emu1")
                 (join-overlay (range 2 (+ 1 num-nodes)) 20))
      (cmd-block 25000
                 "Put dataset into DHT, distributing the put commands across all nodes"
        (schedule-cmds put-cmds
                     (range 1 (+ 1 num-nodes))
                     50)))))