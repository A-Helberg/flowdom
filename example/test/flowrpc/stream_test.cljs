(ns flowrpc.stream-test
  "Tests for follow-args, flowrpc's reactive-query-arguments
  combinator. Lives here for now because the flowrpc lib has no cljs
  test build of its own yet."
  (:require [cljs.test :refer-macros [deftest is async]]
            [missionary.core :as m]
            [flowrpc.stream :as stream]))

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
  (let [room   (atom "general")
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
  (let [room   (atom "general")
        starts (atom [])
        {:keys [cancel!]} (consume!
                           (stream/follow-args [room]
                                               (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= 1 (count @starts)))
    (reset! room "general") ;; watchers fire (atom semantics), but = dedups
    (is (= 1 (count @starts)) "resetting to an equal value does not reconnect")
    (cancel!)))

(deftest unresolved-refs-keep-the-query-silent
  (let [sel    (atom stream/unresolved)
        starts (atom [])
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args ["x" sel]
                                                    (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= [] @seen) "nothing emitted while unresolved")
    (is (= [] @starts) "make-flow never called — no connection, no wrong question")
    (reset! sel "id-1")
    (is (= [["x" "id-1"]] @seen) "resolution starts the flow")
    (is (= [["x" "id-1"]] @starts))
    (cancel!)))

(deftest back-to-unresolved-cancels-the-inner-flow
  (let [sel     (atom "a")
        stopped (atom 0)
        {:keys [cancel!]} (consume!
                           (stream/follow-args [sel]
                                               (fn [_vs]
                                                 (m/observe (fn [emit!]
                                                              (emit! :v)
                                                              (fn [] (swap! stopped inc)))))))]
    (is (= 0 @stopped))
    (reset! sel stream/unresolved)
    (is (= 1 @stopped) "flipping back to unresolved tore down the connection")
    (cancel!)))

(deftest cancelling-the-consumer-stops-the-inner-flow
  (let [room    (atom "a")
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

(deftest loading-visible-emits-the-pending-marker-on-each-connect
  (let [room   (atom "general")
        starts (atom [])
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args ["x" room stream/loading-visible]
                                                    (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= [:flowdom.rx/pending ["x" "general"]] @seen)
        "marker precedes the first answer")
    (is (= [["x" "general"]] @starts)
        "the sentinel is filtered — make-flow never sees it")
    (reset! room "random")
    (is (= [:flowdom.rx/pending ["x" "general"] :flowdom.rx/pending ["x" "random"]] @seen)
        "the switch and the marker are the same event")
    (cancel!)))

(deftest loading-visible-keeps-unresolved-silent
  ;; 'not asked yet' is not 'loading' — the sentinel must not defeat
  ;; the no-wrong-question guarantee
  (let [sel (atom stream/unresolved)
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args [sel stream/loading-visible]
                                                    (fn [vs] (forever vs))))]
    (is (= [] @seen) "nothing emitted while unresolved, marker included")
    (reset! sel "id-1")
    (is (= [:flowdom.rx/pending ["id-1"]] @seen))
    (cancel!)))

(deftest loading-visible-without-refs-marks-the-one-connect
  (let [made (atom [])
        {:keys [seen]} (consume!
                        (stream/follow-args [1 stream/loading-visible]
                                            (fn [vs] (swap! made conj vs) (m/seed [vs]))))]
    (is (= [[1]] @made) "sentinel filtered on the plain path too")
    (is (= [:flowdom.rx/pending [1]] @seen)
        "the plain path has one connect — it emits the marker first")))

(deftest loading-value-seeds-the-first-connect-only
  (let [room   (atom "general")
        starts (atom [])
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args [room (stream/loading-value [])]
                                                    (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= [[:flowdom.rx/loading []] ["general"]] @seen)
        "the placeholder precedes the first answer, wrapped — distinguishable")
    (is (= [["general"]] @starts) "the wrapper is filtered — make-flow never sees it")
    (reset! room "random")
    (is (= [[:flowdom.rx/loading []] ["general"] ["random"]] @seen)
        "a refetch does NOT re-emit the placeholder — stale holds")
    (cancel!)))

(deftest loading-value-composes-with-loading-visible
  ;; two different moments: the placeholder precedes the first answer
  ;; ever (preempting the marker), the marker precedes every refetch
  (let [room (atom "general")
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args [room
                                                     stream/loading-visible
                                                     (stream/loading-value :blank)]
                                                    (fn [vs] (forever vs))))]
    (is (= [[:flowdom.rx/loading :blank] ["general"]] @seen)
        "first connect: wrapped placeholder, no marker")
    (reset! room "random")
    (is (= [[:flowdom.rx/loading :blank] ["general"] :flowdom.rx/pending ["random"]] @seen)
        "refetch: marker, no placeholder")
    (cancel!)))

(deftest loading-value-keeps-unresolved-silent
  (let [sel (atom stream/unresolved)
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args [sel (stream/loading-value :empty)]
                                                    (fn [vs] (forever vs))))]
    (is (= [] @seen) "nothing emitted while unresolved, placeholder included")
    (reset! sel "id-1")
    (is (= [[:flowdom.rx/loading :empty] ["id-1"]] @seen))
    (cancel!)))

(deftest equal-values-do-not-restart-or-flash
  ;; = dedup means no reconnect — and with loading-visible, no marker
  ;; either: nothing to wait for, nothing to announce
  (let [room   (atom "general")
        starts (atom [])
        {:keys [seen cancel!]} (consume!
                                (stream/follow-args [room stream/loading-visible]
                                                    (fn [vs] (swap! starts conj vs) (forever vs))))]
    (is (= [:flowdom.rx/pending ["general"]] @seen))
    (reset! room "general")
    (is (= 1 (count @starts)))
    (is (= [:flowdom.rx/pending ["general"]] @seen) "no reconnect, no marker")
    (cancel!)))

(deftest exception-events-fail-the-flow
  ;; diff-xf is the seam: an [:exception data] event throws, which
  ;; fails the eduction — the reader (an rx, an error boundary) sees a
  ;; failed flow instead of a silent console line and a hung UI. The
  ;; events arrive in separate turns, like real SSE events do.
  (async done
         (let [seen (atom [])
               flow (m/eduction (stream/diff-xf)
                                (m/ap (m/amb [:full ["a"]]
                                             (do (m/? (m/sleep 5))
                                                 [:exception {:message "boom"}]))))]
           ((m/reduce (fn [_ v] (swap! seen conj v) nil) nil flow)
            (fn [_] (is false "flow completed instead of failing") (done))
            (fn [e]
              (is (= [["a"]] @seen) "values before the exception emitted")
              (is (= {:message "boom"} (:flowrpc/error (ex-data e)))
                  "the server's payload rides the error")
              (done))))))

(deftest patches-reconstruct-and-bad-patches-do-not-emit
  (let [seen (atom [])
        flow (m/eduction (stream/diff-xf)
                         (m/seed [[:patch [[[0] :+ "early"]]] ;; patch before any :full — dropped
                                  [:full ["a"]]
                                  [:patch [[[1] :+ "b"]]]]))
        _    ((m/reduce (fn [_ v] (swap! seen conj v) nil) nil flow)
              (fn [_] nil) (fn [_] nil))]
    (is (= [["a"] ["a" "b"]] @seen)
        "patch before the first :full is ignored; later patches apply")))
