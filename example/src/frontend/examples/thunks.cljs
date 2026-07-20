(ns frontend.examples.thunks
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defn example []
  (let [n (atom 0)]
    [:div {:class "space-y-3"}
     ;; an rx in a child slot is live — this one updates a single
     ;; text node in place
     [:p {:class "font-mono"} "count: " (rx (? n))]

     ;; a structural rx swaps its subtree when its value changes —
     ;; here a fresh <p> per parity flip
     (rx (if (even? (? n))
           [:p {:class "text-green-600"} "n is even"]
           [:p {:class "text-orange-600"} "n is odd"]))

     [ui/button {:on-click #(swap! n inc)} "Increment"]]))
