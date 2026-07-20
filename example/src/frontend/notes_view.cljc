(ns frontend.notes-view
  "The pure-reads pattern end to end: a component that is a pure
  function of a db anchor. Render it live in the browser (real
  server) or on the JVM (in-process flow, no HTTP, no mocks — see the
  example JVM tests); render it against a fixed as-of value and
  nothing ever updates.

  Everything here is data until mount: the facade returns a lazy
  flow, read at point of use; handlers are fns in props. The
  only platform split is reading a value out of an input event."
  (:require [api.notes :as notes]
            [flowdom.core :refer [for-by]]
            [flowdom.rx :refer [rx ?]]))

(defn- event-value [e]
  #?(:cljs (.. e -target -value)
     ;; JVM tests fire! handlers with the value directly
     :clj  e))

(defn notes-view
  "Pure function of a db anchor: a value on the JVM, a token on the
  client, nil for 'now'."
  [db]
  (let [notes-q (notes/all-notes< db)
        notes<  (rx (? notes-q))
        draft   (atom "")]
    ;; `notes<` is the facade's flow read through ONE rx — rx blocks
    ;; are shared, so the pending probe and the list below are a
    ;; single subscription (one connection in the browser). The query
    ;; is built OUTSIDE the rx body: a re-run must read the same flow,
    ;; not construct a fresh one.
    [:div {:class    "space-y-2"
           :fallback [:p {:class "text-sm text-gray-400"} "connecting…"]}
     ;; the probe: pending until the query's first answer → the
     ;; :fallback above renders; afterwards it renders nothing.
     (rx (? notes<) nil)
     [:ul {:class "notes space-y-1"}
      (for-by identity notes<
              (fn [note] [:li {:class "font-mono text-sm"} (rx (? note))]))]
     [:div {:class "flex gap-2"}
      [:input {:value       (rx (? draft))
               :placeholder "add a note…"
               :on-input    (fn [e] (reset! draft (event-value e)))}]
      [:button {:on-click (fn [_]
                            (notes/add-note! @draft)
                            (reset! draft ""))}
       "Add"]]]))
