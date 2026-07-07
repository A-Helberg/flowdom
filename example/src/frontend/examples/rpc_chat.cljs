(ns frontend.examples.rpc-chat
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  ;; against a real backend this require becomes [solidrpc.call.solidjs :as rpc]
  (:require [frontend.fake-rpc :as rpc]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; query → flow → hold. Lazy end to end: the "connection" opens when
;; this page first renders and closes when you navigate away.
(defonce messages (sm/hold (rpc/query 'chat/messages) :initial []))

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
                          (rpc/command 'chat/send!
                                       (.get (js/FormData. (.-target e)) "message"))
                          (.reset (.-target e)))}
       [ui/input {:name "message" :placeholder "say something…"}]
       [ui/button {} "Send"]]]))
