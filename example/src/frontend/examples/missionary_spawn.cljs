(ns frontend.examples.missionary-spawn
  (:require [missionary.core :as m]
            [flowdom.rx :refer [rx ? effect]]
            [solidclj.docs.ui :as ui]))

(defonce mounted? (atom true))
(defonce beats (atom 0))

;; the heartbeat is a flow run for effect: each emission is a beat
;; written into state. A flow is a recipe — building it runs nothing.
(def heartbeat
  (m/ap (loop []
          (m/amb (swap! beats inc)
                 (do (m/? (m/sleep 500)) (recur))))))

;; (effect heartbeat) renders nothing; the tree position it occupies
;; is its lifetime — mount starts the loop, unmount cancels it
(defn heart []
  [:p (effect heartbeat) "❤ beating…"])

(defn example []
  [:div {:class "space-y-3"}
   (rx (if (? mounted?)
         [heart]
         [:p {:class "text-gray-400"} "unmounted"]))
   [:p {:class "font-mono"} "beats: " (rx (? beats))]
   [ui/button {:on-click #(swap! mounted? not)} "toggle mount"]])
