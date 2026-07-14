(ns frontend.examples.live-notes
  (:require-macros [solidclj.hiccup-macros :refer [h]])
  (:require [frontend.fake-datomic :as fd]
            [frontend.notes :as notes]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            [solidclj.docs.ui :as ui]))

;; Both panels are pure — db in, hiccup out. `live-panel` builds
;; recipes: the facade returns a flow, the hold is lazy, so calling
;; it runs nothing; effects start at mount and end at unmount.
;; `pinned-panel` has nothing reactive at all: applied to an
;; immutable as-of value it is a function call — no flow, no
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
  an immutable as-of view. Nothing here ever updates."
  [db]
  (h [:ul {:class "space-y-1"}
      (for [note (notes/all-notes db)]
        ^{:key note} [:li {:class "font-mono text-sm"} note])]))

;; The demo shell below is the impure edge: buttons transact, and
;; fd/basis-t + fd/as-of stand in for a db value you would otherwise
;; be handed (e.g. by a command response). fd/ping! plays an unrelated
;; writer — filtered by :relevant? before the query even re-runs.

(defn example []
  (let [pinned-t (s/atom nil)]
    (h [:div {:class "space-y-3"}
        [:div {:class "flex gap-2 flex-wrap"}
         [ui/button {:on-click notes/add-note!} "transact a note"]
         [ui/button {:on-click fd/ping!} "irrelevant tx"]
         [ui/button {:on-click #(reset! pinned-t (fd/basis-t))}
          "pin an as-of view"]]
        [:div {:class "grid md:grid-cols-2 gap-4"}
         [:div
          [:p {:class "text-sm font-semibold"} "live — [live-panel nil]"]
          [live-panel nil]]
         [:div
          [:p {:class "text-sm font-semibold"}
           (fn [] (if-let [t @pinned-t]
                    (str "pinned — [pinned-panel (as-of " t ")]")
                    "pinned — click the button"))]
          [:show {:when pinned-t}
           (fn [] [pinned-panel (fd/as-of @pinned-t)])]]]])))
