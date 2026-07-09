(ns server.notes
  "The tx-listener wired end to end: an in-memory Datomic database
  whose live query results stream to browsers over solidrpc.
  Registered in server.core — with the example server running, try it
  from a REPL:

      (require '[server.notes :as notes])
      (notes/add-note! \"from the repl\")   ;; every connected client updates"
  (:require
   [datomic.api :as d]
   [manifold.stream :as s]
   [missionary.core :as m]
   [server.tx-listener :as txl]
   [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; A throwaway in-memory database
;; ---------------------------------------------------------------------------

(defonce conn
  (let [uri "datomic:mem://notes"]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      @(d/transact conn [{:db/ident       :note/text
                          :db/valueType   :db.type/string
                          :db/cardinality :db.cardinality/one}])
      @(d/transact conn [{:note/text "hello from datomic"}])
      conn)))

;; ONE listener per connection, shared. m/stream is lazy and refcounted
;; — the JVM twin of sm/hold's lifecycle: the report queue attaches
;; when the first query subscribes and detaches when the last client
;; disconnects.
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

;; ---------------------------------------------------------------------------
;; missionary → manifold: solidrpc streams manifold sources over SSE
;; ---------------------------------------------------------------------------

(defn- flow->stream
  "Runs a missionary flow into a manifold stream. The blocking put is
  the backpressure; closing the stream (client disconnected) cancels
  the flow, releasing its m/stream subscriptions."
  [flow]
  (let [out    (s/stream 16)
        cancel ((m/reduce (fn [_ v] @(s/put! out v) nil) nil flow)
                (fn [_] (s/close! out))
                (fn [e]
                  ;; a cancelled run fails by design — only log failures
                  ;; that happened while anyone was still listening.
                  (when-not (s/closed? out)
                    (log/error e "flow->stream: flow failed"))
                  (s/close! out)))]
    (s/on-closed out cancel)
    out))

;; ---------------------------------------------------------------------------
;; The rpc surface (whitelisted in server.core)
;; ---------------------------------------------------------------------------

(defn- all-notes [db]
  (->> (d/q '[:find ?e ?text
              :where [?e :note/text ?text]] db)
       (sort-by first)
       (mapv second)))

(defn notes
  "Query: every note, re-emitted (deduped) whenever a transaction
  changes the answer."
  []
  (flow->stream (txl/q-flow all-notes (txl/db-flow conn tx-reports))))

(defn add-note!
  "Command."
  [text]
  (when (seq text)
    @(d/transact conn [{:note/text text}]))
  true)
