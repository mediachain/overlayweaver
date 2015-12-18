(ns hamming-dht.scenarios
  (:require
    [clojure.set :refer [map-invert]]
    [clojure.java.io :as io]
    [clojure.data.json :as json])
  (:import [ow.id ID]))

;; utils for generating scenario files for the OW DHT emulator tools

(def id-size-bytes 2)

(defn long->id-str [long-val]
  (str "id:" (format "%h" long-val)))


(defn long->id [_key long-val]
  (ID/parseID (long->id-str long-val) id-size-bytes))

(defn filenames-and-hashes
  [file]
  (json/read-str (slurp file) :value-fn long->id))


(def sample-data-path (io/file (io/resource "phash-sample.json")))
(def sample-data (filenames-and-hashes sample-data-path))
(def samples-by-id (map-invert sample-data))

