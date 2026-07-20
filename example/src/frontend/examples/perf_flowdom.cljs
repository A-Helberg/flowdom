(ns frontend.examples.perf-flowdom
  "A 50×50 grid where every dot is its own atom. Updating a dot
  touches exactly one DOM node — nothing else re-renders."
  (:require [flowdom.rx :refer [rx ? effect]]
            [missionary.core :as m]))

(def size 50)

(def palette
  ["#3b82f6" "#22c55e" "#eab308" "#ef4444" "#a855f7" "#e5e7eb"])

(defonce cells
  (vec (repeatedly (* size size) #(atom "#e5e7eb"))))

(defn- tick!
  "Recolor one random dot."
  []
  (reset! (nth cells (rand-int (count cells))) (rand-nth palette)))

;; The ticker is a flow — no cleanup hook, because none is needed:
;; (effect ticker) below runs it for as long as the grid is mounted.
(def ticker
  (m/ap (loop []
          (m/amb nil
                 (do (m/? (m/sleep 125)) ;; 8 dots/second
                     (tick!)
                     (recur))))))

(defn grid []
  [:div {:style (str "display:grid;gap:2px;"
                     "grid-template-columns:repeat(" size ",10px)")}
   (effect ticker) ;; drives the ticks; renders nothing
   (map (fn [cell]
          [:div {:style (rx (str "width:10px;height:10px;border-radius:5px;"
                                 "background:" (? cell)))}])
        cells)])
