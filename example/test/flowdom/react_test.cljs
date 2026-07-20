(ns flowdom.react-test
  "The React bridge under happy-dom: a real React root mounts inside a
  flowdom tree, atom props re-render it, unmounting the region tears
  it down. React commits asynchronously, so assertions sit behind
  scheduler ticks."
  (:require [cljs.test :refer-macros [deftest is async]]
            ["happy-dom" :refer [Window]]
            ["react" :refer [createElement]]
            [flowdom.dom :as dom]
            [flowdom.react :as react]
            [flowdom.rx :refer [rx ?]]))

(defn- box []
  (let [win (Window.)
        doc (.-document win)
        el  (.createElement doc "div")]
    (set! js/window win)
    (set! js/document doc)
    (.appendChild (.-body doc) el)
    el))

(defn- Label [^js props]
  (createElement "span" nil (str "n=" (.-n props))))

(deftest react-root-follows-atom-props
  (async done
    (let [el (box)
          n  (atom 0)
          d  (dom/mount [:div [react/component Label {:n n}]] el)]
      (js/setTimeout
       (fn []
         (is (re-find #"n=0" (.-textContent el)) "initial commit")
         (swap! n inc)
         (js/setTimeout
          (fn []
            (is (re-find #"n=1" (.-textContent el))
                "atom prop change re-rendered the root")
            (d)
            (done))
          30))
       30))))

(deftest rx-props-are-read-too
  (async done
    (let [el (box)
          a  (atom 1)
          d  (dom/mount [:div [react/component Label {:n (rx (* 10 (? a)))}]] el)]
      (js/setTimeout
       (fn []
         (is (re-find #"n=10" (.-textContent el)) "rx prop resolved")
         (reset! a 2)
         (js/setTimeout
          (fn []
            (is (re-find #"n=20" (.-textContent el)) "rx prop re-rendered")
            (d)
            (done))
          30))
       30))))
