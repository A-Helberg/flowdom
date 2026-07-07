(ns frontend.examples.form-controlled
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce email (s/atom ""))

(defn- valid-email? [s]
  (boolean (re-matches #".+@.+\..+" s)))

;; Controlled: the atom is the single source of truth, written on every
;; keystroke — which is what lets the validation line react as you type.
(defn example []
  (h [:div {:class "space-y-3"}
      [ui/input {:value       @email
                 :on-input    #(reset! email (.. % -target -value))
                 :placeholder "you@example.com"}]
      (if (valid-email? @email)
        [:p {:class "text-sm text-green-600"} "✓ ready to submit"]
        [:p {:class "text-sm text-gray-400"}
         (count @email) " characters — not an email yet"])]))
