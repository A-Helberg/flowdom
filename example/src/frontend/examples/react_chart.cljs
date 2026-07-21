(ns frontend.examples.react-chart
  (:require ["recharts" :refer [LineChart Line XAxis YAxis Tooltip]]
            [flowdom.react :as react]
            [flowdom.docs.ui :as ui]))

(defonce data
  (atom [{:day "Mon" :uv 400} {:day "Tue" :uv 300}
         {:day "Wed" :uv 200} {:day "Thu" :uv 278}]))

;; children built with react/el are React elements, passed through to
;; React untouched; the :data atom re-renders the chart on change
(defn example []
  [:div {:class "space-y-3"}
   [react/component LineChart
    {:width 420 :height 220 :data data}
    (react/el XAxis   {:dataKey "day"})
    (react/el YAxis   {})
    (react/el Tooltip {})
    (react/el Line    {:type "monotone" :dataKey "uv" :stroke "#2563eb"})]
   [:div {:class "flex gap-2"}
    [ui/button {:on-click #(swap! data conj {:day (str "+" (count @data))
                                             :uv  (+ 100 (rand-int 400))})}
     "Add point"]
    [ui/button {:on-click #(when (> (count @data) 1)
                             (swap! data (comp vec drop-last)))}
     "Remove point"]]])
