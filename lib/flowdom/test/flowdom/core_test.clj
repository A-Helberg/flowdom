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

(deftest pending-without-any-fallback-renders-nothing
  ;; DOM parity: a pending slot with no inherited :fallback is empty —
  ;; surrounding structure stays, the slot contributes nothing
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div (rx [:p (? user<)])]]
      (is (= [:div nil] (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "ann"]] (snapshot t))))))

(deftest fallback-renders-in-place-at-the-pending-slot
  ;; the fallback is INHERITED and renders AT the pending slot —
  ;; intermediate structure and non-pending siblings stay live
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div {:fallback [:em "…"]}
                     [:ul [:li "static"] (rx [:li (? user<)])]
                     [:p "sibling stays"]]]
      (is (= [:div [:ul [:li "static"] [:em "…"]] [:p "sibling stays"]]
             (snapshot t)))
      (dv "x")
      (is (= [:div [:ul [:li "static"] [:li "x"]] [:p "sibling stays"]]
             (snapshot t))))))

(deftest nearest-fallback-declaration-wins
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div {:fallback [:em "outer"]}
                     [:section {:fallback [:em "inner"]}
                      (rx [:p (? user<)])]]]
      (is (= [:div [:section [:em "inner"]]] (snapshot t))))))

(deftest pending-props-are-omitted-until-the-first-value
  ;; DOM parity: spawn-prop! leaves the attribute unset while pending
  (let [dv     (m/dfv)
        class< (m/ap (m/? dv))]
    (with-render [t [:p {:class (rx (? class<))} "x"]]
      (is (= [:p "x"] (snapshot t)) "pending prop omitted, element live")
      (dv "big")
      (is (= [:p {:class "big"} "x"] (snapshot t))))))

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

;; ---------------------------------------------------------------------------
;; hold: share a cold flow, pending until its first value

(deftest hold-shares-one-subscription
  (let [subs (atom 0)
        src  (m/observe (fn [emit!]
                          (swap! subs inc)
                          (emit! 7)
                          (fn [] (swap! subs dec))))
        h    (rx/hold src)]
    (with-render [t [:div [:span (rx (? h))] [:span (rx (* 2 (? h)))]]]
      (is (= [:div [:span 7] [:span 14]] (snapshot t)))
      (is (= 1 @subs) "two readers, one subscription"))
    (is (= 0 @subs) "last reader leaving tears it down")))

(deftest hold-is-pending-until-the-first-value
  (let [dv (m/dfv)
        h  (rx/hold (m/ap (m/? dv)))]
    (with-render [t [:div {:fallback [:em "loading"]} (rx [:p (? h)])]]
      (is (= [:div [:em "loading"]] (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "ann"]] (snapshot t))))))

(deftest pending-marker-protocol-re-enters-pending
  ;; any flow may emit the pending marker (rx/pending) to signal 'no
  ;; value right now' — readers re-enter pending until the next value.
  ;; This is how flowrpc's loading-visible surfaces refetches.
  (let [emit* (atom nil)
        src   (m/observe (fn [emit!]
                           (reset! emit* emit!)
                           (fn [] (reset! emit* nil))))
        h     (rx/hold src)
        l<    (rx/loading?< h)]
    (with-render [t [:div {:fallback [:em "loading"]}
                     (rx [:p (? h)])
                     [:span (rx (str (? l<)))]]]
      (is (re-find #"loading" (str (snapshot t))) "pending before the first value")
      (@emit* "first")
      (is (= [:div [:p "first"] [:span "false"]] (snapshot t)))
      (@emit* rx/pending)
      (is (= [:div [:em "loading"] [:span "true"]] (snapshot t))
          "marker re-enters pending, loading?< flips")
      (@emit* "second")
      (is (= [:div [:p "second"] [:span "false"]] (snapshot t))))
    (is (nil? @emit*) "teardown reached the source")))

;; ---------------------------------------------------------------------------
;; state flows: loading?< / error< / error?< — loading and failure as
;; ordinary flows, the value channel untouched

(deftest loading?<-flips-on-first-value
  (let [dv (m/dfv)
        h  (rx/hold (m/ap (m/? dv)))
        p< (rx/loading?< h)]
    (with-render [t [:div (rx (if (? p<) [:em "loading"] [:p (? h)]))]]
      (is (= [:div [:em "loading"]] (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "ann"]] (snapshot t))))))

(deftest loading?<-is-false-immediately-for-a-ready-source
  ;; a synchronously-ready source settles during subscription — no
  ;; transient loading frame
  (let [h  (rx/hold (m/seed [7]))
        p< (rx/loading?< h)]
    (with-render [t [:span (rx (if (? p<) "loading" "ready"))]]
      (is (= [:span "ready"] (snapshot t))))))

(defn- failable
  "Flow: emits the dfv's value, or throws it if it is a Throwable."
  [dv]
  (m/ap (let [v (m/? dv)]
          (if (instance? Throwable v) (throw v) v))))

(deftest error<-carries-the-failure-as-a-value
  (let [dv (m/dfv)
        h  (rx/hold (failable dv))
        p< (rx/loading?< h)
        e< (rx/error< h)]
    (with-render [t [:div (rx (cond
                                (some? (? e<)) [:em (ex-message (? e<))]
                                (? p<)         [:em "loading"]
                                :else          [:p (? h)]))]]
      (is (= [:div [:em "loading"]] (snapshot t)))
      (dv (ex-info "boom" {}))
      (is (= [:div [:em "boom"]] (snapshot t))))))

(deftest error?<-flips-on-failure-and-loading?<-settles
  (let [dv  (m/dfv)
        h   (rx/hold (failable dv))
        p<  (rx/loading?< h)
        e?< (rx/error?< h)]
    (with-render [t [:span (rx (str (? p<) "/" (? e?<)))]]
      (is (= [:span "true/false"] (snapshot t)))
      (dv (ex-info "boom" {}))
      (is (= [:span "false/true"] (snapshot t))
          "failure settles loading and raises the error flag"))))

(deftest error<-stays-nil-on-success
  (let [dv (m/dfv)
        h  (rx/hold (failable dv))
        e< (rx/error< h)]
    (with-render [t [:span (rx (str (? e<)))]]
      (is (= [:span ""] (snapshot t)))
      (dv "fine")
      (is (= [:span ""] (snapshot t))))))

(deftest error?<-clears-when-the-source-heals
  ;; an rx source that heals (dependency change replaces the Err with
  ;; a value) clears the error flag — states track, not latch
  (let [flag (atom true)
        src  (rx (if (? flag) (throw (ex-info "boom" {})) :ok))
        e?<  (rx/error?< src)]
    (with-render [t [:span (rx (str (? e?<)))]]
      (is (= [:span "true"] (snapshot t)))
      (reset! flag false)
      (is (= [:span "false"] (snapshot t))))))

(deftest loading-with-value-travels-as-a-value
  ;; (rx/loading x) rides the value channel: ? hands the wrapper
  ;; through untouched (no suspense), rx/value unwraps for rendering,
  ;; and loading?< reads true until a bare answer replaces it
  (let [emit* (atom nil)
        src   (m/observe (fn [emit!]
                           (reset! emit* emit!)
                           (fn [] (reset! emit* nil))))
        h     (rx/hold src)
        l<    (rx/loading?< h)]
    (with-render [t [:div {:fallback [:em "suspended"]}
                     [:p (rx (str (rx/value (? h))))]
                     [:code (rx (str (rx/loading? (? h))))]
                     [:span (rx (str (? l<)))]]]
      (@emit* (rx/loading "—:—"))
      (is (= [:div [:p "—:—"] [:code "true"] [:span "true"]] (snapshot t))
          "placeholder renders — no fallback — while loading?< stays true")
      (@emit* "12:00")
      (is (= [:div [:p "12:00"] [:code "false"] [:span "false"]] (snapshot t))
          "a bare answer settles everything"))))

(deftest state-flows-share-the-held-subscription
  (let [subs (atom 0)
        src  (m/observe (fn [emit!]
                          (swap! subs inc)
                          (emit! 7)
                          (fn [] (swap! subs dec))))
        h    (rx/hold src)
        p<   (rx/loading?< h)
        e?<  (rx/error?< h)]
    (with-render [t [:div
                     [:span (rx (? h))]
                     [:span (rx (str (? p<)))]
                     [:span (rx (str (? e?<)))]]]
      (is (= [:div [:span 7] [:span "false"] [:span "false"]] (snapshot t)))
      (is (= 1 @subs) "value reader + two state readers, one subscription"))
    (is (= 0 @subs) "teardown intact")))

;; ---------------------------------------------------------------------------
;; churn detection: the build-a-flow-inside-the-body footgun

(deftest building-a-flow-inside-the-body-warns-and-trips-the-breaker
  (let [warnings (atom [])
        tick     (atom 0)]
    (binding [rx/*on-churn* #(swap! warnings conj %)]
      ;; the footgun: m/watch built INSIDE the body — a brand-new flow
      ;; every run, whose synchronous first emission re-triggers the
      ;; run: an infinite loop without the breaker
      (with-render [t [:span (rx (? (m/watch tick)))]]
        (is (= 1 (count @warnings)) "churn warned, exactly once per rx")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"runaway rx"
                              (snapshot t))
            "the breaker stopped the loop with an error")))))

(deftest branch-toggling-does-not-warn
  ;; conditional branches re-create RECENTLY-DROPPED sources — that's
  ;; normal dynamic dependency tracking, not churn
  (let [warnings (atom [])
        c (atom true)
        a (atom :a)
        b (atom :b)]
    (binding [rx/*on-churn* #(swap! warnings conj %)]
      (with-render [t [:span (rx (name (if (? c) (? a) (? b))))]]
        (dotimes [_ 6] (swap! c not))
        (is (= [:span "a"] (snapshot t)))
        (is (empty? @warnings))))))

(deftest hoisted-flows-do-not-warn
  (let [warnings (atom [])
        tick     (atom 0)
        w        (m/watch tick)]
    (binding [rx/*on-churn* #(swap! warnings conj %)]
      (with-render [t [:span (rx (? w))]]
        (dotimes [_ 5] (swap! tick inc))
        (is (= [:span 5] (snapshot t)))
        (is (empty? @warnings) "one stable source, zero churn")))))

;; ---------------------------------------------------------------------------
;; for-by contract: distinct keys, pending → fallback (parity with the DOM)

(deftest for-by-duplicate-keys-error-to-the-boundary
  (with-render [t [:error-boundary {:fallback (fn [e _] [:p (ex-message e)])}
                   [:ul (for-by identity ["a" "a"]
                                (fn [x] [:li (rx (? x))]))]]]
    (is (re-find #"keys must be distinct" (str (snapshot t))))))

(deftest for-by-pending-renders-the-enclosing-fallback
  (let [ready (atom false)
        items (atom ["a" "b"])
        src   (rx (if (? ready) (? items) (? m/none)))]
    (with-render [t [:div {:fallback [:em "loading"]}
                     [:ul (for-by identity src (fn [x] [:li (rx (? x))]))]]]
      (is (re-find #"loading" (str (snapshot t))) "no value yet → fallback")
      (reset! ready true)
      (is (= [:div [:ul [:li "a"] [:li "b"]]] (snapshot t)))
      (reset! ready false)
      (is (re-find #"loading" (str (snapshot t))) "pending again → fallback"))))
