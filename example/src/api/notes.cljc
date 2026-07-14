(ns api.notes
  "The notes api namespace: the pure query next to its flow facade,
  per the pure-reads conventions.

  - `all-notes` is a pure function of a database VALUE — call it
    with any as-of view: (all-notes (d/as-of db t)) is 'the answer
    at t', no flow required.
  - `all-notes<` lifts it: the `<` says flow. Its db argument is the
    anchor — a real db value on the JVM, an opaque ref on the
    client (solidrpc.transit exchanges the two at the wire), or nil
    for 'now'. Hold it at point of use: (sm/hold (all-notes< db)).
  - The facade is registered under its own symbol — the :cljs branch
    queries `all-notes<`, which the registry resolves to this same
    var, whose :clj branch produces the flow — so the two sides
    cannot drift apart.

  Calling a read runs nothing — flows are recipes; no connection
  opens, no query runs, until something subscribes."
  (:require #?@(:clj  [[datomic.api :as d]
                       [server.notes :as store]
                       [solidrpc.live :as live]]
                :cljs [[solidrpc.call.solidjs :as call]])))

#?(:clj
   (defn all-notes
     "Pure: every note in `db`, oldest first."
     [db]
     (->> (d/q '[:find ?e ?text
                 :where [?e :note/text ?text]] db)
          (sort-by first)
          (mapv second))))

#?(:clj
   (def ^:private note-attrs
     "Attribute idents all-notes reads — the :relevant? whitelist.
  Keep in sync with the query: a missed dependency here is a
  correctness bug (dedupe would still suppress wrong emissions, but
  the query would never re-run)."
     #{:note/text}))

#?(:clj
   (defn- note-tx?
     "Did this transaction touch any attribute all-notes depends on?"
     [{:keys [db-after tx-data]}]
     (boolean (some #(contains? note-attrs (d/ident db-after (:a %))) tx-data))))

(defn all-notes<
  "Flow of every note, anchored at `db` (value / ref / nil). nil
  means 'now' — this facade's convention, applied here; live passes
  the anchor through untouched."
  [db]
  #?(:clj  (live/live store/env (or db ((:db store/env)))
                      all-notes :relevant? note-tx?)
     :cljs (call/query `all-notes< db)))

(defn add-note!
  "Command. Returns true (:clj) / a Promise (:cljs)."
  [text]
  #?(:clj  (store/add-note! text)
     :cljs (call/command `add-note! text)))
