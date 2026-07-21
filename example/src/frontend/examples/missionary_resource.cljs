(ns frontend.examples.missionary-resource
  (:require [flowdom.rx :refer [rx ?]]
            [missionary.core :as m]
            [flowdom.docs.ui :as ui]))

;; A task is a recipe for one async value; m/ap turns it into a
;; one-emission flow. This one pretends to fetch for 900 ms.
;; Memoized by run number: re-running a recipe = reading a NEW flow,
;; so "reload" is just bumping the run.
(def fetch-time
  (memoize
   (fn [_run]
     (m/ap (m/? (m/sleep 900))
           (.toLocaleTimeString (js/Date.))))))

(defn example []
  (let [run (atom 0)]
    [:div {:class "space-y-3"}
     ;; in flight → pending → the :fallback renders; landed → the value.
     ;; Each reload goes through the fallback again: pending is a state
     ;; of the flow, not a cached loading flag.
     [:div {:fallback [:p {:class "text-gray-400"} "fetching…"]}
      (rx [:p {:class "font-mono"} "server time: " (? (fetch-time (? run)))])]
     [ui/button {:on-click #(swap! run inc)} "reload"]]))
