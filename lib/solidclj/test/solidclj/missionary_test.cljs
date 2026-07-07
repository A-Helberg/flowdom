(ns solidclj.missionary-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [solidclj.hiccup.test-setup :refer [fresh-root]]
            [missionary.core :as m]
            [solidclj.api :as s]
            [solidclj.missionary :as sm]
            ["solid-js" :refer [createRoot createEffect]]))

;; ---------------------------------------------------------------- hold

(deftest hold-is-lazy-and-stops-on-dispose
  (async done
    (let [started (atom 0)
          stopped (atom 0)
          flow    (m/observe (fn [emit!]
                               (swap! started inc)
                               (emit! :v)
                               (fn [] (swap! stopped inc))))
          h       (sm/hold flow :initial :none)]
      (is (= 0 @started) "hold alone runs nothing")
      (let [seen    (atom [])
            dispose (createRoot
                     (fn [dispose]
                       (createEffect (fn [] (swap! seen conj @h)))
                       dispose))]
        (is (= 1 @started) "first tracked deref starts the flow")
        (is (= [:v] @seen) "synchronous first emission is seen immediately")
        (dispose)
        ;; release check is deferred by a microtask; give it a turn
        (js/setTimeout
         (fn []
           (is (= 1 @stopped) "last subscriber release cancels the flow")
           (done))
         0)))))

(deftest hold-updates-and-dedups-with-=
  (let [a    (s/atom 0)
        h    (sm/hold (m/watch a))
        runs (atom 0)
        seen (atom [])
        dispose (createRoot
                 (fn [dispose]
                   (createEffect (fn []
                                   (swap! runs inc)
                                   (swap! seen conj @h)))
                   dispose))]
    (is (= [0] @seen) "m/watch's initial value lands before first read returns")
    (swap! a inc)
    (is (= [0 1] @seen))
    (reset! a 1) ;; equal value: watchers fire (atom semantics) but no re-run
    (is (= 2 @runs))
    (dispose)))

(deftest hold-restarts-after-full-release
  (async done
    (let [runs (atom 0)
          flow (m/observe (fn [emit!]
                            (swap! runs inc)
                            (emit! @runs)
                            (fn [])))
          h    (sm/hold flow :initial :none)
          d1   (createRoot (fn [d] (createEffect (fn [] @h)) d))]
      (is (= 1 @runs))
      (d1)
      (js/setTimeout
       (fn []
         (let [seen (atom [])
               d2   (createRoot (fn [d]
                                  (createEffect (fn [] (swap! seen conj @h)))
                                  d))]
           (is (= 2 @runs) "new subscriber restarts the flow from scratch")
           (is (= [2] @seen))
           (d2)
           (done)))
       0))))

(deftest hold-survives-its-own-updates
  ;; a value change re-runs the subscribing effect: Solid disposes the
  ;; old run (refcount 1→0) then re-executes (0→1). The flow must NOT
  ;; be torn down in between.
  (async done
    (let [a       (s/atom 0)
          stopped (atom 0)
          flow    (m/observe (fn [emit!]
                               (emit! @a)
                               (add-watch a ::f (fn [_ _ _ n] (emit! n)))
                               (fn []
                                 (remove-watch a ::f)
                                 (swap! stopped inc))))
          h       (sm/hold flow)
          seen    (atom [])
          dispose (createRoot (fn [d]
                                (createEffect (fn [] (swap! seen conj @h)))
                                d))]
      (swap! a inc)
      (swap! a inc)
      (js/setTimeout
       (fn []
         (is (= [0 1 2] @seen))
         (is (= 0 @stopped) "flow kept running across effect re-runs")
         (dispose)
         (js/setTimeout
          (fn []
            (is (= 1 @stopped))
            (done))
          0))
       0))))

(deftest hold-pending-then-error
  (async done
    (let [flow   (m/ap (m/? (m/sleep 5)) (throw (js/Error. "boom")))
          h      (sm/hold flow)
          states (atom [])
          dispose (createRoot
                   (fn [d]
                     (createEffect
                      (fn []
                        (swap! states conj [(sm/pending? h) (some? (sm/error h)) @h])))
                     d))]
      (is (= [[true false nil]] @states))
      (js/setTimeout
       (fn []
         (is (= [false true nil] (last @states))
             "failure clears pending, sets error, keeps last value")
         (dispose)
         (done))
       30))))

(deftest hold-add-watch-starts-flow
  (let [started (atom 0)
        flow    (m/observe (fn [emit!]
                             (swap! started inc)
                             (emit! 1)
                             (fn [])))
        h       (sm/hold flow)
        seen    (atom [])]
    (is (= 0 @started))
    (add-watch h ::w (fn [_ _ old new] (swap! seen conj [old new])))
    (is (= 1 @started) "add-watch counts as a subscription")
    (is (= [[nil 1]] @seen) "watcher registered before the flow started")
    (is (= 1 @h) "untracked read of a running hold is live")
    (remove-watch h ::w)))

(deftest hold-reload-throws
  (is (thrown? js/Error (sm/reload! (sm/hold (m/seed [1]))))))

;; ------------------------------------------------------------ resource

(deftest resource-suspends-then-renders
  (async done
    (let [root    (fresh-root)
          task    (m/sp (m/? (m/sleep 10)) "world")
          dispose (s/render
                   [(fn []
                      (let [greeting (sm/resource task)]
                        [:suspense {:fallback [:span "loading"]}
                         (fn [] [:span "hello " @greeting])]))]
                   root)]
      (is (.includes (.-textContent root) "loading")
          "pending deref suspends to the boundary fallback")
      (js/setTimeout
       (fn []
         (is (.includes (.-textContent root) "hello world"))
         (dispose)
         (done))
       50))))

(deftest resource-reload-reruns-task
  ;; suspense-free on purpose: happy-dom's Suspense does not re-render
  ;; content after a second suspension cycle, though the resource
  ;; itself refetches and settles fine — verify re-suspension visually
  ;; in a browser (the docs example exercises it).
  (async done
    (let [n       (atom 0)
          task    (m/sp (m/? (m/sleep 1)) (swap! n inc))
          root    (fresh-root)
          ref*    (atom nil)
          dispose (s/render
                   [(fn []
                      (let [r (sm/resource task)]
                        (reset! ref* r)
                        [:div (fn [] [:span "n=" (str @r)])]))]
                   root)]
      (js/setTimeout
       (fn []
         (is (.includes (.-textContent root) "n=1"))
         (sm/reload! @ref*)
         (js/setTimeout
          (fn []
            (is (= 2 @n) "reload! re-ran the task")
            (is (.includes (.-textContent root) "n=2"))
            (dispose)
            (done))
          30))
       30))))

(deftest resource-initial-value-serves-while-pending
  ;; :initial matters when reading OUTSIDE a suspense boundary — under
  ;; one, the fallback still shows during fetches (createResource
  ;; registers any in-flight read, initialValue or not).
  (async done
    (let [root    (fresh-root)
          task    (m/sp (m/? (m/sleep 10)) "fresh")
          dispose (s/render
                   [(fn []
                      (let [r (sm/resource task :initial "stale")]
                        [:div (fn [] [:span (str @r)])]))]
                   root)]
      (is (.includes (.-textContent root) "stale"))
      (js/setTimeout
       (fn []
         (is (.includes (.-textContent root) "fresh"))
         (dispose)
         (done))
       50))))

;; --------------------------------------------------------------- halt!

(deftest halt!-stops-a-hold-permanently
  (let [stopped (atom 0)
        flow    (m/observe (fn [emit!]
                             (emit! 1)
                             (fn [] (swap! stopped inc))))
        h       (sm/hold flow)
        dispose (createRoot (fn [d] (createEffect (fn [] @h)) d))]
    (is (= 0 @stopped))
    (sm/halt! h)
    (is (= 1 @stopped) "halt! cancels the running flow")
    (is (= 1 @h) "last value is pinned")
    (dispose)
    ;; a subscription after halt is ignored — the flow must not restart
    (let [d2 (createRoot (fn [d] (createEffect (fn [] @h)) d))]
      (is (= 1 @stopped))
      (is (= 1 @h))
      (d2))))

(deftest halt!-cancels-a-resource
  (let [state   (atom :init)
        task    (fn [_success _failure]
                  (reset! state :running)
                  (fn [] (reset! state :cancelled)))
        ref*    (atom nil)
        dispose (createRoot (fn [d] (reset! ref* (sm/resource task)) d))]
    (is (= :running @state))
    (sm/halt! @ref*)
    (is (= :cancelled @state))
    (dispose)))

;; -------------------------------------------------------------- spawn!

(deftest spawn!-cancels-on-owner-cleanup
  (let [state   (atom :init)
        task    (fn [_success _failure]
                  (reset! state :running)
                  (fn [] (reset! state :cancelled)))
        dispose (createRoot (fn [d] (sm/spawn! task) d))]
    (is (= :running @state))
    (dispose)
    (is (= :cancelled @state))))

(deftest spawn!-throws-without-an-owner
  (is (thrown? js/Error (sm/spawn! (fn [_ _] (fn []))))))

;; ------------------------------------------------------------- tracked

(deftest tracked-follows-solid-state
  (let [a      (s/atom 1)
        seen   (atom [])
        cancel ((m/reduce (fn [_ v] (swap! seen conj v) nil) nil
                          (sm/tracked (fn [] (* 2 @a))))
                (fn [_] nil)
                (fn [_] nil))] ;; cancellation lands here; expected
    (is (= [2] @seen) "initial value emitted on spawn")
    (swap! a inc)
    (is (= [2 4] @seen))
    (reset! a 2) ;; = value → memo dedups
    (is (= [2 4] @seen))
    (cancel)
    (swap! a inc)
    (is (= [2 4] @seen) "disposed root no longer tracks")))

;; ------------------------------------------------------------------ ?

(deftest ?-materialises-flows-per-owner
  (let [root    (fresh-root)
        dispose (s/render
                 [(fn []
                    (let [g (s/? (m/seed [1 2 3]))]
                      [:p (fn [] (str (g)))]))]
                 root)]
    (is (.includes (.-textContent root) "3"))
    (dispose)))

(deftest ?-reads-reactive-atoms
  (let [a       (s/atom 5)
        root    (fresh-root)
        dispose (s/render [(fn [] [:p (fn [] (str (s/? a)))])] root)]
    (is (.includes (.-textContent root) "5"))
    (reset! a 9)
    (is (.includes (.-textContent root) "9"))
    (dispose)))
