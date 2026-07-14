(ns frontend.examples.missionary-tracked
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [missionary.core :as m]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce celsius (s/atom 21))

;; tracked: a Solid computation as a continuous missionary flow. From
;; there the whole missionary toolbox applies — compose, then hold the
;; result back into the UI.
(defonce fahrenheit
  (sm/hold (m/latest #(+ 32 (* % 1.8))
                     (sm/tracked (fn [] @celsius)))))

;; a ref only holds the latest value; m/reductions keeps a running
;; history of every temperature seen while this page is mounted.
(defonce history
  (sm/hold (m/reductions conj [] (sm/tracked (fn [] @celsius)))
           :initial []))

(defn example []
  (h [:div {:class "space-y-3"}
      [:p {:class "font-mono"} @celsius "°C = " @fahrenheit "°F"]
      [:p {:class "font-mono text-gray-500"} "seen: " (pr-str @history)]
      [:div {:class "flex gap-2"}
       [ui/button {:on-click #(swap! celsius dec)} "−1°"]
       [ui/button {:on-click #(swap! celsius inc)} "+1°"]]]))
