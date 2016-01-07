(ns dht-test
  (:require
    [hamming-dht.dht :as dht]
    [clojure.test :as test :refer [use-fixtures run-tests]]
    [clojure.test.check.clojure-test :refer [defspec]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop])
  (:import
    [ow.id ID]
    [ow.dht DHT]))


(def dht-opts
  {:algorithm   "Kademlia"
   :id-byte-len 20
   :upnp?       false
   :transport "Emulator"})

(def ^{:private true}
  test-dht (dht/dht dht-opts))

(defn dht-fixture
  "Test fixture that creates a new dht instance, using `dht-opts` above."
  [f]
  (.clearDHTState test-dht)
  (f))

(defn n-bytes-gen
  "Create a generator that returns a byte array of length `n`."
  [n]
  (gen/fmap byte-array (gen/vector gen/byte n)))

(defn id-gen*
  "Create a generator that returns an overlayweaver ID of `byte-length` bytes."
  [byte-length]
  (gen/fmap #(ID/getID % byte-length) (n-bytes-gen byte-length)))

(def id-gen
  "A generator which returns an overlayweaver ID of the length set in the `dht-opts` var."
  (id-gen* (:id-byte-len dht-opts)))

(defn sim->hamming
  "Given a similarity float `sim` in the range 0.0 - 1.0 and the size of the ID space `bit-length`,
  return the Hamming distance that represents that value of similarity."
  [sim bit-length]
  (assert (and (>= sim 0) (<= sim 1)) "similarity must be between 0.0 - 1.0")
  (let [sim (double sim)
        bit-length (double bit-length)]
    (int (- bit-length (* bit-length sim)))))

(defn id-distance
  "Return the Hamming distance between `id-1` and `id-2`"
  [id-1 id-2]
  (let [int1 (.toBigInteger id-1)
        int2 (.toBigInteger id-2)]
    (-> int1 (.xor int2) .bitCount)))

(defn id-with-distance-gen
  "Given an ID `id`, return a generator that will produce IDs with the given Hamming distance `dist`."
  [id dist]
  (gen/fmap
    (fn [bit-positions]
      (let [byte-length (.getSize id)
            ^BigInteger new-id-val
            (reduce
              (fn [n bit] (.flipBit n bit))
              (.toBigInteger id)
              (take dist bit-positions))]
        (ID/getID new-id-val byte-length)))
    (let [bit-length (* 8 (.getSize id))]
      (gen/shuffle (range 0 bit-length)))))


(defn valid-similarity? [n] (and (number? n) (>= n 0) (<= n 1)))

(defn id-with-similarity-gen
  "Given an ID `id` and a float `sim` between 0.0 - 1.0,
  return a generator that will produce IDs that have the given similarity to `id`"
  [id sim]
  (assert (valid-similarity? sim) "similarity must be between 0.0 - 1.0")
  (let [bit-length (* 8 (.getSize id))
        dist (sim->hamming sim bit-length)]
    (id-with-distance-gen id dist)))


(def similarity-gen
  (gen/bind (gen/shuffle (map #(* 0.001 %) (range 1000)))
            gen/elements))

(defn similarity-in-range-gen
  [lower-bound upper-bound]
  (let [r (range (int (* 1000 lower-bound)) (int (* 1000 upper-bound)))]
    (gen/bind (gen/shuffle (map #(* 0.001 %) r))
              gen/elements)))

(defn id-with-similarity-in-range-gen
  [id lower-bound upper-bound]
  (assert (valid-similarity? lower-bound) "lower-bound must be between 0.0 - 1.0")
  (assert (valid-similarity? upper-bound) "upper-bound must be between 0.0 - 1.0")
  (gen/let [sim (similarity-in-range-gen lower-bound upper-bound)]
           (id-with-similarity-gen id sim)))

(defn greater-similarity-gen [sim]
  (gen/such-that #(and (valid-similarity? %) (> % sim)) similarity-gen 1000))
(defn lesser-similarity-gen [sim]
  (gen/such-that #(and (valid-similarity? %) (< % sim)) similarity-gen 1000))

(defn id-above-threshold-gen
  [id threshold]
  (gen/let
    [sim (greater-similarity-gen threshold)]
    (id-with-similarity-gen id sim)))

(defn id-below-threshold-gen
  [id threshold]
  (gen/let
    [sim (lesser-similarity-gen threshold)]
    (id-with-similarity-gen id sim)))


(use-fixtures :each dht-fixture)

(defspec
  get-similar-finds-search-key-if-it-exists
  10
  (prop/for-all
    [threshold similarity-gen
     key id-gen]
    (.put test-dht key (str "val for " key))
    (let [result-keys (-> test-dht
                          (.getSimilar key threshold)
                          keys
                          set)]
      (.clearDHTState test-dht)
      (contains? result-keys key))))

(defspec
  get-similar-finds-keys-in-threshold
  10
  (prop/for-all
    [[threshold key similar-key]
     (gen/let [threshold similarity-gen
               key id-gen
               similar-key (id-above-threshold-gen key threshold)]
              [threshold key similar-key])]
    #_(println "checking for threshold" threshold)
    (.put test-dht key (str "val for " key))
    (.put test-dht similar-key (str "val for " similar-key))
    (let [returned-keys (-> test-dht (.getSimilar key threshold) keys set)]
      (.clearDHTState test-dht)
      (contains? returned-keys similar-key))))

(defspec
  get-similar-doesnt-find-keys-below-threshold
  10
  (prop/for-all
    [[threshold key distant-key]
     (gen/let [threshold (greater-similarity-gen 0.1)
               key id-gen
               distant-key (id-below-threshold-gen key threshold)]
              [threshold key distant-key])]
    (.put test-dht key (str "val for " key))
    (.put test-dht distant-key (str "val for " distant-key))
    (let [returned-keys (-> test-dht (.getSimilar key threshold) keys set)]
      (.clearDHTState test-dht)
      (not (contains? returned-keys distant-key)))))