(ns frontend.fake-rpc
  "A stand-in for flowrpc.client so the tutorial can run as a
  static site — same signatures, no server. An atom plays the
  database; a sleep plays the network.

  Kept from the real thing: query returns a cold missionary flow —
  each consumer is its own 'connection', opened when it runs and
  closed when it cancels — that re-emits when the database changes
  and dedups unchanged results; watchable args are followed via the
  REAL follow-args (a changed ref closes the old 'connection' and
  opens a new one). Command is asynchronous and returns a promise.
  Faked away: the SSE wire format (:full/:patch diffs), reconnection,
  and the server-side registry — the two maps below play that part."
  (:require [missionary.core :as m]
            [flowrpc.stream :as stream]))

(defonce db (atom {:messages ["welcome to the fake backend"]
                   :rooms    {"general" ["welcome to #general"]
                              "random"  ["welcome to #random"]}}))

(def ^:private queries
  {'chat/messages      (fn [db] (:messages db))
   'chat/rooms         (fn [db] (vec (keys (:rooms db))))
   'chat/room-messages (fn [db room] (get-in db [:rooms room]))})

(def ^:private commands
  {'chat/send!    (fn [db text]
                    (cond-> db (seq text) (update :messages conj text)))
   'chat/send-to! (fn [db room text]
                    (cond-> db (seq text) (update-in [:rooms room] conj text)))})

(def unresolved
  "Re-export of flowrpc's sentinel (the real thing, like follow-args):
  a followed ref holding it keeps the query silent."
  stream/unresolved)

(def loading-visible
  "Re-export of flowrpc's sentinel: include it in a query's args to
  surface refetches as loading — real follow-args, real behavior."
  stream/loading-visible)

(def loading-value
  "Re-export of flowrpc's placeholder wrapper: (loading-value x) in a
  query's args emits [:flowdom.rx/loading x] once, before the
  query's first answer."
  stream/loading-value)

;; How many query flows are running right now — what a server would
;; count as open SSE streams. The hold demo displays it.
(defonce open-connections (atom 0))

(defn- counted
  "Wrap a query flow so its lifetime moves `open-connections` — the
  m/observe bracket increments at spawn and decrements exactly once
  at cancel, like the tx-listener's acquire/release."
  [flow]
  (m/ap (let [_ (m/?> (m/observe (fn [emit!]
                                   (swap! open-connections inc)
                                   (emit! nil)
                                   (fn [] (swap! open-connections dec)))))]
          (m/?> flow))))

(defn- run-query [f args]
  (m/eduction (dedupe)
              (m/ap (m/? (m/sleep 300)) ;; the "connecting…" part
                    (apply f (m/?< (m/watch db)) args))))

(defn query
  "Symbol + args → cold missionary flow of results, like flowrpc's
  query. Watchable args are followed, courtesy of the real
  follow-args."
  [fn-id & args]
  (let [f (queries fn-id)]
    (stream/follow-args (vec args) #(counted (run-query f %)))))

(defn command
  "Symbol + args → promise, like flowrpc's command."
  [fn-id & args]
  (let [f (commands fn-id)]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn [] (swap! db (fn [v] (apply f v args))) (resolve true))
        200)))))
