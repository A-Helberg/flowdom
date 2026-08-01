(ns frontend.examples.rpc-hold
  (:require [api.chat :as chat]
            [flowdom.rx :refer [rx ? hold]]
            [frontend.fake-rpc :as rpc]
            [flowdom.docs.ui :as ui]))

;; A query flow is COLD: every ? cell that reads it is its own
;; connection, so two direct readers are two open streams of the same
;; answer. `hold` shares — however many readers ? the hold, there is
;; one subscription underneath, and the last reader leaving closes it.
;; The counter below is real: the fake backend counts running query
;; flows the way a server counts open SSE streams.
(def messages  (chat/messages))       ;; cold — one connection PER reader
(def messages* (hold messages))       ;; shared — one connection total

(defn- panel [src]
  [:ul {:class "border border-gray-200 rounded p-2 space-y-1"}
   (rx (for [msg (? src)]
         [:li {:class "font-mono text-sm"} msg]))])

(defn example []
  (let [mode (atom :shared)]
    [:div {:class    "space-y-3"
           :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
     [:div {:class "flex gap-2 items-baseline"}
      [ui/button {:on-click #(reset! mode :direct)} "two direct readers"]
      [ui/button {:on-click #(reset! mode :shared)} "two readers via hold"]
      [:span {:class "text-sm"}
       "open connections: "
       [:strong (rx (? rpc/open-connections))]]]
     ;; both panels read the SAME value either way — what changes is
     ;; how many connections carry it
     (rx (let [src (case (? mode) :direct messages :shared messages*)]
           [:div {:class "grid grid-cols-2 gap-2"}
            [panel src]
            [panel src]]))]))
