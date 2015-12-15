(ns graycode-test
  (:require [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [ow.routing.chord GrayCode]))


(def gray-code-bidirectional-prop
  "Assert that converting a number to and then from its Gray code representation
   results in the original number."
  (prop/for-all
    [v (gen/large-integer* {:min 0})]
    (= (biginteger v) (-> (biginteger v) GrayCode/toGray GrayCode/fromGray))))


(tc/quick-check 100 gray-code-bidirectional-prop)
