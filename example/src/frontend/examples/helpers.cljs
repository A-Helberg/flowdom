(ns frontend.examples.helpers
  (:require [flowdom.rx :refer [rx ?]]
            [flowdom.docs.ui :as ui]))

(defonce temp (atom 21))

;; ? works at ANY call depth — helpers are ordinary functions with
;; ordinary signatures. This one subscribes whichever rx called it:
;; tracking rides dynamic scope, not syntax, so no macro rewrites
;; your code and no function needs a special "reactive" shape.
(defn- describe []
  (if (< (? temp) 25) "Comfortable." "Getting warm!"))

(defn example []
  [:div {:class "space-y-3"}
   [:p {:class "font-mono"} "temperature: " (rx (? temp)) "°C"]
   [:p {:class (rx (if (< (? temp) 25) "text-blue-600" "text-red-600"))}
    (rx (describe))]
   [:div {:class "flex gap-2"}
    [ui/button {:on-click #(swap! temp dec)} "−1°"]
    [ui/button {:on-click #(swap! temp inc)} "+1°"]]])
