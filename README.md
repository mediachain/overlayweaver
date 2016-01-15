This is a fork of the [Overlay Weaver](https://github.com/shudo/overlayweaver) project.

Overlay Weaver is a toolkit for developing and testing routing algorithms for overlay networks
such as distributed hash tables.

The purpose of this fork is to explore and evaluate routing algorithms that support
distributed similiarity search.  Our main focus is currently on the Chord-based
algorithm described in the paper [Hamming DHT: Taming the Similarity Search](http://ce-resd.facom.ufms.br/sbrc/2012/ST4_2.pdf)
by Villaca, et. al.

The implementation lives in [`HammingChord.java`](https://github.com/mine-code/overlayweaver/blob/hamming-dht/src/ow/routing/chord/HammingChord.java),
although several changes have been made to various other classes / interfaces to support the
`getSimilar` command, which should work with any routing algorithm.

Java 8 or later is required to build the project.

Most of the testing and evaluation code is written in clojure, with dependencies managed by
[leiningen](http://leiningen.org/).  

`lein run` will start a DHT "shell" that accepts commands on standard input, and can connect to
an overlay network running either locally or remotely.  
See [shell.clj](https://github.com/mine-code/overlayweaver/blob/hamming-dht/clj/src/hamming_dht/shell.clj) 
for command line options.
 
Much of the clojure code is designed to be used at a REPL, so it's recommended that you set up a
clojure editor with REPL integration.  [Cursive](http://cursive-ide.com) is an excellent (non-free)
choice, as it offers very flexible debugging and editing tools for Java as well as clojure.