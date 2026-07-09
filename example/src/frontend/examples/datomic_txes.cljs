(ns frontend.examples.datomic-txes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [frontend.fake-datomic :as fd]
            [missionary.core :as m]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; server.tx-listener/db-flow, transposed: the db as of now, then
;; db-after of every report (m/?> forks the block per report).
(def db< (m/ap (m/amb (fd/db) (:db-after (m/?> fd/reports)))))

;; …/q-flow, same transposition — on the server `qf` would be d/q
;; against a real database value.
(defn- q-flow [qf]
  (m/eduction (map qf) (dedupe) db<))

;; a live query: re-runs on every transaction, but dedupe means only
;; CHANGED answers come through — ping! re-runs it and emits nothing.
(defonce notes
  (sm/hold (q-flow (fn [db] (into [] (keep :note/text) (vals db))))
           :initial []))

;; and the raw report feed, accumulated with m/reductions.
(defonce feed
  (sm/hold (m/reductions conj [] fd/reports) :initial []))

(defn example []
  (h [:div {:class "space-y-4"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click fd/add-note!} "transact a note"]
       [ui/button {:on-click fd/ping!} "transact something else"]]
      [:div
       [:p {:class "text-[11px] font-semibold uppercase tracking-wider text-gray-400 mb-1"}
        "live query — notes"]
       [:ul
        [:for {:each notes}
         (fn [text _i] [:li {:class "font-mono text-sm"} text])]]]
      [:div
       [:p {:class "text-[11px] font-semibold uppercase tracking-wider text-gray-400 mb-1"}
        "tx-report feed — newest first"]
       (if (empty? @feed)
         [:p {:class "text-sm text-gray-400"} "no transactions yet"]
         [:ul
          [:for {:each (fn [] (->> @feed (take-last 3) reverse))}
           (fn [{:keys [t tx-data]} _i]
             [:li {:class "font-mono text-xs text-gray-600"}
              "t " t " · " (pr-str tx-data)])]])]]))
