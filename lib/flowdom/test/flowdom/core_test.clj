(ns flowdom.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [flowdom.core :as fd :refer [with-render snapshot for-by]]
            [flowdom.rx :as rx :refer [rx ?]]
            [missionary.core :as m]))

;; ---------------------------------------------------------------------------
;; static trees

(deftest static-tree
  (with-render [t [:div {:class "box"} [:span "hi"] 42]]
    (is (= [:div {:class "box"} [:span "hi"] 42] (snapshot t)))))

;; ---------------------------------------------------------------------------
;; the counter from the README

(defn counter [{:keys [start]}]
  (let [n (atom start)]
    [:div
     [:span (rx (? n))]
     [:button {:on-click (fn [_] (swap! n inc))} "+"]]))

(deftest counter-behaves
  (with-render [t [counter {:start 5}]]
    (let [snap (snapshot t)]
      (is (= [:span 5] (nth snap 1)))
      (is (= "+" (get-in snap [2 2])))
      (is (fn? (get-in snap [2 1 :on-click]))))
    ;; handlers are data: pull the fn out of the snapshot and call it
    (let [on-click (get-in (snapshot t) [2 1 :on-click])]
      (on-click :click))
    (is (= [:span 6] (nth (snapshot t) 1)))))

;; ---------------------------------------------------------------------------
;; ? crosses function boundaries

(defn fmt-price [price] (str "$" (? price)))

(deftest helper-fn-reads
  (let [price (atom 9)]
    (with-render [t [:span (rx (fmt-price price))]]
      (is (= [:span "$9"] (snapshot t)))
      (swap! price inc)
      (is (= [:span "$10"] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; structural slots: an rx emitting different hiccup swaps the subtree

(deftest structural-switch
  (let [logged-in (atom false)]
    (with-render [t [:main (rx (if (? logged-in)
                                 [:section [:h1 "profile"]]
                                 [:form "login"]))]]
      (is (= [:main [:form "login"]] (snapshot t)))
      (reset! logged-in true)
      (is (= [:main [:section [:h1 "profile"]]] (snapshot t))))))

(deftest inner-tick-does-not-remount
  ;; a nested rx ticking must update in place, not rebuild the slot
  (let [n      (atom 0)
        mounts (atom 0)
        view   (fn []
                 (swap! mounts inc)
                 [:p (rx (? n))])]
    (with-render [t [:div (rx [:section [view]])]]
      (is (= [:div [:section [:p 0]]] (snapshot t)))
      (swap! n inc)
      (swap! n inc)
      (is (= [:div [:section [:p 2]]] (snapshot t)))
      (is (= 1 @mounts)))))

;; ---------------------------------------------------------------------------
;; dynamic props

(deftest dynamic-prop
  (let [dark (atom false)]
    (with-render [t [:div {:class (rx (if (? dark) "dark" "light"))} "x"]]
      (is (= [:div {:class "light"} "x"] (snapshot t)))
      (reset! dark true)
      (is (= [:div {:class "dark"} "x"] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; conditional dependencies unsubscribe

(deftest conditional-deps-gc
  (let [use-a (atom true)
        a     (atom 1)
        b     (atom 10)
        runs  (atom 0)]
    (with-render [t [:span (rx (swap! runs inc)
                               (if (? use-a) (? a) (? b)))]]
      (is (= [:span 1] (snapshot t)))
      (reset! use-a false)
      (is (= [:span 10] (snapshot t)))
      (let [before @runs]
        (swap! a inc) ;; no longer a dependency
        (is (= before @runs))
        (is (= [:span 10] (snapshot t)))))))

;; ---------------------------------------------------------------------------
;; for-by: keyed, fine-grained, state survives reorder

(defn todo-item [todo]
  (let [clicks (atom 0)]
    [:li {:on-bump (fn [] (swap! clicks inc))}
     (rx (str (:title (? todo)) "/" (? clicks)))]))

(deftest for-by-keyed
  (let [todos (atom [{:id 1 :title "a"} {:id 2 :title "b"}])]
    (with-render [t [:ul (for-by :id todos todo-item)]]
      (is (= ["a/0" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; bump the first item's local state
      ((get-in (snapshot t) [1 1 :on-bump]))
      (is (= ["a/1" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; item value update ticks in place
      (swap! todos assoc-in [0 :title] "A")
      (is (= ["A/1" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; reorder: processes and local state survive
      (swap! todos (comp vec reverse))
      (is (= ["b/0" "A/1"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; removal unmounts
      (swap! todos (fn [ts] (vec (remove #(= 2 (:id %)) ts))))
      (is (= ["A/1"] (mapv #(nth % 2) (rest (snapshot t))))))))

;; ---------------------------------------------------------------------------
;; suspense: pending is a value; :fallback renders in place

(deftest suspense-fallback
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div {:fallback [:span "loading"]}
                     (rx [:p "hi " (? user<)])]]
      (is (= [:div [:span "loading"]] (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "hi " "ann"]] (snapshot t))))))

(deftest pending-propagates-without-fallback
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div (rx [:p (? user<)])]]
      (is (= rx/pending (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "ann"]] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; error boundary: errors travel as values, heal on dependency change,
;; remount on retry

(deftest error-boundary-heals
  (let [flag (atom false)]
    (with-render [t [:div
                     [:error-boundary
                      {:fallback (fn [e retry]
                                   [:button {:on-retry retry} (ex-message e)])}
                      (rx (if (? flag) (throw (ex-info "boom" {})) [:p "fine"]))]]]
      (is (= [:div [:p "fine"]] (snapshot t)))
      (reset! flag true)
      (is (= "boom" (get-in (snapshot t) [1 2])))
      (reset! flag false) ;; dependency change heals in place
      (is (= [:div [:p "fine"]] (snapshot t))))))

(deftest error-boundary-retry-remounts
  ;; fails on first mount, succeeds on remount — dep-stable, so only
  ;; retry (not healing) can recover it
  (let [mounts (atom 0)
        view   (fn []
                 (swap! mounts inc)
                 (let [attempt @mounts]
                   (rx (if (= 1 attempt) (throw (ex-info "boom" {})) [:p "ok"]))))]
    (with-render [t [:error-boundary
                     {:fallback (fn [e retry] [:button {:on-retry retry} "retry"])}
                     [view]]]
      (is (= "retry" (nth (snapshot t) 2)))
      (is (= 1 @mounts))
      ((get-in (snapshot t) [1 :on-retry]))
      (is (= [:p "ok"] (snapshot t)))
      (is (= 2 @mounts)))))

;; ---------------------------------------------------------------------------
;; async: emissions from other threads, awaited

(deftest await-async-tree
  (let [tick< (m/ap (m/? (m/sleep 40 "later")))]
    (with-render [t [:span {:fallback "..."} (rx (? tick<))]]
      (is (= [:span "..."] (snapshot t)))
      (is (= [:span "later"]
             (fd/await t #(= [:span "later"] %) :timeout 1000))))))

;; ---------------------------------------------------------------------------
;; guide grammar: tag sugar, class shapes, fragments

(deftest tag-sugar-and-class-shapes
  (with-render [t [:div
                   [:p.font-bold.text-blue-600#tag "sugar"]
                   [:p {:class ["font-mono" "text-sm"]} "vector"]
                   [:p {:class {:underline true :line-through false}} "map"]]]
    (is (= [:div
            [:p {:class "font-bold text-blue-600" :id "tag"} "sugar"]
            [:p {:class "font-mono text-sm"} "vector"]
            [:p {:class "underline"} "map"]]
           (snapshot t)))))

(deftest tag-sugar-merges-dynamic-class
  (let [dark (atom false)]
    (with-render [t [:p.base {:class (rx (if (? dark) "dark" "light"))} "x"]]
      (is (= [:p {:class "base light"} "x"] (snapshot t)))
      (reset! dark true)
      (is (= [:p {:class "base dark"} "x"] (snapshot t))))))

(deftest fragments-splice
  (let [n (atom 1)]
    (with-render [t [:dl
                     [:<> [:dt "a"] [:dd (rx (? n))]]
                     [:<> [:dt "b"] [:dd "static"]]]]
      (is (= [:dl [:dt "a"] [:dd 1] [:dt "b"] [:dd "static"]] (snapshot t)))
      (swap! n inc)
      (is (= [:dl [:dt "a"] [:dd 2] [:dt "b"] [:dd "static"]] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; attachable consumers: the tree is a flow, render is just one consumer

(deftest tree-is-a-flow
  ;; consume the interpreted tree with raw missionary — no render involved
  (let [n      (atom 0)
        node   (fd/interpret [:div [:span (rx (? n))]])
        states (atom [])
        cancel ((m/reduce (fn [_ v] (swap! states conj v) nil)
                          nil (rx/unwrap node))
                (fn [_] nil)
                (fn [_] nil))] ;; never throw from process callbacks
    (swap! n inc)
    (swap! n inc)
    (cancel)
    (is (= [[:div [:span 0]] [:div [:span 1]] [:div [:span 2]]] @states))))

(deftest attaching-a-consumer-reruns-nothing
  ;; rx is m/signal-shared: a second consumer of the same tree fans out
  ;; from the running computation instead of re-running it
  (let [n    (atom 0)
        runs (atom 0)]
    (with-render [t [:div [:span (rx (swap! runs inc) (? n))]]]
      (let [runs-after-render @runs
            seen   (atom nil)
            cancel ((m/reduce (fn [_ v] (reset! seen v) nil)
                              nil (rx/unwrap (:tree t)))
                    (fn [_] nil)
                    (fn [_] nil))] ;; never throw from process callbacks
        ;; the second consumer got the current tree without a re-run…
        (is (= (snapshot t) @seen))
        (is (= runs-after-render @runs))
        ;; …and both consumers track the same emissions afterwards
        (swap! n inc)
        (is (= (snapshot t) @seen))
        (cancel)))))

(deftest dual-walk-shares-component-state
  ;; the browser dev mode's semantics, proven with two spines: expand
  ;; runs components ONCE, both walks see one instance — a handler fired
  ;; through consumer A moves the tree consumer B sees
  (let [counter  (fn [{:keys [start]}]
                   (let [c (atom start)]
                     [:div
                      [:span (rx (? c))]
                      [:button {:on-click (fn [_] (swap! c inc))} "+"]]))
        expanded (fd/expand [counter {:start 5}])]
    (with-render [a expanded]
      (with-render [b expanded]
        (is (= (snapshot a) (snapshot b)))
        (let [on-click (get-in (snapshot a) [2 1 :on-click])]
          (on-click :click))
        (is (= [:span 6] (nth (snapshot b) 1)))
        (is (= (snapshot a) (snapshot b)))))))

(deftest without-expand-walks-are-independent
  ;; the counter-example that motivates expand: interpreting the raw
  ;; hiccup twice makes two component instances with separate state
  (let [counter (fn []
                  (let [c (atom 0)]
                    [:div
                     [:span (rx (? c))]
                     [:button {:on-click (fn [_] (swap! c inc))} "+"]]))
        hiccup  [counter]]
    (with-render [a hiccup]
      (with-render [b hiccup]
        ((get-in (snapshot a) [2 1 :on-click]) :click)
        (is (= [:span 1] (nth (snapshot a) 1)))
        (is (= [:span 0] (nth (snapshot b) 1)))))))

(deftest snapshot-samples-any-held-tree
  ;; snapshot/await are derived from the flow — any handle with a
  ;; running :tree can be sampled; render is not special
  (let [n    (atom 0)
        node (fd/interpret [:span (rx (? n))])
        keep ((m/reduce (fn [_ _] nil) nil (rx/unwrap node))
              (fn [_] nil) (fn [_] nil))]
    (try
      (is (= [:span 0] (snapshot {:tree node})))
      (swap! n inc)
      (is (= [:span 1] (snapshot {:tree node})))
      (finally (keep)))))

;; ---------------------------------------------------------------------------
;; portals: a DOM concern — the spine renders content in place under a marker

(deftest portal-renders-in-place-in-the-spine
  (let [open (atom true)
        n    (atom 0)]
    (with-render [t [:div
                     (rx (when (? open)
                           [:portal {:mount :some-dom-element}
                            [:p (rx (? n))]]))]]
      (is (= [:div [:portal [:p 0]]] (snapshot t)))
      (swap! n inc)
      (is (= [:div [:portal [:p 1]]] (snapshot t)))
      (reset! open false)
      (is (= [:div nil] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; effect: run a flow for its side effects, lifetime = subscription

(deftest effect-runs-while-mounted-and-stops-on-cancel
  (let [beats (atom 0)
        beat  (m/ap (loop []
                      (m/amb (swap! beats inc)
                             (do (m/? (m/sleep 10)) (recur)))))]
    (with-render [t [:p (rx/effect beat) "beating"]]
      (is (= [:p nil "beating"] (snapshot t))
          "the effect renders nothing")
      (is (pos? @beats) "mounting started the flow")
      (Thread/sleep 50)
      (is (< 1 @beats) "and it keeps running while mounted"))
    (let [frozen @beats]
      (Thread/sleep 50)
      (is (= frozen @beats) "unmount cancelled it — the count froze"))))

(deftest effect-restarts-per-mount
  (let [runs (atom 0)
        f    (m/ap (m/amb (swap! runs inc) (m/? m/never)))
        on?  (atom true)]
    (with-render [t [:div (rx (when (? on?) [:p (rx/effect f) "on"]))]]
      (is (= 1 @runs))
      (reset! on? false)
      (is (= [:div nil] (snapshot t)))
      (reset! on? true)
      (is (= 2 @runs) "a remount is a fresh run"))))
