(ns hamming-dht.graycode
  (:import [ow.routing.chord GrayCode]))

(defn to-gray
  "Converts n to it's Gray Code representation from binary.
   Returns a clojure BigInt"
  [n]
  (-> (biginteger n) GrayCode/toGray bigint))

(defn from-gray
  "Converts n from Gray Code to binary.
   Returns a clojure BigInt"
  [n]
  (-> (biginteger n) GrayCode/fromGray bigint))

(defn add-gray
  [g1 g2] (bigint (GrayCode/add (biginteger g1) (biginteger g2))))

(defn subtract-gray
  [g1 g2]
  (bigint (GrayCode/subtract (biginteger g1) (biginteger g2))))

