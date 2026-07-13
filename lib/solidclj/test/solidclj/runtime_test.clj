(ns solidclj.runtime-test
  "JVM reactive core: signals, effects, owners, cleanup, and the
  tree-building h — exercised through the real hiccup walker."
  (:require [clojure.test :refer [deftest is testing]]
            [solidclj.api :as s]
            [solidclj.hiccup :as hic]
            [solidclj.runtime :as rt]))

(defn- sc [slot] (:content @slot))

(deftest static-tree
  (let [[tree dispose] (rt/create-root #(hic/as-element [:div.box {:id "a"} "hi" 42]))]
    (is (rt/element-node? tree))
    (is (= "div" (:tag @tree)))
    (is (= "box" (get-in @tree [:props :class])))
    (is (= "a"   (get-in @tree [:props :id])))
    (is (= ["hi" 42] (:children @tree)))
    (dispose)))

(deftest reactive-thunk-child
  (let [a (s/atom "hi")
        [tree dispose] (rt/create-root #(hic/as-element [:div (fn [] @a)]))
        slot (first (:children @tree))]
    (is (rt/slot-node? slot))
    (is (= "hi" (sc slot)))
    (reset! a "bye")
    (is (= "bye" (sc slot)))
    (dispose)
    (reset! a "after-dispose")
    (is (= "bye" (sc slot)) "no updates after dispose")))

(deftest satom-auto-bridge-child
  (let [a (s/atom 1)
        [tree dispose] (rt/create-root #(hic/as-element [:span a]))
        slot (first (:children @tree))]
    (is (= 1 (sc slot)))
    (swap! a inc)
    (is (= 2 (sc slot)))
    (dispose)
    (is (empty? (.getWatches a)) "bridge watch removed on dispose")))

(deftest reactive-prop
  (let [v (s/atom "x")
        [tree dispose] (rt/create-root
                        #(hic/as-element [:input {:value v :onChange identity}]))]
    (is (= "x" (get-in @tree [:props :value])))
    (reset! v "y")
    (is (= "y" (get-in @tree [:props :value])))
    (is (fn? (get-in @tree [:props :onChange])) "handler stored as data")
    (dispose)))

(deftest reactive-style-entry
  (let [c (s/atom "red")
        [tree dispose] (rt/create-root
                        #(hic/as-element [:p {:style {:color c :margin-top "1px"}}]))]
    (is (= "1px" (get-in @tree [:props :style "marginTop"])))
    (is (= "red" (get-in @tree [:props :style "color"])))
    (reset! c "blue")
    (is (= "blue" (get-in @tree [:props :style "color"])))
    (dispose)))

(deftest conditional-thunk-swaps-subtree
  (let [flag (s/atom true)
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div (fn [] (if @flag [:p "yes"] [:span "no"]))]))
        slot (first (:children @tree))]
    (is (= "p" (:tag @(sc slot))))
    (is (= ["yes"] (:children @(sc slot))))
    (reset! flag false)
    (is (= "span" (:tag @(sc slot))))
    (is (= ["no"] (:children @(sc slot))))
    (dispose)))

(deftest component-runs-once-with-local-state
  (let [run-count (atom 0)
        counter   (fn [start]
                    (swap! run-count inc)
                    (let [n (s/atom start)]
                      [:div
                       [:button {:onClick (fn [_] (swap! n inc))} "+"]
                       [:span (fn [] @n)]]))
        [tree dispose] (rt/create-root #(hic/as-element [counter 5]))
        span-el  (second (:children @tree))
        slot     (first (:children @span-el))
        on-click (get-in @(first (:children @tree)) [:props :onClick])]
    (is (= 1 @run-count) "component body ran once")
    (is (= 5 (sc slot)))
    (on-click :fake-event)
    (is (= 6 (sc slot)) "local satom updated the tree")
    (is (= 1 @run-count) "component body did NOT re-run")
    (dispose)))

(deftest nested-thunk-granularity
  (let [outer-runs (atom 0)
        inner (s/atom 0)
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div (fn []
                                  (swap! outer-runs inc)
                                  [:section (fn [] @inner)])]))]
    (is (= 1 @outer-runs))
    (swap! inner inc)
    (swap! inner inc)
    (is (= 1 @outer-runs) "inner thunk updates do not re-run outer thunk")
    (let [inner-slot (first (:children @(sc (first (:children @tree)))))]
      (is (= 2 (sc inner-slot))))
    (dispose)))

(deftest on-cleanup-before-rerun-and-on-dispose
  (let [cleanups (atom [])
        x (s/atom 0)
        [_ dispose] (rt/create-root
                     #(hic/as-element
                       [:div (fn []
                               (let [v @x]
                                 (rt/on-cleanup (fn [] (swap! cleanups conj v)))
                                 v))]))]
    (is (= [] @cleanups))
    (reset! x 1)
    (is (= [0] @cleanups) "cleanup ran before re-run")
    (reset! x 2)
    (is (= [0 1] @cleanups))
    (dispose)
    (is (= [0 1 2] @cleanups) "cleanup ran on dispose")))

(deftest equality-gated-propagation
  (let [thunk-runs (atom 0)
        a (s/atom {:x 1})
        [_ dispose] (rt/create-root
                     #(hic/as-element [:div (fn [] (swap! thunk-runs inc) (str @a))]))]
    (is (= 1 @thunk-runs))
    (reset! a {:x 1})                                     ;; equal but not identical
    (is (= 1 @thunk-runs) "equal write deduped")
    (reset! a {:x 2})
    (is (= 2 @thunk-runs))
    (dispose)))

(deftest fragment-and-seq-flattening
  (let [[tree dispose] (rt/create-root
                        #(hic/as-element [:ul [:<> (for [i (range 3)] ^{:key i} [:li i])]]))
        frag (first (:children @tree))]
    (is (vector? frag))
    (is (= ["li" "li" "li"] (map (comp :tag deref) (first frag))))
    (dispose)))

(deftest runaway-loop-guard
  (testing "an effect that reads AND writes the same satom throws instead of hanging"
    (let [a (s/atom 0)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (rt/create-root
                    #(hic/as-element [:div (fn [] (let [v @a] (swap! a inc) v))])))))))
