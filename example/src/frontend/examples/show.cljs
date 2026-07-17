(ns frontend.examples.show
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce online? (s/atom true))

(defn example []
  [:div {:class "space-y-3"}
   [:show {:when     (fn [] @online?)
           :fallback [:p {:class "text-gray-400"} "Offline — reconnect to continue."]}
    [:p {:class "text-green-600"} "Connected."]]
   [ui/button {:on-click #(swap! online? not)} "Toggle connection"]])
