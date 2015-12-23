(ns hamming-dht.scenarios
  (:require
    [clojure.set :refer [map-invert]]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.string :as string]
    [clojure.core.async :refer [chan thread <!! >!!]])
  (:import [ow.id ID]
           [java.io PipedOutputStream PipedInputStream OutputStreamWriter]))

;; utils for generating scenario files for the OW DHT emulator tools

(def id-size-bytes 4)

(defn long->id-str [_key long-val]
  (let [id (ID/getID (biginteger long-val) id-size-bytes)
        s (.toString id)]
    (str "id:" s)))

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

(defn load-sample-data []
  (data-from-json-file sample-data-path))


(defn join-lines [& lines]
  (string/join \newline lines))

(def stat-collector
  "Launch the message-counter tool in the emulator.
  If you use this, don't use `visualizer`"

  ["class ow.tool.msgcounter.Main"
   "schedule 0 invoke"])

(defn visualizer
  "Launch the overlay visualizer in the emulator.
  If you use this, don't use `stat-collector`."
  [id-size-bytes]
  ["class ow.tool.visualizer.Main"
   (str "arg -i " (* 8 id-size-bytes))
   "schedule 0 invoke"])

(defn make-nodes
  "Create `n` emulated DHT node instances, using some optional configuration values.
  `n` must be > 0.
  The first node will listen on `shell-port` (default 10000) on localhost for control commands,
  so you can telnet in to poke at the network.
  "
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
  "Given a finite seq of command strings `cmds`, and a seq of node numbers `node-nums`,
   distributes the commands across the numbered nodes.
   Will wait `wait-ms` between command invocations."
  [cmds node-nums wait-ms]
  (let [node-nums (cycle node-nums)
        wait-times (iterate (partial + wait-ms) 0)]
    (map (fn [[cmd node-num wait]]
           (str "schedule " wait
                " control " node-num
                " " cmd))
         (partition 3 (interleave cmds node-nums wait-times)))))

(defn join-overlay
  "Returns a seq of commands that will schedule the nodes
  in the (finite) seq `node-nums` to join the overlay by contacting the node 'emu1'.
  Will wait `wait-ms` between each join attempt."
  [node-nums wait-ms]
  (let [num-nodes (count node-nums)]
    (schedule-cmds (repeat num-nodes "init emu1")
                   node-nums
                   wait-ms)))



(defn cmd-block
  "Returns a string defining a group of related commands,
  preceeded by `comment`, to be invoked at the specified
  `time-offset`, which refers to milliseconds after emulation start."
  [time-offset comment command-lines]
  (join-lines
    (str "# " comment)
    (str "timeoffset " time-offset)
    (if (string? command-lines)
      command-lines
      (apply join-lines command-lines))
    ""))

(defn phash-scenario
  [num-nodes data &
   {:keys [algorithm]}]
  (let [put-cmds
        (map (fn [[k vals]]
               (apply str "put " k " "
                      (string/join " " vals))) data)
        get-cmds (map #(str "get " %) (keys data))]
    (join-lines
      #_(cmd-block 0 "Start stat collector (emu0)" stat-collector)
      (cmd-block 2000 "Start visualizer (emu0)" (visualizer id-size-bytes))
      (cmd-block 5000
                 (str "Make " num-nodes " DHT nodes (emu1 - emu" num-nodes ")")
                 (make-nodes num-nodes :algorithm algorithm))
      (cmd-block 20000
                 (str "Nodes emu2 - emu" num-nodes " join overlay by contacting node at emu1")
                 (join-overlay (range 2 (+ 1 num-nodes)) 20))
      (cmd-block 25000
                 "Put dataset into DHT, distributing the put commands across all nodes"
                 (schedule-cmds put-cmds
                                (range 1 (+ 1 num-nodes))
                                50))
      (cmd-block 30000
                 "Get each key from the DHT, distributing the get commands across all nodes"
                 (schedule-cmds get-cmds
                                (shuffle (range 1 (+ 1 num-nodes)))
                                50)))))

(defn phash-small
  "Returns `phash-scenario` with the given alogrithm,
  using just the first 100 key/value pairs from the sample-data set."
  [algorithm]
  (phash-scenario 100 (into {} (take 100 (load-sample-data))) :algorithm algorithm))


(defn run-scenario!
  "Runs the scenario in `scenario-text` in an OW emulator.
  The emulator runs in the same process as the caller, but in a
  new thread.
  Returns a 0-arity fn that will halt all emulated nodes when invoked.

  Warning! Don't use this for anything serious! The emulator's Main
  class calls System.exit() if things go awry, and quiting the visualizer
  GUI will kill the process you invoke this fn from.

  "
  [scenario-text]
  (let [kill-chan (chan)
        kill-fn #(thread (>!! kill-chan :killed))
        system-in System/in
        out-pipe (PipedOutputStream.)
        out-writer (OutputStreamWriter. out-pipe)
        in-pipe (PipedInputStream. out-pipe)
        args (into-array String [])]
    (thread
      (System/setIn in-pipe)
      (let [emu (ow.tool.emulator.Main.)]
        (.start emu args))
      (System/setIn system-in))

    (thread
      (binding [*out* out-writer]
        (println scenario-text)
        (<!! kill-chan)
        (println "halt")))

    kill-fn))