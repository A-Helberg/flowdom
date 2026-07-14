(ns frontend.notes
  "The notes api namespace — static-site twin of the real
  api/notes.cljc (inlined on the live-queries page), same shape as
  frontend.chat is to a real chat api: the pure query and its
  <-suffixed facade colocated, except the 'server side' here is
  frontend.fake-datomic instead of a Datomic conn.

  In the full-stack app this file does not exist — components require
  api.notes and the :cljs branch queries the real server."
  (:require [frontend.fake-datomic :as fd]
            [solidrpc.live :as live]))

(defn all-notes
  "Pure: every note in `db` — call it with any as-of view."
  [db]
  (into [] (keep :note/text) (vals db)))

(defn- note-tx?
  "Did this transaction touch any attribute all-notes depends on?"
  [{:keys [tx-data]}]
  (boolean (some (fn [[_ a _]] (= :note/text a)) tx-data)))

(defn all-notes<
  "Flow of every note, anchored at `db` (a value here — the fake's dbs
  are plain maps — or nil for 'now'). Hold it at point of use."
  [db]
  (live/live fd/env db all-notes :relevant? note-tx?))

(defn add-note!
  "Command."
  []
  (fd/add-note!))
