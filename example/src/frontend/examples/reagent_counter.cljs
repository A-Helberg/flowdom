(ns frontend.examples.reagent-counter
  (:require [reagent.core :as r]
            [flowdom.react.reagent :as react.reagent]
            [flowdom.docs.ui :as ui]))

(defonce step (atom 1))

;; A form-2 Reagent component: its internal r/atom and Reagent's own
;; re-rendering work untouched inside the bridge.
(defn- counter [_props]
  (let [n (r/atom 0)]
    (fn [{:keys [step]}]
      [:div {:style {:font-family "monospace"}}
       [:span "count: " @n " "]
       [:button {:on-click #(swap! n + step)
                 :style {:padding "0 0.5rem" :background "#2563eb"
                         :color "white" :border-radius "0.25rem"}}
        (str "+" step)]])))

;; props cross the bridge like anywhere else in flowdom: atoms and rx
;; values are read, and re-render the root on change
(defn example []
  [:div {:class "space-y-3"}
   [react.reagent/component counter {:step step}]
   [:div {:class "flex gap-2 items-center"}
    [:span {:class "text-sm text-gray-500"} "step (atom prop):"]
    (for [k [1 5 10]]
      ^{:key k}
      [ui/button {:on-click #(reset! step k)} (str k)])]])
