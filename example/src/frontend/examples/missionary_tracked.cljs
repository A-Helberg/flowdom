(ns frontend.examples.missionary-tracked
  (:require [flowdom.rx :refer [rx ?]]
            [missionary.core :as m]
            [solidclj.docs.ui :as ui]))

(defonce celsius (atom 21))

;; the bridge runs both ways because there is no bridge: an rx IS a
;; missionary flow, so the whole missionary toolbox applies to it —
;; and (m/watch celsius) would give the raw atom as a flow.
(def fahrenheit (rx (+ 32 (* (? celsius) 1.8))))

;; a ref only holds the latest value; m/reductions over the rx keeps a
;; running history of every temperature seen while this page is mounted.
;; The chain is lazy end to end: it spins up when this page renders and
;; tears down when you leave (which is why the history resets).
(def history< (m/reductions conj [] fahrenheit))

(defn example []
  [:div {:class "space-y-3"}
   [:p {:class "font-mono"} (rx (? celsius)) "°C = " (rx (? fahrenheit)) "°F"]
   [:p {:class "font-mono text-gray-500"} "seen: " (rx (pr-str (? history<)))]
   [:div {:class "flex gap-2"}
    [ui/button {:on-click #(swap! celsius dec)} "−1°"]
    [ui/button {:on-click #(swap! celsius inc)} "+1°"]]])
