(ns frontend.examples.on-mount
  (:require [solidclj.docs.ui :as ui]))

(defn example []
  ;; the atom holds a DOM node, not app state — nothing here is reactive
  (let [input-el (atom nil)]
    [:div {:class "flex gap-2"}
     [:input {:class       "border border-gray-300 rounded-md px-2 py-1 text-sm w-56 focus:outline-2 focus:outline-blue-500"
              :placeholder "click focus →"
              :on-mount    #(reset! input-el %)}]
     [ui/button {:on-click #(some-> @input-el .focus)} "Focus"]]))
