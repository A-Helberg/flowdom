(ns solidclj.api
  (:refer-clojure :exclude [atom])
  (:require
   [solidclj.hiccup :as hiccup]
   [solidclj.missionary :as sm]
   [solidclj.runtime :as rt]
   [solidclj.satom :as satom]))

(def on-cleanup
  "Register a 0-arg fn to run when the current owner disposes — or,
  inside a reactive thunk, before every re-run (Solid semantics).
  Cross-platform: real solid-js onCleanup on CLJS, the simulator's on
  the JVM."
  rt/on-cleanup)

(def render
  "CLJS: (render hiccup dom-root) → dispose fn.
  JVM:  (render hiccup) → {:tree node :dispose fn} — a LIVE reactive
  tree; see `snapshot` / `with-render`."
  hiccup/render)

#?(:cljs (def render-to-string hiccup/render-to-string))
#?(:cljs (def hydrate hiccup/hydrate-app))

#?(:clj (def snapshot
          "Live tree (or render handle) → plain hiccup, resolved to
  the currently-rendered state. See solidclj.hiccup/snapshot."
          hiccup/snapshot))

#?(:clj (defmacro with-render
          "Render, bind the handle, run body, always dispose:
      (with-render [t [my-comp arg]] … (snapshot t) …)"
          [binding & body]
          `(solidclj.hiccup/with-render ~binding ~@body)))

(def atom
  "Reagent-style reactive atom: a real atom that also subscribes any
  SolidJS tracking scope that derefs it. See solidclj.satom."
  satom/atom)

(defn ?
  "Read a reactive value within the current Solid owner.

  Accepts either:
  - A **reactive atom** (s/atom, or a solidclj.missionary hold/resource)
    — bridges it to a Solid signal and returns the current value
    directly. Equivalent to deref'ing it inside a reactive thunk.
  - A **missionary flow** — materialises it into a Solid signal and
    returns the signal getter fn. Call the result to read the current
    value: ((? flow)). Deduplicated per owner; runs for the owner's
    lifetime. For a shareable, lazily-managed ref use
    solidclj.missionary/hold instead.

  Analogous to m/? in missionary."
  [x]
  (if (satisfies? satom/IReactiveAtom x)
    ((hiccup/atom->signal-getter x))
    (sm/flow-getter x)))
