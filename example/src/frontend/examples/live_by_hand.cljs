(ns frontend.examples.live-by-hand
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  ;; the composition lives in api.notes — this component holds the
  ;; flow, exactly like the chat demo held its messages
  (:require [api.notes :as notes]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce all-notes (sm/hold (notes/all-notes<) :initial []))

(defonce ^:private n* (atom 0))

(defn example []
  (h [:div {:class "space-y-3"}
      [:div {:class "flex gap-2"}
       [ui/button {:on-click #(notes/add-note! (str "note " (swap! n* inc)))}
        "transact a note"]
       ;; touches no :note/* attribute: the query re-runs, dedupe
       ;; emits nothing — watch for the absence of a flash
       [ui/button {:on-click #(notes/ping!)}
        "irrelevant tx"]]
      [:ul
       [:for {:each all-notes}
        (fn [text _i] [:li {:class "font-mono text-sm"} text])]]]))
