(ns frontend.fake-rpc
  "A stand-in for solidrpc.call.solidjs so the tutorial can run as a
  static site — same signatures, no server. An atom plays the
  database; a sleep plays the network.

  Kept from the real thing: query returns a missionary flow that
  re-emits when the database changes and dedups unchanged results;
  command is asynchronous and returns a promise. Faked away: the SSE
  wire format (:full/:patch diffs), reconnection, and the server-side
  registry — the two maps below play that part."
  (:require [missionary.core :as m]))

(defonce db (atom {:messages ["welcome to the fake backend"]}))

(def ^:private queries
  {'chat/messages (fn [db] (:messages db))})

(def ^:private commands
  {'chat/send! (fn [db text]
                 (cond-> db (seq text) (update :messages conj text)))})

(defn query
  "Symbol + args → missionary flow of results, like solidrpc's query."
  [fn-id & args]
  (let [f (queries fn-id)]
    (m/eduction (dedupe)
                (m/ap (m/? (m/sleep 300)) ;; the "connecting…" part
                      (apply f (m/?< (m/watch db)) args)))))

(defn command
  "Symbol + args → promise, like solidrpc's command."
  [fn-id & args]
  (let [f (commands fn-id)]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn [] (swap! db (fn [v] (apply f v args))) (resolve true))
        200)))))
