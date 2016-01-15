(ns hamming-dht.graycode
  (:import [ow.routing.chord GrayCode]))

(defn from-bin
  "Converts n to it's Gray Code representation from binary.
   Returns a clojure BigInt"
  [n]
  (-> (biginteger n) GrayCode/toGray bigint))

(defn to-bin
  "Converts n from Gray Code to binary.
   Returns a clojure BigInt"
  [n]
  (-> (biginteger n) GrayCode/fromGray bigint))

(defn add
  [g1 g2] (bigint (GrayCode/add (biginteger g1) (biginteger g2))))

(defn subtract
  [g1 g2]
  (bigint (GrayCode/subtract (biginteger g1) (biginteger g2))))

(defn distance
  [g1 g2]
  (bigint (GrayCode/distance (biginteger g1) (biginteger g2))))
