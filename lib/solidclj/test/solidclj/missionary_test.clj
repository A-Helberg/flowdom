(ns solidclj.missionary-test
  "JVM half of the missionary bridge tests: hold/resource/spawn!/
  tracked/flow-getter against the simulator runtime. (The CLJS half,
  missionary_test.cljs, runs the bridge against real solid-js.)"
  (:require [clojure.test :refer [deftest is testing]]
            [missionary.core :as m]
            [solidclj.api :as s]
            [solidclj.hiccup :as hic]
            [solidclj.missionary :as sm]
            [solidclj.runtime :as rt]))

(defn- counting-flow
  "Observes start/stop and emits `v` once at spawn."
  [starts stops v]
  (m/observe (fn [emit!]
               (swap! starts inc)
               (emit! v)
               (fn [] (swap! stops inc)))))

(defn- await-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

;; ---- hold ---------------------------------------------------------------

(deftest hold-is-lazy
  (let [starts (atom 0)
        stops  (atom 0)
        h      (sm/hold (counting-flow starts stops :v))]
    (is (= 0 @starts) "calling hold runs nothing")
    (hic/with-render [t [:p (fn [] (name @h))]]
      (is (= 1 @starts) "first tracked read starts the flow")
      (is (= [:p "v"] (s/snapshot t))))
    (is (= 1 @stops) "unmount cancels the flow")))

(deftest hold-of-seed-keeps-final-value
  (let [h (sm/hold (m/seed [1 2 3]))]
    (hic/with-render [t [:p (fn [] @h)]]
      (is (= [:p 3] (s/snapshot t)) "synchronous flow: last emission wins")
      (is (false? (sm/pending? h))))))

(deftest hold-of-watch-is-live
  (let [a (s/atom 1)
        h (sm/hold (m/watch a))]
    (hic/with-render [t [:p (fn [] @h)]]
      (is (= [:p 1] (s/snapshot t)))
      (swap! a inc)
      (is (= [:p 2] (s/snapshot t))))
    (is (empty? (.getWatches a)) "m/watch detached on unmount")))

(deftest hold-bridged-as-undereffed-child
  (let [a (s/atom 7)
        h (sm/hold (m/watch a))]
    (hic/with-render [t [:p h]]
      (is (= [:p 7] (s/snapshot t)) "walker auto-bridge starts the hold")
      (swap! a inc)
      (is (= [:p 8] (s/snapshot t))))))

(deftest hold-refcount-survives-thunk-reruns
  (let [starts (atom 0)
        stops  (atom 0)
        other  (s/atom 0)
        h      (sm/hold (counting-flow starts stops :v))]
    (hic/with-render [_ [:div (fn [] (str @other "-" (name @h)))]]
      (is (= 1 @starts))
      ;; each swap re-runs the thunk: release (1→0) then resubscribe
      ;; (0→1) inside one reactive turn — defer! must absorb it
      (swap! other inc)
      (swap! other inc)
      (is (= 1 @starts) "flow NOT restarted by thunk re-runs")
      (is (= 0 @stops)))
    (is (= 1 @stops) "flow cancelled once, on unmount")))

(deftest hold-pending-and-late-emission
  (let [emit* (atom nil)
        flow  (m/observe (fn [emit!] (reset! emit* emit!) (fn [])))
        h     (sm/hold flow :initial :none)]
    (hic/with-render [t [:div (fn [] (if (sm/pending? h) "pending" (str "got:" (name @h))))]]
      (is (= [:div "pending"] (s/snapshot t)))
      (@emit* :late)
      (is (= [:div "got:late"] (s/snapshot t))))))

(deftest hold-halt!-pins-and-ignores
  (let [a (s/atom 1)
        h (sm/hold (m/watch a))]
    (hic/with-render [t [:p (fn [] @h)]]
      (is (= [:p 1] (s/snapshot t)))
      (binding [rt/*warn* (fn [_])]   ;; silence the halted-subscription warning
        (sm/halt! h)
        (swap! a inc)
        (is (= [:p 1] (s/snapshot t)) "no updates after halt!")))))

(deftest hold-failure-keeps-last-value-and-exposes-error
  (let [emit* (atom nil)
        crash* (atom nil)
        flow (m/observe (fn [emit!] (reset! emit* emit!) (fn [])))
        h    (sm/hold (m/ap (let [v (m/?> flow)]
                              (if (= :boom v) (throw (ex-info "boom" {})) v))))]
    (binding [rt/*warn* (fn [_])]
      (hic/with-render [t [:div (fn []
                                  (str (or (some-> (sm/error h) ex-message) "ok")
                                       ":" @h))]]
        (@emit* 1)
        (is (= [:div "ok:1"] (s/snapshot t)))
        (@emit* :boom)
        (is (= [:div "boom:1"] (s/snapshot t))
            "failed hold serves last good value alongside the error")))))

(deftest hold-reload!-throws
  (is (thrown? UnsupportedOperationException
               (sm/reload! (sm/hold (m/seed [1]))))))

;; ---- resource -----------------------------------------------------------

(deftest resource-sync-task
  (hic/with-render [t [(fn []
                         (let [r (sm/resource (m/sp 42))]
                           [:div (fn [] (if (sm/pending? r) "pending" (str "v:" @r)))]))]]
    (is (= [:div "v:42"] (s/snapshot t)))))

(deftest resource-async-task
  (let [r* (atom nil)]
    (hic/with-render [t [(fn []
                           (let [r (sm/resource (m/sp (m/? (m/sleep 30)) :done) :initial :wait)]
                             (reset! r* r)
                             [:div (fn [] (if (sm/pending? r) "pending" (name @r)))]))]]
      (is (= [:div "pending"] (s/snapshot t)))
      (is (await-until #(not (sm/pending? @r*)) 2000) "task settles")
      (is (= [:div "done"] (s/snapshot t))))))

(deftest resource-failure-rethrows-on-deref
  (binding [rt/*warn* (fn [_])]
    (let [r* (atom nil)]
      (hic/with-render [t [(fn []
                             (let [r (sm/resource (m/sp (throw (ex-info "nope" {}))))]
                               (reset! r* r)
                               [:div (fn []
                                       (if-some [e (sm/error r)]
                                         (str "err:" (ex-message e))
                                         (str "v:" @r)))]))]]
        (is (= [:div "err:nope"] (s/snapshot t)))
        (is (thrown? clojure.lang.ExceptionInfo @@r*) "raw deref re-throws")))))

(deftest resource-reload!-reruns-task
  (let [n  (atom 0)
        r* (atom nil)]
    (hic/with-render [t [(fn []
                           (let [r (sm/resource (m/sp (swap! n inc)))]
                             (reset! r* r)
                             [:div (fn [] @r)]))]]
      (is (= [:div 1] (s/snapshot t)))
      (sm/reload! @r*)
      (is (= [:div 2] (s/snapshot t)) "tasks are recipes — re-run on reload!"))))

(deftest resource-cancelled-on-unmount
  (let [cancelled (atom 0)]
    (hic/with-render [_ [(fn []
                           (sm/resource (m/sp (try (m/? m/never)
                                                   (finally (swap! cancelled inc)))))
                           [:p "x"])]]
      (is (= 0 @cancelled)))
    (is (= 1 @cancelled) "in-flight task cancelled by owner cleanup")))

;; ---- spawn! ---------------------------------------------------------------

(deftest spawn!-cancels-on-dispose
  (let [cleaned (atom 0)
        [_ dispose] (rt/create-root
                     (fn []
                       (sm/spawn! (m/sp (try (m/? m/never)
                                             (finally (swap! cleaned inc)))))))]
    (is (= 0 @cleaned))
    (dispose)
    (is (= 1 @cleaned))))

(deftest spawn!-outside-owner-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (sm/spawn! (fn [_ _] (fn []))))))

;; ---- tracked (Solid → Missionary → Solid roundtrip) ------------------------

(deftest tracked-roundtrip
  (let [a (s/atom 1)
        h (sm/hold (sm/tracked (fn [] (* 10 @a))))]
    (hic/with-render [t [:p (fn [] @h)]]
      (is (= [:p 10] (s/snapshot t)))
      (swap! a inc)
      (is (= [:p 20] (s/snapshot t)))
      (swap! a inc)
      (is (= [:p 30] (s/snapshot t))))))

;; ---- flow-getter via solidclj.api/? -----------------------------------------

(deftest question-mark-with-flow
  (let [a (s/atom 5)]
    (hic/with-render [t [(fn []
                           (let [g (s/? (m/watch a))]
                             [:p (fn [] (g))]))]]
      (is (= [:p 5] (s/snapshot t)))
      (swap! a inc)
      (is (= [:p 6] (s/snapshot t))))))

(deftest question-mark-with-satom
  (let [a (s/atom :x)]
    (hic/with-render [t [(fn [] [:p (fn [] (name (s/? a)))])]]
      (is (= [:p "x"] (s/snapshot t)))
      (reset! a :y)
      (is (= [:p "y"] (s/snapshot t))))))
