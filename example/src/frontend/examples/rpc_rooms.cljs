(ns frontend.examples.rpc-rooms
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [frontend.chat :as chat]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; state, query and command are set up together in the component body
;; (it runs once): the query SUBSCRIBES to `room` — picking a room
;; cancels the old connection and opens one for the new room — while
;; the command CLOSES OVER it, reading it at call time (handlers are
;; untracked): you always send to the room you're looking at.
;; Unmounting the page releases the hold, which closes the connection;
;; come back and it reconnects fresh.
(defn example []
  (let [room     (s/atom "general")
        messages (sm/hold (chat/room-messages room) :initial [])
        send!    (fn [text] (chat/send-to! @room text))]
    (h [:div {:class "space-y-3"}
        [:div {:class "flex gap-2 items-baseline"}
         [ui/button {:on-click #(reset! room "general")} "#general"]
         [ui/button {:on-click #(reset! room "random")} "#random"]
         [:span {:class "text-sm font-semibold"} "in #" @room]]
        ;; the hold keeps showing the old room's messages for the
        ;; ~300ms the switch takes — stale-while-switching.
        (if (sm/pending? messages)
          [:p {:class "text-sm text-gray-400"} "connecting…"]
          [:ul {:class "space-y-1"}
           [:for {:each messages}
            (fn [msg _i] [:li {:class "font-mono text-sm"} msg])]])
        [:form {:class    "flex gap-2"
                :onSubmit (fn [e]
                            (.preventDefault e)
                            (send! (.get (js/FormData. (.-target e)) "message"))
                            (.reset (.-target e)))}
         [ui/input {:name "message" :placeholder "say something…"}]
         [ui/button {} "Send"]]])))
