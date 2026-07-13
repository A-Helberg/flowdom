(ns solidclj.runtime
  "The platform seam under solidclj.hiccup and solidclj.satom.

  This namespace defines the runtime primitives the hiccup walker and
  satom bottom out in. On CLJS (this file) it is a thin re-export of
  real solid-js — zero behavior of its own. On the JVM (runtime.clj)
  the same surface is implemented by a simulator: a reactive core plus
  a tree-building `h`, so components render and react on the server
  and can be snapshotted back to plain hiccup for tests / SSR.

  Anything browser-only (renderToString, hydrate, isServer,
  ssrElement, DOM mounting) is deliberately NOT part of this seam —
  it stays behind reader conditionals in solidclj.hiccup."
  (:require ["solid-js"     :as solid]
            ["solid-js/h"   :as h-module]
            ["solid-js/web" :as solid-web]))

;; solid-js/h ships an ESM build (`export { h as default }`) that shadow's
;; browser bundler exposes under `.default`, and a CJS build
;; (`module.exports = h`) that node's require() returns directly — e.g. in
;; the node-test build. Unwrap whichever shape we got.
(def h (or (.-default h-module) h-module))

;; solid-js/h only exports `h` as a default. Fragment is a property on the
;; hyperscript function itself (h.Fragment = ...), not a module-level export.
(def fragment (.-Fragment h))

;; ---- Reactive core ---------------------------------------------------------

(defn create-signal
  "Returns a [getter setter] pair. `equals` is a 2-arg fn used to
  dedupe propagation (defaults to Solid's own referential check when
  omitted). The setter is called with an updater fn — wrap plain
  values as (fn [_] v) so fn values are stored, not invoked."
  ([init]        (solid/createSignal init))
  ([init equals] (solid/createSignal init #js {:equals equals})))

(def get-listener solid/getListener)
(def on-cleanup   solid/onCleanup)
(def get-owner    solid/getOwner)

;; ---- Data boundary -----------------------------------------------------------
;; The walker builds pure CLJ data; these convert at the platform edge.
;; On the JVM these are (mostly) identity — the simulator consumes CLJ
;; data directly.

(defn ->props
  "Final conversion of a normalized CLJ props map into what `h`
  consumes: a JS object (deep, via clj->js — string keys and fn
  values pass through untouched)."
  [m]
  (clj->js m))

(defn lazy-prop!
  "Install `getter` as a lazily-read prop on already-converted props.
  Solid reads <Dynamic>'s component / <Portal>'s mount inside a
  tracking memo, so the value must resolve at read time, not build
  time. `k` is a keyword. Returns the props — callers must use the
  return value (the JVM implementation is pure)."
  [props k getter]
  (js/Object.defineProperty
   props (name k)
   #js {:configurable true :enumerable true :get getter})
  props)

(defn ->children
  "Children-collection boundary: Solid wants a JS array."
  [xs]
  (into-array xs))

(defn ->each
  "The [:for]/[:index] :each boundary. Solid's mapArray iterates with
  arr.length / arr[i], which CLJS persistent collections don't expose,
  so anything not already a JS array is copied. Item identities (the
  refs Solid keys on) are preserved."
  [coll]
  (cond
    (nil? coll)   #js []
    (array? coll) coll
    :else         (to-array coll)))

;; ---- Dev ergonomics ----------------------------------------------------------

(def ^boolean debug? goog.DEBUG)

(defn warn! [msg] (js/console.warn msg))

;; ---- Control-flow components -----------------------------------------------
;; Passed as first-class values to `h` by the hiccup walker.

(def For           solid/For)
(def Index         solid/Index)
(def Show          solid/Show)
(def Switch        solid/Switch)
(def Match         solid/Match)
(def Suspense      solid/Suspense)
(def ErrorBoundary solid/ErrorBoundary)
(def Dynamic       solid-web/Dynamic)
(def Portal        solid-web/Portal)
