(ns frontend.examples.perf-react
  "The same 50×50 grid rendered by React through flowdom's bridge.
  State lives at the top, so every tick re-renders the whole grid:
  React re-runs createElement for all 2500 dots and re-stamps each one
  (data-tick marks the render pass that produced it — the same thing
  React DevTools' 'highlight updates' visualises)."
  (:require ["react" :refer [createElement]]
            [missionary.core :as m]
            [flowdom.react :as react]
            [flowdom.rx :refer [effect]]))

(def size 50)

(def palette
  ["#3b82f6" "#22c55e" "#eab308" "#ef4444" "#a855f7" "#e5e7eb"])

(defonce state
  (atom {:cells (vec (repeat (* size size) "#e5e7eb"))
         :tick  0}))

(defn- tick!
  "Recolor one random dot; the whole grid re-renders."
  []
  (swap! state
         (fn [{:keys [cells tick]}]
           {:cells (assoc cells (rand-int (count cells)) (rand-nth palette))
            :tick  (inc tick)})))

;; the ticker is a flow run for effect — mounting the grid starts it,
;; unmounting cancels it (see the Effects page)
(def ticker
  (m/ap (loop []
          (m/amb nil
                 (do (m/? (m/sleep 125)) ;; 8 dots/second
                     (tick!)
                     (recur))))))

(defn- Grid
  "A plain React component."
  [^js props]
  (let [cells (.. props -state -cells)
        tick  (.. props -state -tick)]
    (createElement "div"
                   #js {:style #js {:display             "grid"
                                    :gap                 "2px"
                                    :gridTemplateColumns (str "repeat(" size ",10px)")}}
                   (.map cells
                         (fn [color i]
                           (createElement "div"
                                          #js {:key       i
                                               :data-tick tick
                                               :style     #js {:width        "10px"
                                                               :height       "10px"
                                                               :borderRadius "5px"
                                                               :background   color}}))))))

(defn grid []
  [:div
   (effect ticker)
   [react/component Grid {:state state}]])
