(ns frontend.examples.satom
  (:require [flowdom.rx :refer [rx ?]]
            [flowdom.docs.ui :as ui]))

;; State is a PLAIN Clojure atom — swap!, reset!, add-watch, validators,
;; everything works because it IS cljs.core/atom. (? temp) inside an rx
;; reads it and subscribes the block.
(defonce temp (atom 21))

(defn example []
  [:div {:class "space-y-3"}
   [:p {:class "font-mono"} "temperature: " (rx (? temp)) "°C"]

   (rx (if (< (? temp) 25)
         [:p {:class "text-blue-600"} "Comfortable."]
         [:p {:class "text-red-600"} "Getting warm!"]))

   [:div {:class "flex gap-2"}
    [ui/button {:on-click #(swap! temp dec)} "−1°"]
    [ui/button {:on-click #(swap! temp inc)} "+1°"]]])
