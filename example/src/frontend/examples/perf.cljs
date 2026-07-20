(ns frontend.examples.perf
  "Toggle between the flowdom-rendered dot grid and the React
  comparison — the same 2500 dots, fine-grained patches vs whole-root
  re-renders."
  (:require [flowdom.rx :refer [rx ?]]
            [frontend.examples.perf-flowdom :as perf-flowdom]
            [frontend.examples.perf-react :as perf-react]))

(defonce ^:private mode (atom :flowdom))

(defn- tab [m label]
  [:button {:class    (rx (str "px-4 py-1.5 rounded-md text-sm font-medium cursor-pointer "
                               (if (= m (? mode))
                                 "bg-blue-600 text-white"
                                 "bg-gray-100 text-gray-600 hover:bg-gray-200")))
            :on-click #(reset! mode m)}
   label])

(defn demo []
  [:div {:class "space-y-4"}
   [:div {:class "flex gap-2"}
    [tab :flowdom "flowdom"]
    [tab :react "React"]]
   (rx (case (? mode)
         :flowdom [perf-flowdom/grid]
         :react   [perf-react/grid]))])
