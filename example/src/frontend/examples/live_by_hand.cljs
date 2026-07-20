(ns frontend.examples.live-by-hand
  ;; the composition lives in api.notes — this component reads the
  ;; flow, exactly like the chat demo read its messages
  (:require [api.notes :as notes]
            [flowdom.core :refer [for-by]]
            [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce all-notes (notes/all-notes<))

(defonce ^:private n* (atom 0))

(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "flex gap-2"}
    [ui/button {:on-click #(notes/add-note! (str "note " (swap! n* inc)))}
     "transact a note"]
    ;; touches no :note/* attribute: the query re-runs, dedupe
    ;; emits nothing — watch for the absence of a flash
    [ui/button {:on-click #(notes/ping!)}
     "irrelevant tx"]]
   ;; for-by straight over the query: rows are keyed, so a new answer
   ;; touches only the rows that changed
   [:ul
    (for-by identity all-notes
            (fn [note] [:li {:class "font-mono text-sm"} (rx (? note))]))]])
