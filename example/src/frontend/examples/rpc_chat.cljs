(ns frontend.examples.rpc-chat
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  ;; no rpc in sight: frontend.chat wraps the queries and commands in
  ;; plain functions, so this component doesn't know it's talking to a
  ;; server (or, on this static site, to the fake one).
  (:require [frontend.chat :as chat]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; (chat/messages) returns a missionary flow; hold bridges it. Lazy end
;; to end: the "connection" opens when this page first renders and
;; closes when you navigate away.
(defonce messages (sm/hold (chat/messages) :initial []))

(defn example []
  (h [:div {:class "space-y-3"}
      (if (sm/pending? messages)
        [:p {:class "text-sm text-gray-400"} "connecting…"]
        [:ul {:class "space-y-1"}
         [:for {:each messages}
          (fn [msg _i]
            [:li {:class "font-mono text-sm"} msg])]])
      ;; writes are one-shot commands — an uncontrolled form is plenty
      [:form {:class    "flex gap-2"
              :onSubmit (fn [e]
                          (.preventDefault e)
                          (chat/send! (.get (js/FormData. (.-target e)) "message"))
                          (.reset (.-target e)))}
       [ui/input {:name "message" :placeholder "say something…"}]
       [ui/button {} "Send"]]]))
