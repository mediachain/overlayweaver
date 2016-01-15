(ns hamming-dht.dht
  (:import
    [ow.dht DHTFactory DHT DHTConfiguration]
    [ow.routing RoutingAlgorithmProvider RoutingAlgorithmFactory RoutingAlgorithmConfiguration RoutingServiceFactory RoutingService]
    [ow.messaging Signature MessagingFactory MessagingConfiguration]
    [ow.id ID]))

(defn get-working-dir []
  (System/getProperty "user.dir"))

(def defaults
  {:routing-style "Iterative"
   :algorithm     "HammingChord"
   :transport     "UDP"
   :working-dir   (get-working-dir)
   :app-id        1
   :app-version   1
   :id-byte-len   20
   :self-address-map {:host "localhost" :port 3997}
   :self-id       nil})


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

  `algorithm` can be the name of an algorithm (e.g. 'HammingChord')
  or an instance of RoutingAlgorithmProvider.

  `opts` is an optional map of config options:

  key        |   value
  -----------+-------------------------
  :id-bytes  | size of DHT id in bytes
  "
  ^RoutingAlgorithmConfiguration
  [algorithm opts]
  (let [opts (merge defaults opts)
        {:keys [id-byte-len]} opts
        provider (algorithm-provider algorithm)
        config (.getDefaultConfiguration provider)]
   (when (integer? id-byte-len)
     (.setIDSizeInByte config id-byte-len))
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
        {:keys [algorithm transport working-dir self-address-map]} opts
        {self-address :host self-port :port} self-address-map]
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
        id (.getID id-addr)
        addr (.getAddress id-addr)
        hostname (.getHostname addr)
        host (.getHostAddress addr)
        port (.getPort addr)
        cfg-info (dht-config-info (.getConfiguration dht))]
    (merge cfg-info
           {:id            id
            :id-byte-len   (.getSize id)
            :self-address  host
            :self-hostname hostname
            :self-port     port})))

(defn- id-string
  "Ensures that string `s` is prefixed with \"id:\",
  suitable for passing to (ID/parseID str len).
  Returns nil if `s` is not a string."
  [s]
  (cond
    (not (string? s)) nil
    (.startsWith s "id:") s
    :else (str "id:" s)))

(defn opts->self-id
  "If `opts` contains a key :self-id-str, attempts to create
  an overlayweaver ID by parsing it using the :id-byte-len length
  from `opts`.
  If no :self-id-str key is present *and* the length of IDs is
  greater than 20 bytes (size of SHA-1 digest), a random ID is
  generated to avoid an exception when OW tries to assign an
  SHA-1 based id for us.
  If no :self-id-str key is present and IDs are <= 20 bytes in
  length, return nil for default SHA-1 based id."
  [opts]
  (let [{:keys [id-byte-len]} opts]
    (if-let [s (id-string (:self-id-str opts))]
      (ID/parseID s id-byte-len)
      (when (> id-byte-len 20)
        (println "id length > 20, generating random id")
        (ID/getRandomID id-byte-len)))))

(defn dht
  ^DHT
  ([] (dht {}))
  ([opts]
   (let [opts (merge defaults opts)
         self-id (opts->self-id opts)
         opts (assoc opts :self-id self-id)
         dht-cfg (dht-config opts)
         algo-provider (algorithm-provider (:algorithm opts))
         algo-config (algorithm-config algo-provider opts)
         routing-svc (routing-service dht-cfg algo-provider algo-config opts)]
     (.setSelfPort dht-cfg (-> routing-svc .getMessageReceiver .getPort))
     (.initializeAlgorithmInstance algo-provider algo-config routing-svc)

     (let [dht-instance (DHTFactory/getDHT dht-cfg routing-svc)]
       (if-let [stat-collector (:stat-collector-address-map opts)]
         (.setStatCollectorAddress dht-instance (:host stat-collector) (:port stat-collector -1)))
       dht-instance))))

