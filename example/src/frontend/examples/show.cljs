(ns frontend.examples.show
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce online? (atom true))

;; there is no <Show> component — an rx emitting different hiccup IS
;; the conditional, and switching cancels the old branch's processes
(defn example []
  [:div {:class "space-y-3"}
   (rx (if (? online?)
         [:p {:class "text-green-600"} "Connected."]
         [:p {:class "text-gray-400"} "Offline — reconnect to continue."]))
   [ui/button {:on-click #(swap! online? not)} "Toggle connection"]])
