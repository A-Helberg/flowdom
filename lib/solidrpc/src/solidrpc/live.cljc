(ns solidrpc.live
  "Lift pure query functions into live read-endpoint flows.

  A database is a value. Backend authors write pure functions of one
  — (f db) → shaped, authorized data — and `live` turns such a
  function into a flow that emits the answer for the database you
  hand it, then a fresh answer whenever a transaction changes it.
  Server code passes real db values around (on a Datomic peer they
  are cheap); when a value crosses the wire, solidrpc.transit
  serializes it as a token (#solid/db {:basis-t t}) and deserializes it
  back into an actual value on the way in — the exchange is invisible
  to application code. Clients hold opaque generic tokens and pass them
  like any other value.

  The reports< flow
  =================
  `live`'s store argument is one flow of tx-reports — maps with
  :db-after (and whatever :relevant? inspects, e.g. :tx-data) — with
  one contract on its head: the first report a subscriber receives
  carries the CURRENT db, immediately; the rest follow as they
  happen. The head is what lets a fresh subscriber see the present
  at all — an event feed only announces the next change — so `live`
  exempts it from :relevant?: catch-up is unconditional.

  A flow is a recipe, so the head needs no machinery — one def over
  the shared listener, the db read at every spawn:

      (def tx-reports<
        (m/ap (m/amb {:db-after (d/db conn) :tx-data []}
                     (m/?> tx-reports))))

  Read the db, don't replay the last report: a db read is current as
  of the last commit, so the head is never older than an anchor
  minted on the same connection; a remembered report is only as
  fresh as the last one the (lazy, refcounted) listener processed,
  which can silently break the anchor floor.

  The requirement under all of this is value semantics: reports must
  carry the new database as an immutable value. Any epochal store
  qualifies — an atom, Datomic, XTDB; a store that can't produce its
  state as a value can't play.

  The anchor
  ==========
  `live`'s db argument is the flow's lower bound: the flow starts
  from (f anchor) and immediately catches up to the head report's
  db; under latest-wins (m/relieve) a consumer never observes
  anything older than the anchor, and usually just sees the freshest
  answer directly. nil means no floor — the head already supplies
  the present, so 'anchor at now' needs nothing from the caller. A
  non-nil anchor is passed to f exactly as given.

  Queries anchored to the same value start from the same point in
  time, and a command response carrying the post-transaction db gives
  read-your-writes by construction. One same-peer assumption to know
  about: the head's db read is current for THIS node; a multi-node
  deployment that hands out anchors across peers should make its
  anchor resolver sync to the anchor's t (d/sync) before yielding
  the value.

  As-of views
  ===========
  There is no pinned mode — a fixed point in time needs no flow at
  all. An as-of view is an immutable value, so 'the answer at t' is
  plain function application: (f (d/as-of db t)). Render against a
  fixed value and nothing ever updates.

  Efficiency levers:
  - `dedupe` is built in: a transaction that doesn't change f's answer
    emits nothing (correctness backstop, saves bandwidth).
  - :relevant? — a predicate on the tx-report, checked before
    re-running f (saves the query itself, e.g. \"did this tx touch a
    :note/* attribute\"). Opt-in: a missed dependency in a relevance
    predicate is a correctness bug, so the default re-runs on every
    report and lets dedupe do the suppressing. The head report is
    always exempt.
  - Cross-client sharing of identical (endpoint, args) flows is not
    this namespace's job — wrap the result in m/stream yourself when
    fan-out costs show up.

  Consumption: register the flow-returning facade var and
  solidrpc.server streams it over SSE; or hold it directly in-process
  (facade :clj branches, tests)."
  (:require [missionary.core :as m]))

(defn db-flow
  "A continuous flow of database values: the anchor (when given)
  exactly as given, then :db-after of the head report and of every
  report after it — filtered by `relevant?` when given; the head is
  exempt, catch-up is unconditional. Latest-wins under load
  (m/relieve) — a live query only ever needs the newest db."
  [reports< anchor relevant?]
  (let [reports (if relevant?
                  (m/eduction (map-indexed vector)
                              (filter (fn [[i report]]
                                        (or (zero? i) (relevant? report))))
                              (map second)
                              reports<)
                  reports<)
        dbs     (m/ap (:db-after (m/?> reports)))]
    (m/relieve {} (if (nil? anchor)
                    dbs
                    (m/ap (m/amb anchor (m/?> dbs)))))))

(defn live
  "Lift a pure query fn `f` (db → data) into the standard read-endpoint
  flow: (f anchor) when an anchor is given — the floor; latest-wins
  means consumers never see anything older — then (f db-after) for
  the head report and every relevant one after it, deduplicated
  with =. A nil anchor means no floor: the head is the present.

  For a fixed as-of view, skip the flow: call (f db-value) directly.

  See the namespace docstring for the reports< contract, anchors and
  :relevant?."
  [reports< anchor f & {:keys [relevant?]}]
  (when (nil? reports<)
    (throw (ex-info "solidrpc.live: a reports< flow is required"
                    {:reports< reports<})))
  (m/eduction (map f) (dedupe)
              (db-flow reports< anchor relevant?)))
