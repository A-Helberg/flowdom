(ns frontend.examples.index-list
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce readings (s/atom [12 47 3]))

(defn example []
  [:div {:class "space-y-3"}
   ;; <Index> keys by POSITION: the row's DOM node is reused and only
   ;; its text updates. Note the flipped signature vs :for — here the
   ;; item is the getter and the index is a plain number.
   [:div {:class "font-mono text-sm"}
    [:index {:each (fn [] @readings)}
     (fn [reading i]
       [:div "sensor " i ": " (fn [] (reading))])]]

   [ui/button {:on-click #(swap! readings (fn [rs] (mapv (fn [_] (rand-int 100)) rs)))}
    "New readings"]])
