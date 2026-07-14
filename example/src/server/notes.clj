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
                          :db/cardinality :db.cardinality/one}
                         {:db/ident       :app/pings
                          :db/valueType   :db.type/long
                          :db/cardinality :db.cardinality/one}])
      @(d/transact conn [{:note/text "hello from datomic"}])
      conn)))

;; ONE listener per connection, shared. m/stream is lazy and refcounted
;; — the JVM twin of sm/hold's lifecycle: the report queue attaches
;; when the first query subscribes and detaches when the last client
;; disconnects.
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

(def tx-reports<
  "The feed, consumable: a catch-up head, then every report as it
  lands. The head is the present — an event feed alone only announces
  the next change, so a fresh subscriber would otherwise wait for the
  next write. It reads the db at spawn (a flow is a recipe: every
  subscriber gets its own read), which keeps it fresh by construction
  — never older than any anchor minted on this connection — and it
  carries no datoms: it is a sample, not news."
  (m/ap (m/amb {:db-after (d/db conn) :tx-data []}
               (m/?> tx-reports))))

;; ---------------------------------------------------------------------------
;; db-as-value ↔ wire ref, at the transit boundary
;; ---------------------------------------------------------------------------

(def transit-handlers
  "The db half of the app's value vocabulary, as data — server.core
  merges this into the opts at the rpc mount point. Out: a db value's
  wire form is its basis-t, nothing else crosses. In: the ref becomes
  an actual db value again. No clamping — the trust boundary is the
  query fn (authorize against the present, read domain data at t),
  and data that must not be readable at ANY t is excision's job."
  {:read-handlers  {transit/db-tag (fn [{:keys [basis-t]}]
                                     (let [db (d/db conn)]
                                       (cond-> db basis-t (d/as-of basis-t))))}
   :write-handlers {datomic.db.Db {:tag transit/db-tag
                                   :rep (fn [db] {:basis-t (d/basis-t db)})}}})

;; ---------------------------------------------------------------------------
;; Storage commands
;; ---------------------------------------------------------------------------

(defn add-note!
  "Returns the post-transaction db value — over the wire it leaves as
  a ref (the write handler above), so the client can anchor its next
  read with it: read-your-writes, no cache to patch."
  [text]
  (when (seq text)
    (:db-after @(d/transact conn [{:note/text text}]))))

(defonce ^:private pings (atom 0))

(defn ping!
  "A write that touches no :note/* attribute — lets demos and REPL
  sessions show :relevant? skipping the re-query."
  []
  @(d/transact conn [{:app/pings (swap! pings inc)}])
  nil)
