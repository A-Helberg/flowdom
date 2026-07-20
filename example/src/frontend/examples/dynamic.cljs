(ns frontend.examples.dynamic
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defonce tag (atom :h2))

;; tags are data — keep the keyword in an atom and swap it; the
;; element is rebuilt with the same children
(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "flex gap-2"}
    (for [t [:h2 :p :em :code]]
      [ui/button {:on-click #(reset! tag t)} (name t)])]

   (rx [(? tag) "Same content, different element"])])
