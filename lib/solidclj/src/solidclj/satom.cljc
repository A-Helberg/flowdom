(ns solidclj.satom
  "A Reagent-style reactive atom backed by a SolidJS signal.

  `satom/atom` returns something that IS an atom — `swap!`, `reset!`,
  `add-watch`, validators and `@` all behave exactly like
  `cljs.core/atom`. The difference is what happens when you deref it
  inside a SolidJS tracking scope (a render thunk, `createEffect`,
  `createMemo`, …): the read also subscribes that computation to the
  atom, so the computation re-runs when the atom's value changes.

  That makes the Reagent idiom work directly in hiccup:

      (def temp (s/atom 21))

      [:div
       (fn [] [:p \"it is \" @temp \"°C\"])]   ;; re-runs on swap!/reset!

  Outside a tracking scope, deref is a plain read — no subscription,
  no warning, no owner required.

  Semantics
  =========
  - `swap!`/`reset!` notify watchers unconditionally (normal atom
    behaviour), but reactive propagation is deduplicated with CLJS `=`:
    resetting to an equal value does not re-run Solid computations.
  - An s/atom also satisfies IDeref + IWatchable, so everything that
    works with a plain atom in hiccup (un-deref'd children, prop
    values, `:each`, …) works with an s/atom too.
  - Component bodies are NOT tracking scopes in solidclj (unlike
    Reagent, the component fn runs once). A bare `@a` at the top level
    of a component is a one-shot snapshot; deref inside a `(fn [] …)`
    thunk is live.

  JVM
  ===
  The same type exists on the JVM against solidclj.runtime's reactive
  core (runtime.clj), implementing clojure.lang.IAtom / IDeref / IRef,
  so components using s/atoms compile and run server-side with the
  same tracking-scope subscription semantics. Unlike clojure.core/atom,
  swap! is NOT a CAS loop — it is read-then-reset, mirroring the
  single-threaded CLJS original. Concurrent writers can lose updates;
  s/atoms are UI/test state, not coordination primitives."
  (:refer-clojure :exclude [atom])
  (:require [solidclj.runtime :as rt]))

(defprotocol IReactiveAtom
  "Marker for atoms the hiccup renderer treats as reactive. Plain
  cljs.core/atoms deliberately do NOT satisfy this — the renderer does
  nothing special with them. Implement it (plus IDeref + IWatchable)
  to make a custom reference type live in hiccup.")

#?(:cljs
   (deftype SAtom [^:mutable state ^:mutable meta validator ^:mutable watches
                   signal-get signal-set]
     Object
     (equiv [this other] (-equiv this other))

     IReactiveAtom

     IAtom

     IDeref
     (-deref [_]
       ;; Inside a Solid tracking scope, touch the mirror signal so the
       ;; running computation subscribes. The value itself always comes
       ;; from `state` — signal and state are written together in -reset!.
       (when (some? (rt/get-listener))
         (signal-get))
       state)

     IReset
     (-reset! [a new-value]
       (when-not (nil? validator)
         (when-not (validator new-value)
           (throw (js/Error. "Validator rejected reference state"))))
       (let [old-value state]
         (set! state new-value)
         ;; fn form so values that are themselves functions are stored,
         ;; not invoked as updater fns by Solid's setter.
         (signal-set (fn [_] new-value))
         (when-not (nil? watches)
           (-notify-watches a old-value new-value))
         new-value))

     ISwap
     (-swap! [a f]          (-reset! a (f state)))
     (-swap! [a f x]        (-reset! a (f state x)))
     (-swap! [a f x y]      (-reset! a (f state x y)))
     (-swap! [a f x y more] (-reset! a (apply f state x y more)))

     IWatchable
     (-notify-watches [a old new]
       (doseq [[k f] watches]
         (f k a old new)))
     (-add-watch [a k f]
       (set! watches (assoc watches k f))
       a)
     (-remove-watch [a k]
       (set! watches (dissoc watches k))
       a)

     IMeta
     (-meta [_] meta)

     IEquiv
     (-equiv [o other] (identical? o other))

     IHash
     (-hash [o] (goog/getUid o))

     IPrintWithWriter
     (-pr-writer [a writer opts]
       (-write writer "#<SAtom: ")
       (pr-writer state writer opts)
       (-write writer ">"))))

#?(:clj
   ;; `watches*` is a plain clojure.core/atom holding the watch map —
   ;; deftype mutable fields can't be set! inside `locking` (Clojure
   ;; 1.12 compiles the locking body into an fn), and an atom gives
   ;; add-watch/remove-watch thread safety for free. `state` mirrors
   ;; the CLJS type's mutable field; like the CLJS original, swap! is
   ;; read-then-reset, not a CAS loop (see ns docstring).
   (deftype SAtom [^:volatile-mutable state
                   mta
                   validator
                   watches*
                   signal-get signal-set]
     IReactiveAtom

     clojure.lang.IDeref
     (deref [_]
       ;; Same trick as the CLJS type: touch the mirror signal so a
       ;; running computation subscribes; the value comes from `state`.
       (when (some? (rt/get-listener))
         (signal-get))
       state)

     clojure.lang.IAtom
     (reset [this new-value]
       (when (some? validator)
         (when-not (validator new-value)
           (throw (IllegalStateException. "Validator rejected reference state"))))
       (let [old-value state]
         (set! state new-value)
         ;; fn form so fn values are stored, not invoked as updaters.
         (signal-set (fn [_] new-value))
         (doseq [[k f] @watches*]
           (f k this old-value new-value))
         new-value))
     (swap [this f]          (.reset this (f state)))
     (swap [this f x]        (.reset this (f state x)))
     (swap [this f x y]      (.reset this (f state x y)))
     (swap [this f x y args] (.reset this (apply f state x y args)))
     (compareAndSet [this oldv newv]
       (if (identical? state oldv)
         (do (.reset this newv) true)
         false))

     clojure.lang.IRef
     (setValidator [_ _]
       (throw (UnsupportedOperationException.
               "s/atom validator is fixed at creation")))
     (getValidator [_] validator)
     (getWatches [_] @watches*)
     (addWatch [this k f]
       (swap! watches* assoc k f)
       this)
     (removeWatch [this k]
       (swap! watches* dissoc k)
       this)

     clojure.lang.IMeta
     (meta [_] mta)))

#?(:clj
   (defmethod print-method SAtom [^SAtom a ^java.io.Writer w]
     (.write w "#<SAtom: ")
     (print-method @a w)
     (.write w ">")))

(defn atom
  "Like cljs.core/atom, but deref inside a SolidJS tracking scope
  (render thunk / effect / memo) subscribes that computation to the
  atom. See the namespace docstring for details.

      (def a (satom/atom 0))
      (def b (satom/atom {} :meta m :validator map?))"
  ([x]
   (let [[g s] (rt/create-signal x =)]
     (SAtom. x nil nil #?(:cljs nil :clj (clojure.core/atom {})) g s)))
  ([x & {:keys [meta validator]}]
   (let [[g s] (rt/create-signal x =)]
     (SAtom. x meta validator #?(:cljs nil :clj (clojure.core/atom {})) g s))))
