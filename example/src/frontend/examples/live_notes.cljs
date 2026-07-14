(ns frontend.examples.live-notes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [api.notes :as notes]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; Both panels are pure — db anchor in, hiccup out. `live-panel`
;; builds recipes: the facade returns a flow, the hold is lazy, so
;; calling it runs nothing; effects start at mount and end at
;; unmount. `pinned-panel` has nothing reactive at all: applied to an
;; immutable db value it is a function call — no flow, no
;; subscription, no lifecycle.

(defn live-panel
  "Pure component: db anchor in (nil = now), hiccup out."
  [db]
  (let [notes< (sm/hold (notes/all-notes< db) :initial [])]
    (h [:ul {:class "space-y-1"}
        [:for {:each notes<}
         (fn [note _] [:li {:class "font-mono text-sm"} note])]])))

(defn pinned-panel
  "Pure component: db value in, hiccup out — a function call against
  an immutable value. Nothing here ever updates."
  [db]
  (h [:ul {:class "space-y-1"}
      (for [note (notes/all-notes db)]
        ^{:key note} [:li {:class "font-mono text-sm"} note])]))

;; The demo shell below is the impure edge: buttons transact, and the
;; pin button keeps the db that add-note! hands back — the database
;; that contains that write, frozen.

(defonce ^:private n* (atom 0))

(defn example []
  (let [pinned-db (s/atom nil)]
    (h [:div {:class "space-y-3"}
        [:div {:class "flex gap-2 flex-wrap"}
         [ui/button {:on-click #(notes/add-note! (str "note " (swap! n* inc)))}
          "transact a note"]
         [ui/button {:on-click #(notes/ping!)}
          "irrelevant tx"]
         [ui/button {:on-click (fn [_]
                                 (-> (notes/add-note! (str "pinned " (swap! n* inc)))
                                     (.then (fn [db] (reset! pinned-db db)))))}
          "add a note, pin its db"]]
        [:div {:class "grid md:grid-cols-2 gap-4"}
         [:div
          [:p {:class "text-sm font-semibold"} "live — [live-panel nil]"]
          [live-panel nil]]
         [:div
          [:p {:class "text-sm font-semibold"}
           (fn [] (if @pinned-db
                    "pinned — [pinned-panel db-of-my-write]"
                    "pinned — click the button"))]
          [:show {:when pinned-db}
           (fn [] [pinned-panel @pinned-db])]]]])))
