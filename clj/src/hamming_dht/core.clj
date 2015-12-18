(ns hamming-dht.core
  (:import
    [ow.dht DHTFactory DHT]))

(def hamming-config
  (doto (DHTFactory/getDefaultConfiguration)
    (.setRoutingAlgorithm "HammingChord")
    (.setSearchKeysForSimilarity true)))

(def dht (DHTFactory/getDHT hamming-config))
