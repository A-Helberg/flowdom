(ns solidclj.satom-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [solidclj.hiccup.test-setup :refer [fresh-root]]
            [solidclj.hiccup :as h]
            [solidclj.satom :as s]
            ["solid-js" :refer [createRoot createEffect createMemo]]))

(deftest behaves-like-a-plain-atom
  (let [a (s/atom 0)]
    (is (= 0 @a))
    (is (= 1 (swap! a inc)))
    (is (= 11 (swap! a + 4 6)))
    (is (= 42 (reset! a 42)))
    (is (= 42 @a))))

(deftest watchers-fire-like-a-plain-atom
  (let [a    (s/atom 0)
        seen (atom [])]
    (add-watch a ::w (fn [k _ref old new] (swap! seen conj [k old new])))
    (reset! a 1)
    (reset! a 1) ;; watchers notify even without a value change (atom semantics)
    (remove-watch a ::w)
    (reset! a 2)
    (is (= [[::w 0 1] [::w 1 1]] @seen))))

(deftest validator-rejects-bad-state
  (let [a (s/atom {} :validator map?)]
    (is (thrown? js/Error (reset! a 1)))
    (is (= {} @a))))

(deftest deref-outside-tracking-scope-is-a-plain-read
  (let [a (s/atom :x)]
    (is (= :x @a))))

(deftest deref-inside-effect-subscribes
  (let [a    (s/atom 0)
        seen (atom [])
        dispose (createRoot
                 (fn [dispose]
                   (createEffect (fn [] (swap! seen conj @a)))
                   dispose))]
    (is (= [0] @seen) "effect runs once on creation")
    (reset! a 1)
    (is (= [0 1] @seen) "reset! re-runs the subscribed effect")
    (reset! a 1)
    (is (= [0 1] @seen) "resetting to an = value does not re-run")
    (swap! a inc)
    (is (= [0 1 2] @seen))
    (dispose)
    (reset! a 99)
    (is (= [0 1 2] @seen) "disposed effects no longer re-run")))

(deftest deref-inside-memo-subscribes
  (let [a (s/atom 2)]
    (createRoot
     (fn [dispose]
       (let [doubled (createMemo (fn [] (* 2 @a)))]
         (is (= 4 (doubled)))
         (reset! a 5)
         (is (= 10 (doubled))))
       (dispose)))))

(deftest deref-in-hiccup-thunk-updates-dom
  (let [a       (s/atom 0)
        root    (fresh-root)
        dispose (h/render [:div (fn [] (str "n=" @a))] root)]
    (try
      (is (= "<div>n=0</div>" (.-innerHTML root)))
      (reset! a 1)
      (is (= "<div>n=1</div>" (.-innerHTML root)))
      (swap! a + 10)
      (is (= "<div>n=11</div>" (.-innerHTML root)))
      (finally (dispose)))))

(deftest bare-satom-in-child-slot-is-not-interpreted
  ;; Refs are just values to the walker: printed form, never live.
  (let [a       (s/atom "live")
        root    (fresh-root)
        dispose (h/render [:div a] root)]
    (try
      (is (re-find #"SAtom" (.-textContent root)))
      (reset! a "updated")
      (is (re-find #"\"live\"" (.-textContent root)) "walk-time capture — not live")
      (finally (dispose)))))
