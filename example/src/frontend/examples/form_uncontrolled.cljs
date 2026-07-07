(ns frontend.examples.form-uncontrolled
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce submitted (s/atom nil))

;; The browser owns the input values — no atom per field, no writes per
;; keystroke. On submit, FormData collects every field by :name, and one
;; line of interop turns it into a map. (Duplicate names keep the last
;; value — reach for (.getAll fd "name") for checkbox groups.)
(defn form->map [form]
  (js->clj (js/Object.fromEntries (js/FormData. form))
           :keywordize-keys true))

(defn example []
  (h [:form {:class    "space-y-3"
             :onSubmit (fn [e]
                         (.preventDefault e)
                         (reset! submitted (form->map (.-target e))))}
      [ui/input {:name "name" :placeholder "Name"}]
      [ui/input {:name "email" :type "email" :placeholder "Email"}]
      [ui/button {} "Submit"]
      ;; nothing above this line flashes while you type
      (when @submitted
        [:p {:class "font-mono text-sm"} "submitted: " (pr-str @submitted)])]))
