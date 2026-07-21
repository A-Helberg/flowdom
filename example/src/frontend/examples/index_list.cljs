(ns frontend.examples.index-list
  (:require [flowdom.core :refer [for-by]]
            [flowdom.rx :refer [rx ?]]
            [flowdom.docs.ui :as ui]))

(defonce readings (atom [12 47 3]))

;; position-keyed is for-by keyed by index: each row's DOM node is
;; reused and only its reading updates when a new value lands in that
;; slot. Use it when positions are the identity; key by :id when items
;; move around.
(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "font-mono text-sm"}
    (for-by :i (rx (vec (map-indexed (fn [i v] {:i i :v v}) (? readings))))
            (fn [r]
              [:div "sensor " (:i @r) ": " (rx (:v (? r)))]))]

   [ui/button {:on-click #(swap! readings (fn [rs] (mapv (fn [_] (rand-int 100)) rs)))}
    "New readings"]])
