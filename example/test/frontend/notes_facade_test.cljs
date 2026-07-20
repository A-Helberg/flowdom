(ns frontend.notes-facade-test
  "The CLJS half of the api.notes facade. Construction is pure — the
  facade returns a FLOW (a recipe) and building it runs nothing; no
  EventSource, no network. In cljs builds api.notes resolves to its
  browser twin (api/notes.cljs) — the real solidrpc.live combinator
  over the fake datomic — so this ns can also MOUNT the real
  notes-view against it and watch a write come back through the fake
  tx-report stream; the real :cljs branch (client/query) is one line,
  exercised full-stack. The marker facades below are the real cljc."
  (:require [cljs.test :refer-macros [deftest is]]
            ["happy-dom" :refer [Window]]
            [api.notes :as notes]
            [api.server-info :as info]
            [api.viewer :as viewer]
            [flowdom.dom :as dom]
            [frontend.notes-view :as nv]))

(deftest facade-returns-a-lazy-flow
  ;; happy-dom provides no EventSource — if constructing the flow
  ;; opened a connection, this would throw. Laziness IS the test.
  (is (fn? (notes/all-notes< nil))
      "a flow is a recipe — plain value, nothing running"))

(deftest notes-view-is-pure-data-until-mount
  ;; calling the component builds hiccup + recipes, runs no effects
  (let [hiccup (nv/notes-view nil)]
    (is (vector? hiccup))
    (is (= :div (first hiccup)))))

(deftest marker-token-facades-are-lazy-flows
  ;; markers are generic tokens — plain data, no registration; the
  ;; flows are recipes — no connection until something subscribes
  (is (fn? (viewer/whoami< (viewer/viewer-token))))
  (is (fn? (info/server-info< (info/server-info-token)))))

(deftest notes-view-mounts-live-against-the-stand-ins
  ;; the browser twin runs the REAL live combinator over the fake
  ;; datomic: mounting subscribes (the head report answers
  ;; synchronously), a write lands through the fake tx-report stream,
  ;; and unmounting releases the subscription.
  (let [win  (Window.)
        doc  (.-document win)
        el   (.createElement doc "div")
        note (str "facade-live-" (gensym))]
    (.appendChild (.-body doc) el)
    (let [d (dom/mount [nv/notes-view nil] el)]
      (try
        (is (some? (.querySelector el "input")) "the form is up")
        (notes/add-note! note)
        (is (re-find (re-pattern note) (.-textContent el))
            "the write came back through the tx-report stream")
        (finally (d))))))
