(ns flowdom.dom-test
  "Browser-consumer smoke tests under happy-dom (node): the same
  semantics the JVM suite proves for the spine, observed as real DOM."
  (:require ["happy-dom" :refer [Window]]
            [missionary.core]
            [cljs.test :refer [deftest is]]
            [flowdom.core :as fd :refer [for-by]]
            [flowdom.dom :as dom]
            [flowdom.rx :refer [rx ?]]))

(defn- container []
  (let [win (Window.)
        doc (.-document win)
        el  (.createElement doc "div")]
    (.appendChild (.-body doc) el)
    el))

(deftest static-render
  (let [el (container)
        d  (dom/mount [:div [:span {:class "x"} "hi"] " there"] el)]
    (is (= "hi there" (.-textContent el)))
    (is (some? (.querySelector el "span.x")))
    (d)
    (is (= "" (.-textContent el)))))

(deftest counter-clicks
  (let [el (container)
        n  (atom 0)
        _  (dom/mount [:div
                       [:span (rx (? n))]
                       [:button {:on-click (fn [_] (swap! n inc))} "+"]]
                      el)
        span (.querySelector el "span")]
    (is (= "0" (.-textContent span)))
    (.click (.querySelector el "button"))
    (is (= "1" (.-textContent span)))
    ;; scalar update patched the same text node in place
    (swap! n + 10)
    (is (= "11" (.-textContent span)))))

(deftest structural-switch-swaps-subtree
  (let [el   (container)
        open (atom false)
        _    (dom/mount [:div (rx (if (? open)
                                    [:section "on"]
                                    [:p "off"]))]
                        el)]
    (is (some? (.querySelector el "p")))
    (is (nil? (.querySelector el "section")))
    (reset! open true)
    (is (some? (.querySelector el "section")))
    (is (nil? (.querySelector el "p")))))

(deftest dynamic-prop-patches-attribute
  (let [el   (container)
        dark (atom false)
        _    (dom/mount [:p {:class (rx (if (? dark) "dark" "light"))} "x"] el)
        p    (.querySelector el "p")]
    (is (= "light" (.-className p)))
    (reset! dark true)
    (is (= "dark" (.-className p)))
    ;; same element, only the attribute changed
    (is (identical? p (.querySelector el "p")))))

(deftest for-by-reorder-preserves-nodes
  (let [el    (container)
        todos (atom [{:id 1 :t "a"} {:id 2 :t "b"} {:id 3 :t "c"}])
        _     (dom/mount [:ul (for-by :id todos
                                      (fn [todo] [:li (rx (:t (? todo)))]))]
                         el)
        lis   (vec (array-seq (.querySelectorAll el "li")))]
    (is (= ["a" "b" "c"] (mapv #(.-textContent %) lis)))
    ;; reorder: the SAME nodes move
    (swap! todos (comp vec reverse))
    (let [lis' (vec (array-seq (.querySelectorAll el "li")))]
      (is (= ["c" "b" "a"] (mapv #(.-textContent %) lis')))
      (is (identical? (nth lis 0) (nth lis' 2)))
      (is (identical? (nth lis 2) (nth lis' 0))))
    ;; item edit ticks in place
    (swap! todos assoc-in [1 :t] "B")
    (is (= ["c" "B" "a"] (mapv #(.-textContent %) (array-seq (.querySelectorAll el "li")))))
    ;; removal unmounts
    (swap! todos (fn [ts] (vec (remove #(= 1 (:id %)) ts))))
    (is (= ["c" "B"] (mapv #(.-textContent %) (array-seq (.querySelectorAll el "li")))))))

(deftest fallback-renders-while-pending
  (let [el (container)
        _  (dom/mount [:div {:fallback [:em "loading"]}
                       (rx [:p (? missionary.core/none)])] ;; never emits
                      el)]
    (is (some? (.querySelector el "em")))
    (is (= "loading" (.-textContent el)))))

(deftest spine-mode-dual-consumers
  ;; dev mode: DOM patched AND the tree kept as data, one component
  ;; instance behind both — the DOM click shows up in the snapshot
  (let [el      (container)
        counter (fn []
                  (let [n (atom 0)]
                    [:div
                     [:span (rx (? n))]
                     [:button {:on-click (fn [_] (swap! n inc))} "+"]]))
        handle  (dom/mount [counter] el {:spine? true})]
    (is (= "0" (.-textContent (.querySelector el "span"))))
    (is (= [:span 0] (nth (fd/snapshot handle) 1)))
    ;; click the real DOM button…
    (.click (.querySelector el "button"))
    ;; …the DOM updated, and so did the sampled tree: same instance
    (is (= "1" (.-textContent (.querySelector el "span"))))
    (is (= [:span 1] (nth (fd/snapshot handle) 1)))
    ;; teardown cancels both consumers
    ((:dispose handle))
    (is (= "" (.-textContent el)))))

(deftest portal-mounts-into-target
  (let [el     (container)
        doc    (.-ownerDocument el)
        target (.createElement doc "div")
        _      (.appendChild (.-body doc) target)
        open   (atom true)
        msg    (atom "hi")
        d      (dom/mount [:div [:span "host"]
                           (rx (when (? open)
                                 [:portal {:mount target}
                                  [:p (rx (? msg))]]))]
                          el)]
    ;; content escapes the host tree entirely…
    (is (nil? (.querySelector el "p")))
    (is (= "hi" (.-textContent (.querySelector target "p"))))
    ;; …but stays in the reactive graph
    (swap! msg str "!")
    (is (= "hi!" (.-textContent (.querySelector target "p"))))
    ;; unmounting the portal's position removes the ported content
    (reset! open false)
    (is (nil? (.querySelector target "p")))
    (reset! open true)
    (is (some? (.querySelector target "p")))
    ;; and so does disposing the whole app
    (d)
    (is (nil? (.querySelector target "p")))))

(deftest on-mount-hands-over-the-connected-node
  (let [el    (container)
        seen  (atom nil)
        d     (dom/mount [:input {:on-mount #(reset! seen %)}] el)]
    ;; called with the real element, already connected to the document
    (is (identical? (.querySelector el "input") @seen))
    (is (true? (.-isConnected @seen)))
    (d)))

(deftest on-mount-teardown-runs-on-unmount
  (let [el     (container)
        open   (atom true)
        events (atom [])
        _      (dom/mount [:div (rx (when (? open)
                                      [:span {:on-mount (fn [_]
                                                          (swap! events conj :up)
                                                          #(swap! events conj :down))}
                                       "x"]))]
                          el)]
    (is (= [:up] @events))
    ;; unmounting the span (structural swap) cancels its on-mount teardown
    (reset! open false)
    (is (= [:up :down] @events))
    ;; and it re-runs on remount
    (reset! open true)
    (is (= [:up :down :up] @events))))
