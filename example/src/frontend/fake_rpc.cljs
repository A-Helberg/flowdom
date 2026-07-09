(ns frontend.fake-rpc
  "A stand-in for solidrpc.call.solidjs so the tutorial can run as a
  static site — same signatures, no server. An atom plays the
  database; a sleep plays the network.

  Kept from the real thing: query returns a missionary flow that
  re-emits when the database changes and dedups unchanged results —
  and watchable args are followed via the REAL follow-args (a changed
  ref closes the old 'connection' and opens a new one). Command is
  asynchronous and returns a promise. Faked away: the SSE wire format
  (:full/:patch diffs), reconnection, and the server-side registry —
  the two maps below play that part."
  (:require [missionary.core :as m]
            [solidrpc.stream :as stream]))

(defonce db (atom {:messages ["welcome to the fake backend"]
                   :rooms    {"general" ["welcome to #general"]
                              "random"  ["welcome to #random"]}}))

(def ^:private queries
  {'chat/messages      (fn [db] (:messages db))
   'chat/room-messages (fn [db room] (get-in db [:rooms room]))})

(def ^:private commands
  {'chat/send!    (fn [db text]
                    (cond-> db (seq text) (update :messages conj text)))
   'chat/send-to! (fn [db room text]
                    (cond-> db (seq text) (update-in [:rooms room] conj text)))})

(defn- run-query [f args]
  (m/eduction (dedupe)
              (m/ap (m/? (m/sleep 300)) ;; the "connecting…" part
                    (apply f (m/?< (m/watch db)) args))))

(defn query
  "Symbol + args → missionary flow of results, like solidrpc's query.
  Watchable args are followed, courtesy of the real follow-args."
  [fn-id & args]
  (let [f (queries fn-id)]
    (stream/follow-args (vec args) #(run-query f %))))

(defn command
  "Symbol + args → promise, like solidrpc's command."
  [fn-id & args]
  (let [f (commands fn-id)]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn [] (swap! db (fn [v] (apply f v args))) (resolve true))
        200)))))
