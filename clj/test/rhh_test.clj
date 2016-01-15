(ns rhh-test
  (:require
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]
    [clojure.math.combinatorics :as combo])
  (:import [ow.id.lsh RandomHyperplaneIDGenerator]))

(defn abs [n]
  (if (neg? n) (- n) n))

(defn hamming-distance [i j]
  (let [i (biginteger i)
        j (biginteger j)
        count-i (.bitCount i)
        count-j (.bitCount j)]
    (abs (- count-i count-j))))

(defn compare-hammining-pairs
  "compares two 2-element vectors of numbers, `a` and `b`.
   Returns 1 if `a` has a greater hamming distance between its elements than `b`,
   -1 if `a` has a lower hamming distance, and 0 if the distance is equal."
  [a b]
  (> (hamming-distance (first a) (second a)) (hamming-distance (first b) (second b))))

(defn sort-hamming
  "Takes a vector of numbers and returns a seq of 2-element vectors containing
   elements of the input vector.  The pairs are sorted by hamming-distance
   between the elements in the pair, from lowest to highest."
  [xs]
  (let [pairs (combo/combinations xs 2)]
    (reverse (sort compare-hammining-pairs pairs))))

(defn rhh-id [i]
  (RandomHyperplaneIDGenerator/generateIDByHashingHash (biginteger i)))

(def id-generation-succeeds-prop
  (prop/for-all
    [i gen/large-integer]
    (not (nil? (rhh-id i)))))

(defn check-id-generation-succeeds []
  (tc/quick-check 100 id-generation-succeeds-prop))



(def closer-inputs-have-closer-ids-prop
  (prop/for-all
    [v (gen/vector gen/int)]
    (let [value-pairs (sort-hamming v) ; value pairs sorted by hamming distance
          id-pairs (partition 2 (mapcat rhh-id value-pairs))]
      )))