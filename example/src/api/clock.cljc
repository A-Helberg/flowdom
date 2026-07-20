(ns api.clock
  (:require
   #?(:cljs [solidrpc.client :as solidrpc])
   #?(:clj [manifold.stream :as s])))

(defn time-flow
  "Emits the current server time every second as a string.
   Server: a Manifold periodic stream.
   Client: a shared SSE-backed flow via client/query."
  []
  #?(:clj  (s/periodically (long 1000)
                           (fn [] (str (java.util.Date.))))
     :cljs (solidrpc/query `time-flow)))

(defn slow-time-flow
  "Like time-flow but waits 3 seconds before emitting the first value."
  []
  #?(:clj  (let [stream (s/stream)]
             (future
               (Thread/sleep 3000)
               (s/connect (s/periodically (long 1000)
                                          (fn [] (str (java.util.Date.))))
                          stream))
             stream)
     :cljs (solidrpc/query `slow-time-flow)))

(defn echo
  "Simple command that echoes its argument back."
  [msg]
  #?(:clj  {:echo msg :at (str (java.util.Date.))}
     :cljs (solidrpc/command `echo msg)))

(defn scoreboard-flow
  "Emits a map of player->score every second, incrementing one random player.
   Demonstrates diff streaming: only the changed entry is sent after the first full value."
  []
  #?(:clj  (let [players ["Alice" "Bob" "Carol" "Dave" "Eve"]
                 scores  (atom (into {} (map #(vector % (rand-int 50)) players)))]
             (s/periodically
              (long 1000)
              (fn []
                (let [p (rand-nth players)]
                  (swap! scores update p + (+ 1 (rand-int 15)))
                  @scores))))
     :cljs (solidrpc/query `scoreboard-flow)))
