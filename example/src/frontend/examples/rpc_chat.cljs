(ns frontend.examples.rpc-chat
  ;; no rpc in sight: api.chat wraps the queries and commands in
  ;; plain functions, so this component doesn't know it's talking to a
  ;; server (or, on this static site, to the fake one).
  (:require [api.chat :as chat]
            [flowdom.rx :refer [rx ?]]
            [flowdom.docs.ui :as ui]))

;; (chat/messages) returns a shared missionary flow. Lazy end to end:
;; the connection opens when this page first renders and closes when
;; you navigate away.
(defonce messages (chat/messages))

(defn example []
  [:div {:class    "space-y-3"
         :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
   ;; pending until the first answer arrives → the :fallback renders
   (rx [:ul {:class "space-y-1"}
        (for [msg (? messages)]
          [:li {:class "font-mono text-sm"} msg])])
   ;; writes are one-shot commands — an uncontrolled form is plenty
   [:form {:class     "flex gap-2"
           :on-submit (fn [e]
                        (.preventDefault e)
                        (chat/send! (.get (js/FormData. (.-target e)) "message"))
                        (.reset (.-target e)))}
    [ui/input {:name "message" :placeholder "say something…"}]
    [ui/button {} "Send"]]])
