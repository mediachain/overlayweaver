(ns hamming-dht.hamming-dht.core
  (:import
    [ow.dht DHTFactory]))

(def hamming-config
  (doto (DHTFactory/getDefaultConfiguration)
    (.setRoutingAlgorithm "Hamming")))

(def dht (DHTFactory/getDHT hamming-config))
