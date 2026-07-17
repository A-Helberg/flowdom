(ns frontend.examples.for-list
  (:require [solidclj.api :as s]
            [solidclj.docs.ui :as ui]))

(defonce todos   (s/atom [{:id 1 :text "buy milk"}
                          {:id 2 :text "walk dog"}]))
(defonce next-id (s/atom 2))

(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "flex gap-2"}
    [ui/button {:on-click (fn []
                            (let [id (swap! next-id inc)]
                              (swap! todos conj {:id id :text (str "task " id)})))}
     "add"]
    [ui/button {:on-click #(when (seq @todos) (swap! todos pop))}
     "pop"]
    [ui/button {:on-click #(swap! todos (comp vec reverse))}
     "reverse"]]

   ;; <For> keys by item identity; index is a getter, so call it
   [:ul {:class "list-disc pl-5 font-mono text-sm"}
    [:for {:each (fn [] @todos)}
     (fn [item index]
       [:li "#" (fn [] (inc (index))) " — " (:text item)])]]])
