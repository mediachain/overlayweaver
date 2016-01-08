(ns distribution-test
  (:require
    [hamming-dht.emu :as emu :refer [get-similar]]
    [dht-test :refer [id-gen* id-distance sim->hamming
                      id-with-similarity-in-range-gen
                      id-with-similarity-gen]]
    [clojure.set :as set]
    [clojure.java.io :as io]
    [clojure.test :as test :refer [use-fixtures run-tests]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop])
  (:import (ow.id ID)
           (ow.directory.comparator HammingIDComparator)
           (ow.directory DirectoryConfiguration DirectoryFactory)))

(defn kv-gen
  "Generate a random ID/value pair."
  [id-byte-length]
  (gen/fmap #(vector % (str "value for " %)) (id-gen* id-byte-length)))

(defn kv-map-gen
  "Generate a map of `n` random ID/value pairs."
  [n id-byte-len]
  (gen/fmap (partial into {}) (gen/vector (kv-gen id-byte-len) n)))


(defn ids-with-frequencies-gen
  "Generate a vector of `n` IDs, with id length `id-byte-len`,
   with similarity distribution specified in the map `distribution`.

   Generates a 'reference key' randomly, then generates `n` similar keys according to the
   given `distribution`.  The reference key is not returned in the set.

   `distribution` should be a map of [lower-bound upper-bound] -> weight,
   where the keys are two element vectors representing a range of similarity values in the range of
   0 - 1. The lower-bound is inclusive, and upper-bound is exclusive, so e.g, [0.5 0.6] would
   produce values between 0.5 and 0.5999.


   The weight values represent the weight of that range of similarities
   in the distribution of similar keys generated.  All weights will be added together, and the
   weight of each similarity value will be (w / total).

   E.g:

   [0.5 0.6] | 1
   [0.6 0.7] | 2
   [0.7 0.8] | 4
   [0.8 0.9] | 2
   [0.9 0.99]| 1

   Will generate keys where 1/10 of the total have similarity 0.5-0.6 to the reference key, 1/5 have
   0.6-0.7 similarity, 2/5 have 0.7-0.8, etc.

   Values are strings of the form \"value for <key>\"
  "
  [n id-byte-len distribution]
  (let [freq-gen-pairs                                      ; a vector of [weight generator] pairs, where generator produces an ID in a given range
        (fn [ref-id]
          (for [[[lo-bound hi-bound] w] distribution]
            [w (id-with-similarity-in-range-gen ref-id lo-bound hi-bound)]))]
    (gen/let [ref-id (id-gen* id-byte-len)]
             (gen/vector-distinct (gen/frequency (freq-gen-pairs ref-id)) {:num-elements n}))))



(defn kv-map-with-frequencies-gen
  [n id-byte-len distribution]
  (gen/fmap #(into {} (map (fn [k] [k (str "value for " k)]) %))
            (ids-with-frequencies-gen n id-byte-len distribution)))

(defn ids-with-frequencies-sample
  [n-keys id-byte-len distribution]
  (let [batch-size (min 100 n-keys)
        num-batches (int (/ n-keys batch-size))
        batches (gen/sample (ids-with-frequencies-gen batch-size id-byte-len distribution) num-batches)]
    (into #{} (apply concat batches))))

(defn kv-map-with-frequencies-sample
  [n id-byte-len distribution]
  (into {} (map (fn [k] [k (str "value for " k)])
                (ids-with-frequencies-sample n id-byte-len distribution))))

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
          {:query-key      key :threshold threshold
           :total-expected num-known
           :total-found    retrieved-count
           :hop-results    hop-results
           :found-per-hop  (map count hop-results)
           :per-hop-percentage (if (> retrieved-count 0)
                                 (map #(float (/ (count %) retrieved-count)) hop-results)
                                 (map (constantly 0) hop-results))
           :num-hops       (count hop-results)
           :found-all?     (>= retrieved-count num-known)}
          (let [query-result (get-similar overlay key threshold hop-count)
                returned-keys (into #{} (keys query-result))
                new-keys (set/difference returned-keys retrieved-so-far)]
            #_(println "query result: " query-result)
            #_(println "returned-keys: " returned-keys)
            #_(println "new-keys: " new-keys)
            (recur (conj hop-results new-keys))))))))

(def sample-distribution
  {[0.5 0.6]  1
   [0.6 0.7]  2
   [0.7 0.8]  1.5
   [0.8 0.9]  1
   [0.9 0.95] 0.25
   [0 0.95]   2})


(def id-size-bytes 20)
(emu/quiet-logger)

(defn get-all-similar-for-all-keys
  [overlay m search-threshold]
  (let [all-keys (keys m)]
    (emu/start! overlay)
    (emu/put! overlay m)
    (let [get-similar-results
          (map #(get-all-similar overlay % search-threshold all-keys) all-keys)
          desired-keys [:total-expected :total-found :num-hops :found-per-hop :found-all?
                        :per-hop-percentage]
          results
          (->> get-similar-results
               (map (fn [result-map]
                      (into {:query-key (keyword (str (:query-key result-map)))}
                            (select-keys result-map desired-keys))))
               (into []))] ; force realization of lazy seq before stopping the overlay)
      (emu/stop! overlay)
      results)))

(defn measure-distribution-across-nodes
  [algorithms n-nodes n-keys distribution search-threshold]
  (let [m (kv-map-with-frequencies-sample n-keys id-size-bytes distribution)]
    (into {}
          (for [algorithm algorithms]
            (let [overlay (emu/make-overlay n-nodes {:algorithm algorithm})]
              [(keyword algorithm) (get-all-similar-for-all-keys overlay m search-threshold)])))))

(defn append-val [val & colls]
  (let [maxlen (apply max (map count colls))]
    (map #(concat % (repeat (- maxlen (count %)) val)) colls)))

(defn average-hop-percentage
  [result-maps]
  (let [hop-percentages (map :per-hop-percentage result-maps)
        n-results (count hop-percentages)]
    (->> hop-percentages
         (apply append-val 0)
         (apply map +)
         (map #(/ % n-results)))))

(comment
  (let [m sample-vals
        all-keys (keys m)
        overlay (emu/make-overlay 100 {:algorithm "HammingChord"})
        node (first (:nodes overlay))]
    (emu/start! overlay)
    (Thread/sleep 5000)
    (emu/put! overlay m)
    (Thread/sleep 10000)
    (let [query (first all-keys)
          threshold 0.6
          results
          (get-all-similar overlay query threshold all-keys)]
      #_(Thread/sleep 10000)
      #_(emu/stop! overlay)
      (def last-run results)
      (def test-overlay overlay)
      results)))

(comment
  (emu/stop! test-overlay))