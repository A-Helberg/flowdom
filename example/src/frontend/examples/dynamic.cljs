(ns frontend.examples.dynamic
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce tag (s/atom "h2"))

(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "flex gap-2"}
    (for [t ["h2" "p" "em" "code"]]
      ^{:key t}
      [ui/button {:on-click #(reset! tag t)} t])]

   ;; :component takes a tag string / component fn, or an accessor of one
   [:dynamic {:component (fn [] @tag)}
    "Same content, different element"]])
