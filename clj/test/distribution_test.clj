(ns distribution-test
  (:require
    [hamming-dht.emu :as emu :refer [get-similar]]
    [dht-test :refer [id-gen* id-distance sim->hamming
                      id-with-similarity-in-range-gen
                      id-with-similarity-gen]]
    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.java.io :as io]
    [clojure.test :as test :refer [use-fixtures run-tests]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [com.stuartsierra.frequencies :as freq])
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

(defn invert-data
  [data]
  (reduce-kv
    (fn [m k v]
      (let [val-set (m v #{})]
        (assoc m v (conj val-set k))))
    {}
    data))

(defn hop-count-for-value-info-set
  [value-info-set]
  (apply max (map #(.getExtraHopCount %) value-info-set)))

(defn query-results-by-hop-count
  [res]
  (invert-data
    (into {} (for [[k value-info-set] res
                   :let [hop-count (hop-count-for-value-info-set value-info-set)]]
               [k hop-count]))))

(defn hop-count-frequencies
  [query-result]
  (let [hop-counts (map (fn [[_ value-info-set]]
                             (hop-count-for-value-info-set value-info-set))
                        query-result)]
    (frequencies hop-counts)))


(defn get-all-similar
  "Try to get all keys similar to `key` from `overlay`, within `threshold`.
  `known-within-threshold` is a set containing all keys in `overlay` that are
  known to be within the threshold.

  Will call `.getSimilar` on a randomly selected dht node from `overlay`,
  with a maximum 'extra hop' count equal to the total number of nodes in the overlay.
  Returned results are examined to determine the number of hops needed to retreive them.

  Returns a map of the form:
   key                 |    val
   --------------------+--------------------------------
   :query-key          | key used to search
   :threshold          | search threshold
   :raw-result         | unmodified result of calling `.getSimilar`
   :total-expected     | number of known similar results to `key` found in `all-keys`
   :total-found        | number of similar keys found
   :found-all?         | true if total-expected == total-found
   :by-hops            | map of <hop-number> to <set-of-results>
   :hop-results        | vector of result sets returned for each hop. will contain empty sets if
                       | no results were returned for that hop
   :found-per-hop      | number of results found per hop
   :per-hop-percentage | proportion of total results found per hop (range 0 - 1)
   :num-hops           | number of hops required to retrieve all similar keys

  Note that 'hop #0' is the node responsible for the query key.  Subsequent hops are the
  neighbors of the responsible node (and their neighbors, etc).
  "
  [overlay key threshold all-keys]
  (let [known-within-threshold (within-threshold all-keys key threshold)
        num-known (count known-within-threshold)
        num-nodes (count (:nodes overlay))
        max-hops num-nodes
        query-result (get-similar overlay key threshold max-hops)
        total-found (count query-result)
        by-hops (query-results-by-hop-count query-result)

        hops-needed (+ 1 (apply max (or (seq (keys by-hops)) [0])))
        hop-results (map #(get by-hops % #{}) (range hops-needed))
        hop-percentages
        (if (> total-found 0)
          (map #(float (/ (count %) total-found)) hop-results)
          (map (constantly 0) hop-results))]

    {:query-key          key
     :raw-result         query-result
     :by-hops            by-hops
     :threshold          threshold
     :total-expected     num-known
     :total-found        total-found
     :num-hops           hops-needed
     :hop-results        hop-results
     :hop-frequencies    (hop-count-frequencies query-result)
     :found-per-hop      (map count hop-results)
     :found-all?         (>= total-found num-known)
     :per-hop-percentage (into [] hop-percentages)}))

(def sample-distribution
  {[0.5 0.6]  1
   [0.6 0.7]  2
   [0.7 0.8]  1.5
   [0.8 0.9]  1
   [0.9 0.95] 0.25
   [0 0.95]   0.25})

(def id-size-bytes 20)


(defn save-sample-data
  [filename data]
  (let [string-ids
        (into #{} (map (fn [[k _]] (str "id:" (.toString k))) data))]
    (spit filename (pr-str string-ids))))

(defn saved-sample-data
  [filename]
  (let [string-ids (edn/read-string (slurp filename))]
    (into {}
          (map (fn [id] [(ID/parseID id id-size-bytes) (str "value for " id)])
               string-ids))))

(emu/quiet-logger)

(defn get-all-similar-for-all-keys
  [overlay m search-threshold]
  (let [all-keys (keys m)]
    (emu/start! overlay)
    (emu/put! overlay m)
    (let [get-similar-results
          (map #(get-all-similar overlay % search-threshold all-keys) all-keys)
          desired-keys [:total-expected :total-found :num-hops :found-per-hop :found-all?
                        :per-hop-percentage :hop-frequencies :by-hops]
          results
          (->> get-similar-results
               (map (fn [result-map]
                      (into {:query-key (keyword (str (:query-key result-map)))}
                            (select-keys result-map desired-keys))))
               (into []))] ; force realization of lazy seq before stopping the overlay)
      (emu/stop! overlay)
      results)))

(defn measure-distribution-across-nodes
  [algorithms n-nodes m search-threshold]
  (into {}
        (for [algorithm (map name algorithms)]
          (let [overlay (emu/make-overlay n-nodes {:algorithm algorithm})]
            [(keyword algorithm) (get-all-similar-for-all-keys overlay m search-threshold)]))))

(defn measure-with-given-distribution
  [algorithms n-nodes n-keys distribution search-threshold]
  (let [m (kv-map-with-frequencies-sample n-keys id-size-bytes distribution)]
    (measure-distribution-across-nodes algorithms n-nodes m search-threshold)))

(defn measure-with-random-distribution
  [algorithms n-nodes n-keys search-threshold]
  (let [m (gen/generate (kv-map-gen n-keys id-size-bytes))]
    (measure-distribution-across-nodes algorithms n-nodes m search-threshold)))


(defn append-val [val & colls]
  (let [maxlen (apply max (map count colls))]
    (map #(concat % (repeat (- maxlen (count %)) val)) colls)))

(defn average-num-hops
  [result-maps]
  (let [hop-counts (map :num-hops result-maps)
        sum (reduce + hop-counts)
        n-results (count hop-counts)]
    (float (/ sum n-results))))

(defn hop-percentage-frequencies
  [result-maps]
  (let [max-hops (apply max (map :num-hops result-maps))]
    (for [hop-num (range max-hops)]
      (freq/bucket-frequencies 0.01
                               (filter (partial not= :no-results)
                                       (map #(get-in % [:per-hop-percentage hop-num] :no-results)
                                            result-maps))))))

;;; everything below is random repl-testing stuff

(comment
  (let [m (kv-map-with-frequencies-sample 100 20 sample-distribution)
        all-keys (keys m)
        overlay (emu/make-overlay 10 {:algorithm "HammingChord"})
        node (first (:nodes overlay))]
    (emu/start! overlay)
    (emu/put! overlay m)
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