(ns frontend.examples.switch
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce status (atom :loading))

;; more than two branches is just… case. Or cond. Control flow is
;; ordinary Clojure returning data — nothing to learn.
(defn example []
  [:div {:class "space-y-3"}
   (rx (case (? status)
         :loading [:p {:class "text-blue-600"} "Loading…"]
         :ready   [:p {:class "text-green-600"} "Ready!"]
         :error   [:p {:class "text-red-600"} "Something failed."]
         [:p {:class "text-gray-400"} "Unknown status."]))

   [:div {:class "flex gap-2"}
    (for [st [:loading :ready :error]]
      [ui/button {:on-click #(reset! status st)} (name st)])]])
