(ns graycode-test
  (:require
    [hamming-dht.graycode :as gray]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop])
  (:import [ow.routing.chord GrayCode]))


(def gray-code-bidirectional-prop
  "Assert that converting a number to and then from its Gray code representation
   results in the original number."
  (prop/for-all
    [v (gen/large-integer* {:min 0})]
    (= (biginteger v) (-> (biginteger v) GrayCode/toGray GrayCode/fromGray))))


(def graycode-arithmetic-commutative-prop
  "Assert that g1 == g1 + g2 - g2
   Sadly, because the gray code conversion breaks for negative numbers,
   the commutative property fails: g1 != g1 - g2 + g2"
  (prop/for-all
    [i gen/pos-int j gen/pos-int]
    (let [g1 (gray/from-bin i) g2 (gray/from-bin j)]
      (= g1 (-> g1 (gray/add g2) (gray/subtract g2))))))

(tc/quick-check 100 gray-code-bidirectional-prop)
