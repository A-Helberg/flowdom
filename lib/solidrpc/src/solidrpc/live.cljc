(ns solidrpc.live
  "Lift pure query functions into live read-endpoint flows.

  A database is a value. Backend authors write pure functions of one
  — (f db) → shaped, authorized data — and `live` turns such a
  function into a flow that emits the answer for the database you
  hand it, then a fresh answer whenever a transaction changes it.
  Server code passes real db values around (on a Datomic peer they
  are cheap); when a value crosses the wire, solidrpc.transit
  serializes it as a ref (#solid/db {:basis-t t}) and deserializes it
  back into an actual value on the way in — the exchange is invisible
  to application code. Clients hold opaque DbRef tokens and pass them
  like any other value.

  The anchor
  ==========
  `live`'s db argument is the flow's lower bound:

    a db value  — the flow starts from (f value) and immediately
                  catches up to the current db; under latest-wins
                  (m/relieve) a consumer never observes anything
                  older than the anchor, and usually just sees the
                  freshest answer directly.
    nil         — 'now': anchor to the current db. The default for
                  fresh page loads.

  Queries anchored to the same value start from the same point in
  time, and a command response carrying the post-transaction db gives
  read-your-writes by construction. One same-peer assumption to know
  about: the catch-up reads (:db env), which on a single conn is
  always ≥ the anchor; a multi-node deployment that hands out anchors
  across peers should make its resolver sync to the anchor's t
  (d/sync) before yielding the value.

  As-of views
  ===========
  There is no pinned mode — a fixed point in time needs no flow at
  all. An as-of view is an immutable value, so 'the answer at t' is
  plain function application: (f (d/as-of db t)). Render against a
  fixed value and nothing ever updates.

  The env
  =======
  Storage-agnostic — wire your store as two things:

    :db       (fn [] current-db-value)   ;; nil-anchor + catch-up reads
    :reports  flow of tx-reports         ;; maps with :db-after (and
              whatever :relevant? inspects, e.g. :tx-data). Share ONE
              running report flow per connection (m/stream), as
              Datomic's queue semantics require.

  Efficiency levers:
  - `dedupe` is built in: a transaction that doesn't change f's answer
    emits nothing (correctness backstop, saves bandwidth).
  - :relevant? — a predicate on the tx-report, checked before
    re-running f (saves the query itself, e.g. \"did this tx touch a
    :note/* attribute\"). Opt-in: a missed dependency in a relevance
    predicate is a correctness bug, so the default re-runs on every
    report and lets dedupe do the suppressing.
  - Cross-client sharing of identical (endpoint, args) flows is not
    this namespace's job — wrap the result in m/stream yourself when
    fan-out costs show up.

  Consumption: register the flow-returning facade var and
  solidrpc.server streams it over SSE; or hold it directly in-process
  (facade :clj branches, tests)."
  (:require [missionary.core :as m]))

(defn db-flow
  "A continuous flow of database values: the anchor (or (db) when
  nil), a catch-up (db), then :db-after of every report (filtered by
  `relevant?` when given). Latest-wins under load (m/relieve) — a
  live query only ever needs the newest db."
  [{:keys [db reports]} anchor relevant?]
  (m/relieve {}
             (m/ap (m/amb (or anchor (db))
                          (db)
                          (:db-after (m/?> (if relevant?
                                             (m/eduction (filter relevant?) reports)
                                             reports)))))))

(defn live
  "Lift a pure query fn `f` (db → data) into the standard read-endpoint
  flow, anchored at `anchor` (a database value, or nil for the current
  db): starts from the anchor with an immediate catch-up to the
  current db — latest-wins, so consumers see the freshest answer and
  never anything older than the anchor — then (f db-after) per
  relevant report, deduplicated with =.

  For a fixed as-of view, skip the flow: call (f db-value) directly.

  See the namespace docstring for env, anchors and :relevant?."
  [{:keys [db reports] :as env} anchor f & {:keys [relevant?]}]
  (when (or (nil? db) (nil? reports))
    (throw (ex-info "solidrpc.live: env needs :db and :reports"
                    {:env (keys env)})))
  (m/eduction (map f) (dedupe)
              (db-flow env anchor relevant?)))
