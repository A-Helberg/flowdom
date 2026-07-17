(ns frontend.examples.portal
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce open? (s/atom false))

(defn example []
  [:div {:class "space-y-3"}
   [ui/button {:on-click #(swap! open? not)} "Toggle toast"]
   [:show {:when (fn [] @open?)}
    ;; children render into document.body, escaping this panel entirely
    [:portal {:mount js/document.body}
     [:div {:class "fixed bottom-4 right-4 rounded-lg bg-gray-900 text-white px-4 py-2 shadow-lg"}
      "I live directly under <body>."]]]])
