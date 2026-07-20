(ns frontend.examples.missionary-hold
  (:require [flowdom.rx :refer [rx ?]]
            [missionary.core :as m]))

;; A flow is a recipe — building it runs nothing. This one emits
;; 0, 1, 2, … once a second, forever.
(def ticks
  (m/ap (loop [i 0]
          (m/amb i (do (m/? (m/sleep 1000)) (recur (inc i)))))))

;; No bridge, no adapter: ? reads the flow directly. It starts when
;; this rx mounts and is cancelled when it unmounts. Navigate away and
;; back — the counter restarts from 0 even though the flow is a def.
(defn example []
  [:p {:class "font-mono"}
   "this page has been on screen for " (rx (? ticks)) " s"])
