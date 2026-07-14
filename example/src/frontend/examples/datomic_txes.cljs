(ns frontend.examples.datomic-txes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  ;; the feed is a server flow, so it reaches this component the way
  ;; every server flow does: as a query behind an api namespace
  (:require [api.txes :as txes]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce feed (sm/hold (txes/reports) :initial []))

(defonce ^:private n* (atom 0))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click #(txes/add-note! (str "note " (swap! n* inc)))}
        "transact a note"]
       [ui/button {:on-click #(txes/ping!)}
        "transact something else"]]
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
