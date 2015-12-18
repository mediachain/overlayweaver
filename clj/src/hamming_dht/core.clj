(ns hamming-dht.core
  (:import
    [ow.dht DHTFactory DHT DHTConfiguration]
    [ow.routing RoutingAlgorithmProvider RoutingAlgorithmFactory RoutingAlgorithmConfiguration RoutingServiceFactory RoutingService]
    [ow.messaging Signature MessagingFactory MessagingConfiguration]))

(defn get-working-dir []
  (System/getProperty "user.dir"))

(def defaults
  {:routing-style "Iterative"
   :algorithm "HammingChord"
   :transport "UDP"
   :working-dir (get-working-dir)
   :app-id 1
   :app-version 1
   :id-bytes 20
   :self-port 3997
   :self-id nil})


(defn algorithm-provider ^RoutingAlgorithmProvider
  [name-or-provider]
  (cond
    (instance? RoutingAlgorithmProvider name-or-provider) name-or-provider
    (string? name-or-provider) (RoutingAlgorithmFactory/getProvider name-or-provider)
    :else
    (throw (IllegalArgumentException.
             (str "provider must be name of algorithm "
                  "or instance of RoutingAlgorithmProvider")))))

(defn algorithm-config
  "
  Returns a RoutingAlgorithmConfiguration object for the given routing
  algorithm.

  `algorithm` can be the name of an algorithm (e.g. 'HammingKademlia')
  or an instance of RoutingAlgorithmProvider.

  `opts` is an optional map of config options:

  key        |   value
  -----------+-------------------------
  :id-bytes  | size of DHT id in bytes
  "
  ^RoutingAlgorithmConfiguration
  [algorithm opts]
  (let [opts (merge defaults opts)
        {:keys [id-bytes]} opts
        provider (algorithm-provider algorithm)
        config (.getDefaultConfiguration provider)]
   (when (integer? id-bytes)
     (.setIDSizeInByte config id-bytes))
   config))


(defn messaging-provider
  "
  Accepts a DHTConfiguration and the following options:
  :app-id      | A string identifier for the app
  :app-version | An integer app version
  :upnp?       | If true, attempt to use UPnP to traverse NAT

  Returns a map containing two keys:

  :msg-provider  |  A MessagingProvider
  :msg-config    |  A MessagingConfiguration

  These are used to create a RoutingService.
  "
  [^DHTConfiguration dht-config opts]
  (let [opts (merge defaults opts)
        {:keys [app-id app-version upnp?] :or {upnp? true}} opts
        ^bytes sig
        (Signature/getSignature
          (RoutingServiceFactory/getRoutingStyleID  (.getRoutingStyle dht-config))
          (RoutingAlgorithmFactory/getAlgorithmID (.getRoutingAlgorithm dht-config))
          app-id
          app-version)
        provider (MessagingFactory/getProvider
                   (.getMessagingTransport dht-config)
                   sig)
        self-address (.getSelfAddress dht-config)
        config (doto (.getDefaultConfiguration provider)
                 (.setDoUPnPNATTraversal upnp?))]
    (if-not (nil? self-address)
      (.setSelfAddress provider self-address))
    {:msg-provider provider
     :msg-config config}))


(defn routing-service
  ^RoutingService
  [dht-cfg algo-provider algo-config opts]
  (let [opts (merge defaults opts)
        {:keys [self-id]} opts
        {:keys [msg-provider msg-config]} (messaging-provider dht-cfg opts)
        svc-provider (RoutingServiceFactory/getProvider (.getRoutingStyle dht-cfg))]
    (.getService
      svc-provider
      (.getDefaultConfiguration svc-provider)
      msg-provider
      msg-config
      (.getSelfPort dht-cfg)
      (.getSelfPortRange dht-cfg)
      algo-provider
      algo-config
      self-id)))

(defn dht-config
  ^DHTConfiguration
  [opts]
  (let [opts (merge defaults opts)
        {:keys [algorithm transport working-dir self-address self-port]} opts]
    (doto (DHTFactory/getDefaultConfiguration)
      (.setRoutingAlgorithm algorithm)
      (.setMessagingTransport transport)
      (.setWorkingDirectory working-dir)
      (.setSelfPort self-port)
      #(if-not (nil? self-address)
               (.setSelfAddress % self-address)))))

(defn dht-config-info
  [^DHTConfiguration cfg]
  {:algorithm (.getRoutingAlgorithm cfg)
   :transport (.getMessagingTransport cfg)
   :working-dir (.getWorkingDirectory cfg)
   :self-address (.getSelfAddress cfg)
   :self-port (.getSelfPort cfg)})

(defn dht-info
  [^DHT dht]
  (let [id-addr (.getSelfIDAddressPair dht)
        addr (.getAddress id-addr)
        hostname (.getHostname addr)
        host (.getHostAddress addr)
        port (.getPort addr)
        cfg-info (dht-config-info (.getConfiguration dht))]
    (merge cfg-info
           {:id (.getID id-addr)
            :self-address host
            :self-hostname hostname
            :self-port port})))

(defn dht
  ^DHT
  [& opts]
  (let [opts (merge defaults (apply hash-map opts))
        dht-cfg (dht-config opts)
        algo-provider (algorithm-provider (:algorithm opts))
        algo-config (algorithm-config algo-provider opts)
        routing-svc (routing-service dht-cfg algo-provider algo-config opts)]
    (.setSelfPort dht-cfg (-> routing-svc .getMessageReceiver .getPort))
    (.initializeAlgorithmInstance algo-provider algo-config routing-svc)
    (DHTFactory/getDHT dht-cfg routing-svc)))

