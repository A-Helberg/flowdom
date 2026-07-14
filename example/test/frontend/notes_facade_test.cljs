(ns frontend.notes-facade-test
  "The CLJS half of the api.notes facade: construction is pure — the
  facade returns a FLOW (a recipe), and holding it is still lazy. No
  server, no EventSource, no network in this test; actually
  subscribing needs the real server and is covered by the JVM side
  (in-process) plus manual full-stack runs. Compiling this ns also
  keeps the facade's :cljs branch and the cljc notes-view honest in
  the node-test build."
  (:require [cljs.test :refer-macros [deftest is]]
            [api.notes :as notes]
            [api.viewer :as viewer]
            [frontend.notes-view :as nv]
            [solidclj.satom :as satom]
            [solidclj.missionary :as sm]))

(deftest facade-returns-a-lazy-flow
  ;; happy-dom provides no EventSource — if constructing the flow (or
  ;; holding it) opened a connection, this would throw. Laziness IS
  ;; the test.
  (let [flow (notes/all-notes< nil)]
    (is (fn? flow) "a flow is a recipe — plain value, nothing running")
    (let [r (sm/hold flow :initial [])]
      (is (satisfies? satom/IReactiveAtom r) "held, it crosses the hiccup bridge")
      (is (true? (sm/pending? r)) "untracked read: no subscription, still pending")
      (is (= [] @r) ":initial served before any emission"))))

(deftest notes-view-is-pure-data-until-mount
  ;; calling the component builds hiccup + recipes, runs no effects
  (let [hiccup (nv/notes-view nil)]
    (is (vector? hiccup))
    (is (= :div (first hiccup)))))

(deftest viewer-facade-is-a-lazy-flow-over-a-marker-ref
  ;; the ViewerRef is client-constructable plain data; the flow is a
  ;; recipe — no connection until something subscribes
  (is (fn? (viewer/whoami< (viewer/->ViewerRef)))))
