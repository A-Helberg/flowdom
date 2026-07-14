(ns frontend.notes-view
  "The pure-reads pattern end to end: a component that is a pure
  function of a db anchor. Render it live in the browser (real
  server) or on the JVM (in-process flow, no HTTP, no mocks — see the
  example JVM tests); render it against a fixed as-of value and
  nothing ever updates.

  Everything here is data until mount: the facade returns a lazy
  flow, held here at point of use; handlers are fns in props. The
  only platform split is reading a value out of an input event."
  (:require [api.notes :as notes]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]))

(defn- event-value [e]
  #?(:cljs (.. e -target -value)
     ;; JVM tests fire! handlers with the value directly
     :clj  e))

(defn notes-view
  "Pure function of a db anchor: a value on the JVM, a ref on the
  client, nil for 'now'."
  [db]
  (let [notes< (sm/hold (notes/all-notes< db) :initial [])
        draft  (s/atom "")]
    [:div {:class "space-y-2"}
     [:show {:when     (fn [] (not (sm/pending? notes<)))
             :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
      [:ul {:class "notes space-y-1"}
       [:for {:each notes<}
        (fn [note _] [:li {:class "font-mono text-sm"} note])]]]
     [:div {:class "flex gap-2"}
      [:input {:value       draft
               :placeholder "add a note…"
               :onInput     (fn [e] (reset! draft (event-value e)))}]
      [:button {:onClick (fn [_]
                           (notes/add-note! @draft)
                           (reset! draft ""))}
       "Add"]]]))
