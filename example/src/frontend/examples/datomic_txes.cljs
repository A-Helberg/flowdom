(ns frontend.examples.datomic-txes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [frontend.fake-datomic :as fd]
            [missionary.core :as m]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; fd stands in for the conn + report queue on this serverless site;
;; fd/reports has the same shape as (m/stream (tx-report-flow conn))

;; the raw report feed, accumulated with m/reductions — every
;; transaction, as it lands
(defonce feed
  (sm/hold (m/reductions conj [] fd/reports) :initial []))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click fd/add-note!} "transact a note"]
       [ui/button {:on-click fd/ping!} "transact something else"]]
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
