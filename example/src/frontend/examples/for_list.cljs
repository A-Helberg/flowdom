(ns frontend.examples.for-list
  (:require [flowdom.core :refer [for-by]]
            [flowdom.rx :refer [rx ?]]
            [flowdom.docs.ui :as ui]))

(defonce todos   (atom [{:id 1 :text "buy milk"}
                        {:id 2 :text "walk dog"}]))
(defonce next-id (atom 2))

(defn example []
  [:div {:class "space-y-3"}
   [:div {:class "flex gap-2"}
    [ui/button {:on-click (fn [_]
                            (let [id (swap! next-id inc)]
                              (swap! todos conj {:id id :text (str "task " id)})))}
     "add"]
    [ui/button {:on-click #(when (seq @todos) (swap! todos pop))}
     "pop"]
    [ui/button {:on-click #(swap! todos (comp vec reverse))}
     "reverse"]]

   ;; for-by keys rows by :id: the body runs ONCE per key and gets an
   ;; atom-like of that item's latest value. Reversing moves DOM nodes;
   ;; watch the flashes — the row text never re-renders.
   [:ul {:class "list-disc pl-5 font-mono text-sm"}
    (for-by :id todos
            (fn [todo]
              [:li "#" (:id @todo) " — " (rx (:text (? todo)))]))]])
