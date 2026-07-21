(ns flowdom.dom-test
  "Browser-consumer smoke tests under happy-dom (node): the same
  semantics the JVM suite proves for the spine, observed as real DOM."
  (:require ["happy-dom" :refer [Window]]
            [missionary.core]
            [cljs.test :refer [deftest is async]]
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

(deftest svg-elements-get-the-svg-namespace
  (let [el   (container)
        fill (atom "red")
        _    (dom/mount [:div
                         [:svg {:width 20 :height 20 :class "icon"}
                          [:circle {:cx 10 :cy 10 :r 8
                                    :fill (rx (? fill))}]
                          [:foreignObject {:x 0 :y 0}
                           [:p "html again"]]]]
                        el)
        svg    (.querySelector el "svg")
        circle (.querySelector el "circle")
        p      (.querySelector el "p")]
    (is (= "http://www.w3.org/2000/svg" (.-namespaceURI svg)))
    (is (= "http://www.w3.org/2000/svg" (.-namespaceURI circle))
        "descendants inherit the namespace")
    (is (= "icon" (.getAttribute svg "class")) ":class works on SVG")
    (is (= "red" (.getAttribute circle "fill")))
    (reset! fill "blue")
    (is (= "blue" (.getAttribute circle "fill")) "reactive SVG attribute")
    (is (= "http://www.w3.org/1999/xhtml" (.-namespaceURI p))
        "foreignObject children are HTML again")))

(deftest rx-valued-handler-swaps-the-listener
  (let [el    (container)
        mode  (atom :a)
        hits  (atom [])
        _     (dom/mount [:button {:on-click (rx (case (? mode)
                                                   :a (fn [_] (swap! hits conj :a))
                                                   :b (fn [_] (swap! hits conj :b))))}
                          "go"]
                         el)
        btn   (.querySelector el "button")]
    (.click btn)
    (is (= [:a] @hits) "initial listener attached")
    (reset! mode :b)
    (.click btn)
    (is (= [:a :b] @hits) "listener swapped, old one detached")))

(deftest for-by-duplicate-keys-error-to-the-boundary
  (async done
    (let [el (container)]
      (dom/mount [:error-boundary {:fallback (fn [e _] [:p (ex-message e)])}
                  [:ul (for-by identity ["a" "a"]
                               (fn [x] [:li (rx (? x))]))]]
                 el)
      ;; the boundary tears down and remounts on a microtask
      (js/queueMicrotask
       (fn []
         (is (re-find #"keys must be distinct" (.-textContent el)))
         (done))))))

(deftest for-by-pending-renders-the-enclosing-fallback
  (let [el    (container)
        ready (atom false)
        items (atom ["a" "b"])
        src   (rx (if (? ready) (? items) (? missionary.core/none)))]
    (dom/mount [:div {:fallback [:em "loading"]}
                [:ul (for-by identity src (fn [x] [:li (rx (? x))]))]]
               el)
    (is (= "loading" (.-textContent el)) "no value yet → fallback")
    (reset! ready true)
    (is (= "ab" (.-textContent el)) "value arrived → rows")
    (reset! ready false)
    (is (= "loading" (.-textContent el)) "pending again → fallback")
    (reset! ready true)
    (is (= "ab" (.-textContent el)) "and back")))

;; ---------------------------------------------------------------------------
;; fallback scoping parity with the JVM interpreter: in-place, inherited

(deftest fallback-renders-in-place-at-the-pending-slot
  (let [el (container)
        _  (dom/mount [:div {:fallback [:em "…"]}
                       [:ul [:li "static"] (rx [:li (? missionary.core/none)])]
                       [:p "sibling stays"]]
                      el)]
    (is (some? (.querySelector el "ul em"))
        "the fallback renders INSIDE the ul, at the pending slot")
    (is (= "static" (.-textContent (.querySelector el "li")))
        "non-pending siblings in the same ul stay live")
    (is (= "sibling stays" (.-textContent (.querySelector el "p"))))))

(deftest nearest-fallback-declaration-wins
  (let [el (container)
        _  (dom/mount [:div {:fallback [:em "outer"]}
                       [:section {:fallback [:em "inner"]}
                        (rx (? missionary.core/none))]]
                      el)]
    (is (= "inner" (.-textContent (.querySelector el "section em"))))))

(deftest pending-props-are-omitted-until-the-first-value
  (let [el (container)
        c  (atom nil)
        _  (dom/mount [:p {:class (rx (or (? c) (? missionary.core/none)))} "x"]
                      el)
        p  (.querySelector el "p")]
    (is (= "x" (.-textContent p)) "element live while the prop is pending")
    (is (false? (.hasAttribute p "class")) "pending prop left unset")
    (reset! c "big")
    (is (= "big" (.getAttribute p "class")))))

(deftest pending-without-any-fallback-renders-nothing
  (let [el (container)
        _  (dom/mount [:div [:span "kept"] (rx (? missionary.core/none))] el)]
    (is (= "kept" (.-textContent el))
        "the pending slot is empty; structure and siblings stay")))

;; ---------------------------------------------------------------------------
;; root error recovery: :on-error hook + remount!

(deftest root-on-error-hook-receives-uncaught-errors
  (let [el   (container)
        seen (atom nil)]
    (dom/mount [:div (rx (throw (ex-info "kaboom" {})))]
               el {:on-error (fn [e _remount!] (reset! seen e))})
    (is (= "kaboom" (ex-message @seen))
        "an error no boundary caught reaches the root hook")))

(deftest boundary-errors-do-not-reach-the-root-hook
  (let [el   (container)
        seen (atom [])]
    (dom/mount [:error-boundary {:fallback (fn [e _] [:p (ex-message e)])}
                (rx (throw (ex-info "contained" {})))]
               el {:on-error (fn [e _] (swap! seen conj e))})
    (is (empty? @seen) "the boundary owns it")))

(deftest root-remount-recovers
  (async done
    (let [el   (container)
          ;; a PLAIN deref — deliberately not subscribed, so the only
          ;; way to recover is a fresh mount
          flag (atom true)]
      (dom/mount [:div (rx (if @flag
                             (throw (ex-info "kaboom" {}))
                             [:p "recovered"]))]
                 el {:on-error (fn [_ remount!]
                                 (reset! flag false)
                                 (remount!))})
      ;; remount! defers to a microtask
      (js/queueMicrotask
       (fn []
         (is (= "recovered" (.-textContent el))
             "the root remount rebuilt the tree against the fixed state")
         (done))))))

;; ---------------------------------------------------------------------------
;; scheduling: opt-in batched patches

(deftest custom-scheduler-batches-and-coalesces
  (let [el      (container)
        n       (atom 0)
        flushes (atom [])
        _       (dom/mount [:div [:span (rx (? n))]]
                           el {:schedule (fn [flush!] (swap! flushes conj flush!))})
        span    (.querySelector el "span")]
    (is (= "0" (.-textContent span)) "initial render is synchronous")
    (swap! n inc)
    (swap! n inc)
    (swap! n inc)
    (is (= "0" (.-textContent span)) "updates wait for the scheduler")
    (is (= 1 (count @flushes)) "one flush requested for the whole burst")
    ((first @flushes))
    (is (= "3" (.-textContent span)) "the flush applies the LATEST value once")
    (swap! n inc)
    (is (= 2 (count @flushes)) "a post-flush update schedules a fresh flush")
    ((second @flushes))
    (is (= "4" (.-textContent span)))))

(deftest disposed-regions-skip-scheduled-patches
  (let [el      (container)
        o       (atom true)
        flushes (atom [])
        d       (dom/mount [:div (rx (if (? o) [:p "a"] [:p "b"]))]
                           el {:schedule (fn [flush!] (swap! flushes conj flush!))})]
    (reset! o false)   ;; queues a structural patch…
    (d)                ;; …then the whole app is disposed first
    ((first @flushes)) ;; must be a no-op, not an error
    (is (= "" (.-textContent el)))))
