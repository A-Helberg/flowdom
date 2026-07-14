(ns frontend.notes
  "The notes api namespace — static-site twin of the real
  api/notes.cljc (inlined on the live-queries page): the pure query
  and its <-suffixed facade colocated, wired the way server.notes
  wires the real one. In the full-stack app this file does not exist —
  components require api.notes."
  (:require [datomic.api :as d]
            [server.tx-listener :as txl]
            [missionary.core :as m]
            [solidrpc.live :as live]))

(defonce conn (d/connect "datomic:mem://notes"))
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

(def env
  {:db      (fn [] (d/db conn))
   :reports tx-reports})

(defn all-notes
  "Pure: every note in `db` — call it with any as-of view."
  [db]
  (into [] (keep :note/text) (vals db)))

(defn- note-tx?
  "Did this transaction touch any attribute all-notes depends on?"
  [{:keys [tx-data]}]
  (boolean (some (fn [[_ a _]] (= :note/text a)) tx-data)))

(defn all-notes<
  "Flow of every note, anchored at `db` (or nil, which this facade
  treats as 'now'). Hold it at point of use."
  [db]
  (live/live env (or db (d/db conn)) all-notes :relevant? note-tx?))

(defn add-note!
  "Command."
  [text]
  (:db-after @(d/transact conn [{:note/text text}])))
