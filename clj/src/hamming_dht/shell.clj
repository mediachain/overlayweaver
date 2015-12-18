(ns hamming-dht.shell
  (:gen-class
    :name hamming-dht.shell.HammingShell
    :state state
    :init init
    :implements [ow.tool.emulator.EmulatorControllable])
  (:require [hamming-dht.dht :as dht])
  (:import [java.io PrintStream Writer]
           [hamming-dht.shell HammingShell]
           [ow.tool.util.shellframework Shell ShellServer]
           [ow.tool.dhtshell.commands StatusCommand InitCommand GetCommand GetSimilarCommand
                                      PutCommand RemoveCommand SetTTLCommand SetSecretCommand
                                      LocaldataCommand HelpCommand QuitCommand HaltCommand
                                      ClearCommand SuspendCommand ResumeCommand]))


(def commands
  (into-array
    Class
    [StatusCommand InitCommand GetCommand GetSimilarCommand
     PutCommand RemoveCommand SetTTLCommand SetSecretCommand
     LocaldataCommand HelpCommand QuitCommand HaltCommand
     ClearCommand SuspendCommand ResumeCommand]))

(def command-list (ShellServer/createCommandList commands))
(def command-table (ShellServer/createCommandTable command-list))


(def default-opts
  {:algorithm "HammingChord"
   :web? true})

(defn parse-cmd-line
  [_args] ;TODO
  default-opts)

(defn dht-shell
  ^Shell
  [args in-stream out-stream interactive?]
  (let [opts (parse-cmd-line args)
        dht-instance (apply dht/dht opts)]
    (println "DHT configuration: " (dht/dht-info dht-instance))
    dht-instance))

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

(defn -startInteractive
  [this args]
  (let [shell (dht-shell args System/in System/out true)]
    (when-not (nil? shell)
      (set-state! this :shell shell)
      (.run shell))))

(defn -main
  [& args]
  (.startInteractive (HammingShell.)))