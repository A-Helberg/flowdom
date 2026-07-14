(ns frontend.examples.live-by-hand
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [frontend.fake-datomic :as fd]
            [missionary.core :as m]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; fd stands in for the conn + report queue, as on the previous page

;; the pure part: a function of a database value — testable with any
;; db in hand, no flows, no server
(defn- all-notes [db]
  (into [] (keep :note/text) (vals db)))

;; the live part, composed by hand: the db as of now, then db-after
;; of every report (m/?> forks the block per report)…
(def db< (m/ap (m/amb (fd/db) (:db-after (m/?> fd/reports)))))

;; …mapped through the pure fn, deduplicated — only CHANGED answers
;; come through: ping! re-runs the query and emits nothing
(defonce notes
  (sm/hold (m/eduction (map all-notes) (dedupe) db<)
           :initial []))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click fd/add-note!} "transact a note"]
       [ui/button {:on-click fd/ping!} "irrelevant tx"]]
      [:ul
       [:for {:each notes}
        (fn [text _i] [:li {:class "font-mono text-sm"} text])]]]))
