(ns distribution-test
  (:require
    [hamming-dht.emu :as emu :refer [get-similar]]
    [dht-test :refer [id-gen* id-distance sim->hamming]]
    [clojure.set :as set]
    [clojure.test :as test :refer [use-fixtures run-tests]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop])
  (:import (ow.id ID)
           (ow.directory.comparator HammingIDComparator)
           (ow.directory DirectoryConfiguration DirectoryFactory)))

(defn kv-gen
  [id-byte-length]
  (gen/fmap #(vector % (str "value for " %)) (id-gen* id-byte-length)))

(defn kv-map-gen
  [n id-byte-len]
  (gen/fmap (partial into {}) (gen/vector (kv-gen id-byte-len) n)))

(defn similar? [id-1 id-2 threshold]
  (let [similarity (float (HammingIDComparator/getSimilarity id-1 id-2))]
    (>= similarity (float threshold))))

(defn within-threshold
  "Returns the set of IDs from `ids` within similarity `threshold` of `key`"
  [ids key threshold]
  (into #{} (filter #(similar? key % threshold) ids)))

(defn get-all-similar
  "Try to get all keys similar to `key` from `overlay`, within `threshold`.
  `known-within-threshold` is a set containing all keys in `overlay` that are
  known to be within the threshold.

  Will iteratively call `.getSimilar` on a randomly selected dht node from `overlay`,
  increasing the hop count until all known-similar keys are retrieved, or until hop count
  is >= the total number of nodes.

  Returns a map of the form:
  {:query-key key
   :threshold threshold
   :total-expected (count known-within-threshold)
   :total-found num-results
   :hop-results [r0 r1 r2 r3]
   :num-hops (count hop-results)}

  Where `r0`, etc are the set of IDs retrieved for each hop
  "
  [overlay key threshold all-keys]
  (let [known-within-threshold (within-threshold all-keys key threshold)
        num-known (count known-within-threshold)
        num-nodes (count (:nodes overlay))]
    (loop [hop-results []]
      (let [hop-count (count hop-results)
            retrieved-so-far (set (apply concat hop-results))
            retrieved-count (count retrieved-so-far)
            done? (or (>= retrieved-count num-known)
                      (> hop-count num-nodes))]
        (if done?
          {:query-key key :threshold threshold
           :total-expected num-known
           :total-found retrieved-count
           :hop-results hop-results
           :num-hops (count hop-results)
           :found-all? (>= retrieved-count num-known)}
          (let [query-result (get-similar overlay key threshold hop-count)
                returned-keys (into #{} (keys query-result))
                new-keys (set/difference returned-keys retrieved-so-far)]
            (println "query result: " query-result)
            (println "returned-keys: " returned-keys)
            (println "new-keys: " new-keys)
            (recur (conj hop-results new-keys))))))))

(def sample-vals (first (gen/sample (kv-map-gen 100 20) 1)))
(def dir (.openMultiValueDirectory
           (DirectoryFactory/getProvider "VolatileMap")
           ID String "/tmp" "testing-db" (DirectoryConfiguration/getDefaultConfiguration)))

(defn put-dir [dir m]
  (doseq [[k v] m]
    (.put dir k v)))

(comment
  (let [m sample-vals
        all-keys (keys m)
        overlay (emu/make-overlay 10 {:algorithm "HammingChord"})
        node (first (:nodes overlay))]
    (emu/start! overlay)
    (Thread/sleep 1000)
    (emu/put! overlay m)
    (Thread/sleep 1000)
    (let [query (first all-keys)
          threshold 0.5
          results
          (get-all-similar overlay query threshold all-keys)]
      #_(Thread/sleep 10000)
      #_(emu/stop! overlay)
      (def last-run results)
      (def test-overlay overlay)
      results)))

(comment
  (emu/stop! test-overlay))