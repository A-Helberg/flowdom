(ns api.notes
  "Browser twin of the real api/notes.cljc (inlined on the live-queries
  page): the same pure query, facade and commands, running the real
  solidrpc.live combinator against the stand-ins."
  (:require [server.notes :as store]
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
  "Flow of every note, anchored at `db` (nil = no floor; the feed's
  head supplies the present). Hold it at point of use."
  ([] (all-notes< nil))
  ([db] (live/live store/tx-reports< db all-notes
                   :relevant? note-tx?)))

(defn add-note!
  "Command. Resolves to the post-transaction db, so the caller can
  anchor its next read with it."
  [text]
  (js/Promise.resolve (store/add-note! text)))

(defn ping!
  "Command: a write that touches no :note/* attribute."
  []
  (store/ping!))
