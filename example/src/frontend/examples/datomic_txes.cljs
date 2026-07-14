(ns frontend.examples.datomic-txes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [datomic.api :as d]
            [missionary.core :as m]
            [server.tx-listener :as txl]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce conn (d/connect "datomic:mem://notes"))

;; ONE listener per connection, shared with m/stream
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

;; the raw report feed, accumulated with m/reductions — every
;; transaction, as it lands
(defonce feed
  (sm/hold (m/reductions conj [] tx-reports) :initial []))

(defonce ^:private n* (atom 0))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click #(d/transact conn [{:note/text (str "note " (swap! n* inc))}])}
        "transact a note"]
       [ui/button {:on-click #(d/transact conn [{:db/id 1 :app/pings (swap! n* inc)}])}
        "transact something else"]]
      [:div
       [:p {:class "text-[11px] font-semibold uppercase tracking-wider text-gray-400 mb-1"}
        "tx-report feed — newest first"]
       (if (empty? @feed)
         [:p {:class "text-sm text-gray-400"} "no transactions yet"]
         [:ul
          [:for {:each (fn [] (->> @feed (take-last 3) reverse))}
           (fn [{:keys [db-after tx-data]} _i]
             [:li {:class "font-mono text-xs text-gray-600"}
              "t " (d/basis-t db-after) " · " (pr-str tx-data)])]])]]))
