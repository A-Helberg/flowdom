(ns frontend.examples.rpc-rooms
  (:require [api.chat :as chat]
            [flowdom.rx :refer [rx ? hold]]
            [frontend.fake-rpc :as rpc]
            [flowdom.docs.ui :as ui]))

;; `room` starts UNRESOLVED — which room isn't known YET (distinct
;; from nil = resolved to nothing). The messages query FOLLOWS the
;; atom, but while it holds the sentinel the query is SILENT: no
;; connection, no answer to a question nobody asked, and the inner
;; fallback renders until you pick. Without the sentinel the query
;; would run at the atom's initial value and end the loading state
;; with the answer to the wrong question.
;;
;; The room list is itself a query, read through a `hold` so the
;; buttons and the header share its one connection. Everything is set
;; up together in the component body (it runs once); the command
;; CLOSES OVER `room`, reading it at call time — and unmounting the
;; page releases every query's last subscriber, which closes the
;; connections; come back and they reconnect fresh.
(defn example []
  (let [room     (atom rpc/unresolved)
        rooms    (hold (chat/rooms))
        messages (chat/room-messages room)
        send!    (fn [text] (when (string? @room) (chat/send-to! @room text)))]
    [:div {:class    "space-y-3"
           :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
     [:div {:class "flex gap-2 items-baseline"}
      ;; picking a room resolves the selection: the query connects for
      ;; that value, and switching rooms reconnects for the new one
      (rx (for [r (? rooms)]
            [ui/button {:on-click #(reset! room r)} (str "#" r)]))
      [:span {:class "text-sm font-semibold"}
       (rx (let [r (? room)]
             (if (string? r) (str "in #" r) "no room picked")))]]
     ;; while a room SWITCH is in flight the rx keeps its last value —
     ;; the old room's messages linger for the ~300ms the reconnect
     ;; takes. Stale-while-switching, for free.
     [:div {:fallback [:p {:class "text-sm text-gray-400"}
                       "no room picked — the messages query hasn't asked anything"]}
      (rx [:ul {:class "space-y-1"}
           (for [msg (? messages)]
             [:li {:class "font-mono text-sm"} msg])])]
     [:form {:class     "flex gap-2"
             :on-submit (fn [e]
                          (.preventDefault e)
                          (send! (.get (js/FormData. (.-target e)) "message"))
                          (.reset (.-target e)))}
      [ui/input {:name "message" :placeholder "say something…"}]
      [ui/button {} "Send"]]]))
