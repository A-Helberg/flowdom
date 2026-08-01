(ns frontend.examples.rpc-states
  (:require [api.chat :as chat]
            [flowdom.rx :refer [rx ? hold loading?<]]
            [frontend.fake-rpc :as rpc]
            [flowdom.docs.ui :as ui]))

;; A followed query REFETCHES when its ref changes — but by default
;; the refetch is silent: the flow simply doesn't emit until the new
;; answer arrives, so readers keep the stale one. Often right (chat,
;; feeds); wrong when the old answer looks like the answer to the new
;; question. The `loading-visible` sentinel opts a query in: each
;; (re)connect emits the pending marker first, so readers re-enter
;; loading. `loading?<` reads that state inline, as a flow of
;; booleans, no enclosing :fallback needed. Both panels below follow
;; the same atom; switch rooms and watch them disagree for the ~300ms
;; the reconnect takes.
(defn- panel [note body]
  [:div
   [:p {:class "text-xs text-gray-500 mb-1"} note]
   body])

(defn example []
  (let [room     (atom "general")
        stale    (hold (chat/room-messages room))
        fresh    (hold (rpc/query 'chat/room-messages room rpc/loading-visible))
        loading? (loading?< fresh)]
    [:div {:class    "space-y-3"
           :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
     [:div {:class "flex gap-2"}
      [ui/button {:on-click #(reset! room "general")} "#general"]
      [ui/button {:on-click #(reset! room "random")} "#random"]]
     [:div {:class "grid grid-cols-2 gap-2"}
      [panel "default: stale while refetching"
       [:ul {:class "border border-gray-200 rounded p-2 space-y-1"}
        (rx (for [msg (? stale)]
              [:li {:class "font-mono text-sm"} msg]))]]
      [panel "with loading-visible"
       (rx (if (? loading?)
             [:p {:class "text-sm text-gray-400"} "loading…"]
             [:ul {:class "border border-gray-200 rounded p-2 space-y-1"}
              (for [msg (? fresh)]
                [:li {:class "font-mono text-sm"} msg])]))]]]))
