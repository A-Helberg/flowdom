(ns frontend.examples.atom-props
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce text (atom "flowdom"))

;; a prop value can be an rx — only that attribute is written when it
;; changes. ui/input also accepts the atom itself and wraps it for you.
(defn example []
  [:div {:class "space-y-3"}
   [ui/input {:value    text
              :on-input #(reset! text (.. % -target -value))}]
   [:p {:class "font-mono"} "reversed: " (rx (apply str (reverse (? text))))]])
