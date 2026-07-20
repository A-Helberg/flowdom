(ns frontend.examples.rpc-rooms
  (:require [api.chat :as chat]
            [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

;; state, query and command are set up together in the component body
;; (it runs once): the query FOLLOWS `room` — picking a room closes
;; the old connection and opens one for the new value — while the
;; command CLOSES OVER it, reading it at call time (handlers are plain
;; fns): you always send to the room you're looking at. Unmounting the
;; page releases the query's last subscriber, which closes the
;; connection; come back and it reconnects fresh.
(defn example []
  (let [room     (atom "general")
        messages (chat/room-messages room)
        send!    (fn [text] (chat/send-to! @room text))]
    [:div {:class    "space-y-3"
           :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
     [:div {:class "flex gap-2 items-baseline"}
      [ui/button {:on-click #(reset! room "general")} "#general"]
      [ui/button {:on-click #(reset! room "random")} "#random"]
      [:span {:class "text-sm font-semibold"} "in #" (rx (? room))]]
     ;; while the switch is in flight the rx keeps its last value —
     ;; the old room's messages linger for the ~300ms the reconnect
     ;; takes. Stale-while-switching, for free.
     (rx [:ul {:class "space-y-1"}
          (for [msg (? messages)]
            [:li {:class "font-mono text-sm"} msg])])
     [:form {:class     "flex gap-2"
             :on-submit (fn [e]
                          (.preventDefault e)
                          (send! (.get (js/FormData. (.-target e)) "message"))
                          (.reset (.-target e)))}
      [ui/input {:name "message" :placeholder "say something…"}]
      [ui/button {} "Send"]]]))
