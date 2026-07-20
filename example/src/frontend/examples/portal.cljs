(ns frontend.examples.portal
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce open? (atom false))

(defn example []
  [:div {:class "space-y-3"}
   [ui/button {:on-click #(swap! open? not)} "Toggle toast"]
   (rx (when (? open?)
         ;; children render into document.body, escaping this panel
         ;; entirely — but they unmount with this rx like anything else
         [:portal {:mount js/document.body}
          [:div {:class "fixed bottom-4 right-4 rounded-lg bg-gray-900 text-white px-4 py-2 shadow-lg"}
           "I live directly under <body>."]]))])
