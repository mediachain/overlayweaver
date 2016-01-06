(ns hamming-dht.emu
  (:require
    [hamming-dht.dht :as dht])
  (:import
    (ow.id ID)
    (ow.dht DHT DHT$PutRequest)
    (java.io ByteArrayInputStream)
    (java.util.logging LogManager)))


(defn- quiet-logger
  "Prevent OW logger from dumping tons of output to the REPL console."
  []
  (let [config "java.util.logging.ConsoleHandler.level = WARNING"
        stream (ByteArrayInputStream. (.getBytes config "UTF-8"))]
    (.readConfiguration (LogManager/getLogManager) stream)))

(defn make-overlay
  "
  Creates an emulated overlay network with `count` nodes.
  `dht-opts` are used to configure all nodes.
  "
  [count dht-opts]
  (let [dht-opts (assoc dht-opts :transport "Emulator" :upnp? false)
        nodes (repeatedly count #(dht/dht dht-opts))]
    {:nodes nodes :dht-opts dht-opts}))


(defn start! [overlay]
  (let [nodes (:nodes overlay [])
        first-node-info (dht/dht-info (first nodes))
        contact-host (:self-hostname first-node-info)
        contact-port (:self-port first-node-info)]
    (try
      (doseq [n (rest nodes)]
        (.joinOverlay n contact-host contact-port))
      (catch Exception e (str "Exception starting overlay: " (.getMessage e))))))

(defn stop! [overlay]
  (let [nodes (:nodes overlay [])]
    (doseq [n nodes] (.stop n))))

(defn- put-request
  "
  Accepts a two element [key val] vector.
  Returns a DHT$PutRequest object suitable for passing to DHT.put()
  "
  [[k v-or-vs]]
  (let [vs (if (coll? v-or-vs) (set v-or-vs) #{v-or-vs})
        val-array (into-array vs)]
    (DHT$PutRequest. k val-array)))

(defn put!
  ([overlay m]
   "Puts the map `m` into the DHT, selecting a random node as the initiator of the request.
   The keys of `m` must be overlayweaver `ID` objects.
   Values of `m` may be single values (strings) or a collection of values."
    (let [put-requests (into-array DHT$PutRequest (map put-request m))
          node (rand-nth (:nodes overlay []))]
      (.put node put-requests)))
  ([overlay key val]
   "Puts `key` and `val` onto `overlay`, selecting a random node as the initiator of the request."
   (put! overlay {key val})))

(defn get-similar
  ([overlay key threshold] (get-similar overlay key threshold 0))
  ([overlay key threshold extra-hops]
   (let [node (rand-nth (:nodes overlay))]
     (.getSimilar node key threshold extra-hops))))
