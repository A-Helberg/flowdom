(ns server.notes
  "Storage wiring for the notes domain — everything here is about the
  Datomic connection; the domain logic (queries, facades) lives in
  api.notes, colocated with its client side.

  This ns also registers the db-as-value transit handlers: a db value
  crossing the wire serializes as #solid/db {:basis-t t}, and an
  incoming ref deserializes back into an actual db value (as-of), so
  endpoint fns receive databases, never refs. That registration is
  the middleware: resolution happens at the serialization boundary
  and application code never sees it.

  With the example server running, try it from a REPL:

      (require '[api.notes :as notes])
      (notes/add-note! \"from the repl\")   ;; every connected client updates"
  (:require
   [datomic.api :as d]
   [missionary.core :as m]
   [server.tx-listener :as txl]
   [solidrpc.transit :as transit]))

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

(def env
  "The solidrpc.live env for this connection."
  {:db      (fn [] (d/db conn))
   :reports tx-reports})

;; ---------------------------------------------------------------------------
;; db-as-value ↔ wire ref, at the transit boundary
;; ---------------------------------------------------------------------------

(defonce ^:private transit-handlers
  (do
    ;; out: a db value's wire form is its basis-t — nothing else crosses
    (transit/register-write-handler! datomic.db.Db transit/db-tag
                                     (fn [db] {:basis-t (d/basis-t db)}))
    ;; in: the ref becomes an actual db value again. No clamping — the
    ;; trust boundary is the query fn (authorize against the present,
    ;; read domain data at t), and data that must not be readable at
    ;; ANY t is excision's job.
    (transit/register-read-handler! transit/db-tag
                                    (fn [{:keys [basis-t]}]
                                      (let [db (d/db conn)]
                                        (cond-> db basis-t (d/as-of basis-t)))))
    :registered))

;; ---------------------------------------------------------------------------
;; Storage commands
;; ---------------------------------------------------------------------------

(defn add-note!
  [text]
  (when (seq text)
    @(d/transact conn [{:note/text text}]))
  true)
