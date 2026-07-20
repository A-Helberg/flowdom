(ns frontend.examples.form-controlled
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce email (atom ""))

(defn- valid-email? [s]
  (boolean (re-matches #".+@.+\..+" s)))

;; Controlled: the atom is the single source of truth, written on every
;; keystroke — which is what lets the validation line react as you type.
(defn example []
  [:div {:class "space-y-3"}
   [ui/input {:value       email
              :on-input    #(reset! email (.. % -target -value))
              :placeholder "you@example.com"}]
   (rx (if (valid-email? (? email))
         [:p {:class "text-sm text-green-600"} "✓ ready to submit"]
         [:p {:class "text-sm text-gray-400"}
          (count (? email)) " characters — not an email yet"]))])
