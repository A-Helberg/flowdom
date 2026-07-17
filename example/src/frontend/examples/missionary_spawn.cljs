(ns frontend.examples.missionary-spawn
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [missionary.core :as m]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

(defonce mounted? (s/atom true))
(defonce beats (s/atom 0))

;; spawn! ties a task to the component's lifetime: mounting starts it,
;; unmounting cancels it — watch the count freeze. Flows become tasks
;; with plain missionary (m/reduce).
(defn heart []
  (sm/spawn!
   (m/reduce (fn [_ _] (swap! beats inc)) nil
             (m/ap (loop []
                     (m/amb nil (do (m/? (m/sleep 500)) (recur)))))))
  [:p "❤ beating…"])

(defn example []
  (h [:div {:class "space-y-3"}
      [:show {:when (fn [] @mounted?) :fallback [:p {:class "text-gray-400"} "unmounted"]}
       [heart]]
      [:p {:class "font-mono"} "beats: " @beats]
      [ui/button {:on-click #(swap! mounted? not)} "toggle mount"]]))
