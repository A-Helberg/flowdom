(ns frontend.examples.missionary-hold
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [missionary.core :as m]
            [solidclj.missionary :as sm]))

;; A flow is a recipe — building it runs nothing. This one emits
;; 0, 1, 2, … once a second, forever.
(def ticks
  (m/ap (loop [i 0]
          (m/amb i (do (m/? (m/sleep 1000)) (recur (inc i)))))))

;; hold is lazy too: the flow starts when this page first derefs it and
;; is cancelled when the last subscriber unmounts. Navigate away and
;; back — it restarts from 0 even though the hold is a defonce.
(defonce seconds (sm/hold ticks :initial 0))

(defn example []
  (h [:p {:class "font-mono"}
      "this page has been on screen for " @seconds " s"]))
