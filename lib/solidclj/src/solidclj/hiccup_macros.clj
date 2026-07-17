(ns solidclj.hiccup-macros)

;;; Compile-time hiccup tree transform ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; The runtime walker (`as-element`) receives *values*, not source forms, so
;;; it cannot auto-wrap `(if ...)`, `(when ...)`, `(let [...] ...)` etc. in
;;; the `(fn [])` thunks that SolidJS needs to track reactive dependencies.
;;;
;;; This namespace provides a single macro `h` that walks the hiccup literal
;;; at compile time and inserts those thunks for you.  Plain `(fn [])` forms
;;; are left alone, so you can mix both styles freely — use `h` for the outer
;;; shape and add explicit thunks where you want fine-grained reactive scopes.
;;;
;;; Rules:
;;; - In child positions of a hiccup vector (after the tag and optional
;;;   props map), any list-form that does NOT start with `fn` or `fn*` is
;;;   wrapped as `(fn [] <form>)`. That includes `@a` — the reader turns
;;;   it into `(deref a)` — so bare derefs of reactive atoms are live.
;;; - In props-map values, `@a` / `(deref a)` forms are wrapped the same
;;;   way ({:value @a} → {:value (fn [] @a)}), except under `:ref` and
;;;   `:on*` keys, whose values are callbacks, not reactive reads. Other
;;;   list-forms in props are left alone (they're usually handlers).
;;; - Nested hiccup vectors are recursed into. Everything else (literals,
;;;   symbols) passes through unchanged. Note `h` sees SOURCE FORMS: a
;;;   symbol holding an atom passes through as a symbol, and the runtime
;;;   walker does not interpret refs — liveness always needs the deref.

(declare transform-vec)

(defn- fn-form? [form]
  (and (seq? form)
       ('#{fn fn*} (first form))))

(defn- deref-form?
  "True for `@x` (which the reader expands to `(clojure.core/deref x)`)
  and hand-written `(deref x)`."
  [form]
  (and (seq? form)
       ('#{deref clojure.core/deref cljs.core/deref} (first form))))

(defn- transform-child [form]
  (cond
    (vector? form)  (transform-vec form)   ;; nested hiccup — recurse
    (fn-form? form) form                   ;; explicit thunk — keep as-is
    (seq? form)     (list 'fn [] form)     ;; any other list — wrap
    :else           form))                 ;; literal / symbol / atom — pass through

(defn- event-or-ref-key?
  "Props whose values are consumed as callbacks, not read reactively —
  wrapping a form under these would turn the value being *returned*
  into the handler itself."
  [k]
  (and (keyword? k)
       (or (= k :ref)
           (.startsWith (name k) "on"))))

(defn- transform-prop-value [k v]
  (if (and (deref-form? v)
           (not (event-or-ref-key? k)))
    (list 'fn [] v)      ;; {:value @a} → {:value (fn [] @a)} — a live accessor
    v))

(defn- transform-props [props]
  (reduce-kv (fn [m k v] (assoc m k (transform-prop-value k v))) {} props))

(defn- transform-vec [[tag & rest]]
  (let [[props children] (if (and (seq rest) (map? (first rest)))
                           [(first rest) (next rest)]
                           [nil rest])]
    (into [tag]
          (concat
           (when props [(transform-props props)])
           (map transform-child children)))))

(defmacro h
  "Walk a hiccup literal at compile time, auto-wrapping list-form
  expressions in child positions as `(fn [])` reactive thunks.

  Returns hiccup data — use it anywhere you'd return a hiccup vector:

      ;; instead of:
      [:div (fn [] (if (signal) [:span (signal)] [:span \"off\"]))]

      ;; write:
      (h [:div (if (signal) [:span (signal)] [:span \"off\"])])

  Plain `(fn [] ...)` forms are left unchanged, so you can mix both
  styles when you need fine-grained reactive scopes:

      (h [:div
          (if (show?)         ;; auto-wrapped — reruns the whole branch
            (fn [] [:p (detail)])  ;; explicit — independent reactive scope
            [:p \"hidden\"])])"
  [form]
  (if (vector? form)
    (transform-vec form)
    form))
