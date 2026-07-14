(ns frontend.examples.live-by-hand
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [datomic.api :as d]
            [missionary.core :as m]
            [server.tx-listener :as txl]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce conn (d/connect "datomic:mem://notes"))
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

;; the pure part: a function of a database value — testable with any
;; db in hand, no flows, no server
(defn- all-notes [db]
  (into [] (keep :note/text) (vals db)))

;; the live part, composed by hand: the db as of now, then db-after
;; of every report (m/?> forks the block per report)…
(def db< (m/ap (m/amb (d/db conn) (:db-after (m/?> tx-reports)))))

;; …mapped through the pure fn, deduplicated — only CHANGED answers
;; come through: the irrelevant tx re-runs the query and emits nothing
(defonce notes
  (sm/hold (m/eduction (map all-notes) (dedupe) db<)
           :initial []))

(defonce ^:private n* (atom 0))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click #(d/transact conn [{:note/text (str "note " (swap! n* inc))}])}
        "transact a note"]
       [ui/button {:on-click #(d/transact conn [{:db/id 1 :app/pings (swap! n* inc)}])}
        "irrelevant tx"]]
      [:ul
       [:for {:each notes}
        (fn [text _i] [:li {:class "font-mono text-sm"} text])]]]))
