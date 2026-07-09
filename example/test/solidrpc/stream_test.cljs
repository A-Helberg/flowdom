(ns solidrpc.stream-test
  "Tests for follow-args, solidrpc's reactive-query-arguments
  combinator. Lives here for now because the solidrpc lib has no cljs
  test build of its own yet."
  (:require [cljs.test :refer-macros [deftest is]]
            [missionary.core :as m]
            [solidclj.api :as s]
            [solidrpc.stream :as stream]))

(defn- forever
  "A flow that emits `v` once, then stays alive until cancelled —
  the shape of an SSE connection."
  [v]
  (m/ap (m/amb v (m/? m/never))))

(defn- consume!
  "Runs `flow`, conj'ing emissions into the returned :seen atom.
  Cancelling a running flow makes it FAIL with Cancelled — normal
  missionary termination, not an error — so failures only flunk the
  test when they arrive before we cancelled."
  [flow]
  (let [seen       (atom [])
        cancelled? (atom false)
        cancel     ((m/reduce (fn [_ v] (swap! seen conj v) nil) nil flow)
                    (fn [_] nil)
                    (fn [e] (when-not @cancelled?
                              (is false (str "flow failed early: " (.-message e))))))]
    {:seen    seen
     :cancel! (fn [] (reset! cancelled? true) (cancel))}))

(deftest plain-args-pass-straight-through
  (let [made (atom [])
        {:keys [seen]} (consume!
                        (stream/follow-args [1 "a"]
                                            (fn [vs] (swap! made conj vs) (m/seed [vs]))))]
    (is (= [[1 "a"]] @made) "make-flow called once with plain args")
    (is (= [[1 "a"]] @seen))))

(deftest watchable-arg-switches-the-flow
  (let [room   (s/atom "general")
        starts (atom [])
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args ["x" room]
                                                    (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= [["x" "general"]] @seen) "initial resolution emitted synchronously")
    (reset! room "random")
    (is (= [["x" "general"] ["x" "random"]] @seen)
        "ref change cancelled the old flow and started a new one")
    (is (= 2 (count @starts)))
    (cancel!)))

(deftest equal-values-do-not-restart
  (let [room   (s/atom "general")
        starts (atom [])
        {:keys [cancel!]} (consume!
                           (stream/follow-args [room]
                                               (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= 1 (count @starts)))
    (reset! room "general") ;; watchers fire (atom semantics), but = dedups
    (is (= 1 (count @starts)) "resetting to an equal value does not reconnect")
    (cancel!)))

(deftest cancelling-the-consumer-stops-the-inner-flow
  (let [room    (s/atom "a")
        stopped (atom 0)
        {:keys [cancel!]} (consume!
                           (stream/follow-args [room]
                                               (fn [_vs]
                                                 (m/observe (fn [emit!]
                                                              (emit! :v)
                                                              (fn [] (swap! stopped inc)))))))]
    (is (= 0 @stopped))
    (reset! room "b")
    (is (= 1 @stopped) "switch tore down the old connection")
    (cancel!)
    (is (= 2 @stopped) "consumer cancellation tore down the current connection")))
