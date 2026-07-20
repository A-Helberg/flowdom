(ns frontend.examples.react-basic
  (:require ["react" :refer [createElement]]
            [flowdom.react :as react]
            [solidclj.docs.ui :as ui]))

(defonce clicks (atom 0))

;; A plain React function component — stands in for anything from npm.
(defn- Badge [^js props]
  (createElement "span"
                 #js {:className "inline-block rounded-full bg-sky-100 text-sky-700 px-3 py-1 text-sm"}
                 (str "React says: " (.-count props) " clicks")))

;; react/component mounts a real React root inside the flowdom tree.
;; Atom (and rx) props are read — the root re-renders when they change.
(defn example []
  [:div {:class "space-y-3"}
   [react/component Badge {:count clicks}]
   [ui/button {:on-click #(swap! clicks inc)} "Click (from flowdom)"]])
