(ns frontend.notes-facade-test
  "The CLJS half of the api.notes facade: construction is pure — the
  facade returns a FLOW (a recipe), and holding it is still lazy. No
  server, no EventSource, no network in this test; actually
  subscribing needs the real server and is covered by the JVM side
  (in-process) plus manual full-stack runs. In cljs builds api.notes
  resolves to its browser twin (api/notes.cljs), so that twin — and
  the cljc notes-view on top of it — is what this ns holds to the
  laziness contract; the real :cljs branch (call/query) is one line,
  exercised full-stack. The marker facades below are the real cljc."
  (:require [cljs.test :refer-macros [deftest is]]
            [api.notes :as notes]
            [api.server-info :as info]
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

(deftest marker-ref-facades-are-lazy-flows
  ;; markers are generic refs — plain data, no registration; the
  ;; flows are recipes — no connection until something subscribes
  (is (fn? (viewer/whoami< (viewer/viewer-ref))))
  (is (fn? (info/server-info< (info/server-info-ref)))))
