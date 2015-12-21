(ns hamming-dht.shell
  (:gen-class
    :name hamming-dht.shell.HammingShell
    :state state
    :init init
    :implements [ow.tool.emulator.EmulatorControllable])
  (:require [hamming-dht.dht :as dht]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.cli :as cli])
  (:import [java.io PrintStream Writer]
           [hamming-dht.shell HammingShell]
           [ow.tool.util.shellframework Shell ShellServer MessagePrinter]
           [ow.tool.dhtshell.commands StatusCommand InitCommand GetCommand GetSimilarCommand
                                      PutCommand RemoveCommand SetTTLCommand SetSecretCommand
                                      LocaldataCommand HelpCommand QuitCommand HaltCommand
                                      ClearCommand SuspendCommand ResumeCommand]
           [java.net InetAddress]
           [ow.messaging.util MessagingUtility$HostAndPort MessagingUtility]
           [ow.stat StatConfiguration StatFactory]
           [ow.dht DHTFactory]
           [ow.tool.dhtshell Main$ShowPromptPrinter Main$NoCommandPrinter]))


(def commands
  (into-array
    Class
    [StatusCommand InitCommand GetCommand GetSimilarCommand
     PutCommand RemoveCommand SetTTLCommand SetSecretCommand
     LocaldataCommand HelpCommand QuitCommand HaltCommand
     ClearCommand SuspendCommand ResumeCommand]))

(def command-list (ShellServer/createCommandList commands))
(def command-table (ShellServer/createCommandTable command-list))

(defn- default-stat-collector-port []
  (-> (StatFactory/getDefaultConfiguration) .getSelfPort))

(defn- parse-addr [addr-string default-port]
  (let [host-and-port (MessagingUtility/parseHostnameAndPort addr-string default-port)]
    {:host (.getHostName host-and-port)
     :port (.getPort host-and-port)}))

(def cli-opts
  [["-p" "--port PORT" "port number for shell command interface"
    :id :shell-port
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 65536) "Must be a number between 0 and 65536"]]
   ["-s" "--selfaddress HOST[:PORT]" "self IP address (with optional port)"
    :id :self-address-map
    :default {:host "localhost" :port 3997}
    :default-desc "localhost:3997"
    :parse-fn #(parse-addr % 3997)]
   ["-m" "--statcollector HOST[:PORT]" "address of stat collector"
    :id :stat-collector-address-map
    :parse-fn #(parse-addr % (default-stat-collector-port))]
   ["-d" "--directory DIR" "working directory"
    :id :working-dir
    :default-desc "current directory"
    :default (System/getProperty "user.dir")
    :validate [#(let [f (io/file %)]
                (and (.exists f) (.isDirectory f))) "Directory does not exist."]]
   ["-z" "--id-size BYTES" "size of ID in bytes"
    :id :id-byte-len
    :default 20
    :parse-fn #(Integer/parseInt %)]
   ["-i" "--id" "self ID (as hex string)"
    :id :self-id-str
    :default nil]
   ["-t" "--transport UDP|TCP" "messaging transport, UDP or TCP"
    :default "UDP"
    :parse-fn #(.toUpperCase %)
    :validate [#(contains? #{"TCP" "UDP"} %) "Transport must be one of UDP or TCP"]]
   ["-a" "--algorithm NAME" "routing algorithm"
    :default "HammingKademlia"]
   ["-r" "--routingstyle STYLE" "routing style, Iterative or Recursive"
    :id :routing-style
    :default "Iterative"
    :parse-fn string/capitalize
    :validate [#(contains? #{"Iterative" "Recursive"} %) "Routing style must be Iterative or Recursive"]]
   ["-N" "--no-upnp" "Disable UPnP port mapping"]
   ["-h" "--help"]])

(defn- default-contact-port []
  (-> (DHTFactory/getDefaultConfiguration) .getContactPort))

(defn parse-contact-addr [args]
  (when-let [addr-or-port (first args)]
    (try
      {:port (Integer/parseInt addr-or-port)}
      (catch NumberFormatException _
        (parse-addr addr-or-port (default-contact-port))))))

(defn usage [options-summary]
  (->> ["Runs a DHT shell with the given options, controllable by the overlayweaver emulator."
        ""
        "Usage: hamming-dht-shell [options] [<join-port> | <join-address[:port]>] "
        ""
        "Options:"
        options-summary
        "If join-address is present, the DHT will attempt to"
        "join the overlay by contacting the node at that address."
        ""
        "If only join-port is given, attempts to join the network at localhost"
        "on the given port."]
       (string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

; these only apply to this shell program. all other cli opts are used to
; create the dht instance.
(def ^{:private true} shell-cli-opts
  [:shell-port :stat-collector-address-map])

(defn- format-dht-opts
  [options]
  (let [opts (apply dissoc options shell-cli-opts)]
    (if (:no-upnp opts)
      (assoc opts :upnp? false)
      opts)))

(defn parse-cli
  [args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-opts)
        contact-addr (parse-contact-addr arguments)]
    (cond
      (:help options) (exit 0 (usage summary))
      errors (exit 1 (error-msg errors)))
    {:shell-opts (select-keys options shell-cli-opts)
     :dht-opts (format-dht-opts options)}))

(def prompt-printer
  (proxy [MessagePrinter] []
    (execute [out-stream _hint]
      (.print out-stream (str "ready =>" Shell/CRLF))
      (.flush out-stream))))

(def no-command-printer
  (proxy [MessagePrinter] []
    (execute [out-stream hint]
      (.print out-stream
              (str "No such command"
                   (if hint (str ": " hint) ".")
                   Shell/CRLF))
      (.flush out-stream))))

(defn shell-server [dht-instance shell-port]
  (ShellServer. command-table command-list
                prompt-printer
                no-command-printer
                nil ; empty line printer
                dht-instance
                shell-port
                nil)) ; AccessController (implement if we want ACLs)

(defn dht-shell
  ^Shell
  [args in-stream out-stream interactive?]
  (let [{:keys [dht-opts shell-opts]} (parse-cli args)
        dht-instance (dht/dht dht-opts)
        server (shell-server dht-instance (get shell-opts :shell-port -1))]
    (Shell. in-stream out-stream server dht-instance interactive?)))

(defn -init [] [[] (atom {:shell nil})])
(defn get-state [this key] (@(.state this) key))
(defn set-state! [this key val] (swap! (.state this) into {key val}))

(defn -invoke
  [this args out-stream]
  (set-state! this :shell (dht-shell args nil out-stream false)))

(defn -getControlPipe
  [this]
  (when-let [shell (get-state this :shell)]
    (.getWriter shell)))

(defn start-interactive
  [args]
  (let [shell (dht-shell args System/in System/out true)]
    (when shell
      (.run shell))))

(defn -main
  [& args]
  (start-interactive args))