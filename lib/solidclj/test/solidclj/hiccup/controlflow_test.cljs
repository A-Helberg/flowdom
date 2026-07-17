(ns solidclj.hiccup.controlflow-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [solidclj.hiccup :as h]
            [solidclj.hiccup.test-setup :refer [fresh-root]]
            [solidclj.satom :as satom]
            ["solid-js" :refer [createSignal createResource]]))

(defn- with-mount [hiccup f]
  (let [root    (fresh-root)
        dispose (h/render hiccup root)]
    (try (f root)
         (finally (dispose)))))

(deftest show-toggles-between-children-and-fallback
  (let [a (satom/atom true)]
    (with-mount
      [:show {:when (fn [] @a) :fallback [:span "off"]}
       [:p "on"]]
      (fn [root]
        (testing "initial truthy :when renders children"
          (is (= "<p>on</p>" (.-innerHTML root))))
        (testing "atom flip to falsy renders fallback"
          (reset! a false)
          (is (= "<span>off</span>" (.-innerHTML root))))
        (testing "atom flip back to truthy re-renders children"
          (reset! a true)
          (is (= "<p>on</p>" (.-innerHTML root))))))))

(deftest switch-picks-first-matching
  ;; Use Solid signals here (not CLJS atoms) because the :match :when
  ;; values are fn-accessors that derive booleans from the state. Solid
  ;; only tracks signal reads inside accessors — CLJS atom derefs would
  ;; NOT register as a dependency.
  (let [[state set-state] (createSignal :loading)]
    (with-mount
      [:switch {:fallback [:span "fallback"]}
       [:match {:when (fn [] (= :loading (state)))} [:p "loading"]]
       [:match {:when (fn [] (= :ready   (state)))} [:p "ready"]]
       [:match {:when (fn [] (= :error   (state)))} [:p "error"]]]
      (fn [root]
        (testing "initial state matches first :match"
          (is (= "<p>loading</p>" (.-innerHTML root))))
        (testing "signal flip switches to a different :match"
          (set-state :ready)
          (is (= "<p>ready</p>" (.-innerHTML root))))
        (testing "another signal flip selects the third :match"
          (set-state :error)
          (is (= "<p>error</p>" (.-innerHTML root))))
        (testing "no :match matches → renders :fallback"
          (set-state :nope)
          (is (= "<span>fallback</span>" (.-innerHTML root))))))))

(deftest switch-rejects-non-match-children
  (let [thrown (atom nil)]
    (try (with-mount
           [:switch {} [:p "wrong"]]
           (fn [_]))
         (catch :default e (reset! thrown e)))
    (is (some? @thrown))
    (is (re-find #"\[:match" (ex-message @thrown)))))

(deftest match-outside-switch-throws-in-dev
  ;; Plan's Key Conventions lock-in: ":match elsewhere errors in dev."
  ;; Guards against the typo `[:match {:when ok?} ...]` at top level
  ;; silently rendering as a <match> HTML element.
  (let [thrown (atom nil)]
    (try (with-mount
           [:match {:when true} [:p "stray"]]
           (fn [_]))
         (catch :default e (reset! thrown e)))
    (is (some? @thrown))
    (is (re-find #":match" (ex-message @thrown)))
    (is (re-find #":switch" (ex-message @thrown)))))

(deftest dynamic-swaps-the-tag
  (let [tag (satom/atom "p")]
    (with-mount
      [:dynamic {:component (fn [] @tag)} "hello"]
      (fn [root]
        (testing "initial atom value renders as the tag"
          (is (= "<p>hello</p>" (.-innerHTML root))))
        (testing "atom flip swaps the tag in place"
          (reset! tag "h2")
          (is (= "<h2>hello</h2>" (.-innerHTML root))))))))

(deftest portal-mounts-into-target-node
  (let [target  (.createElement js/document "section")
        root    (fresh-root)
        dispose (h/render
                 [:div [:portal {:mount target} [:span "ported"]]]
                 root)]
    (try
      (testing "portal content does NOT appear inside the host root"
        (is (= "<div></div>" (.-innerHTML root))))
      (testing "portal content appears inside the target node"
        ;; Solid's <Portal> wraps the teleported children in a container
        ;; <div> inside the target so it can scope cleanup; the <span> we
        ;; rendered shows up nested inside that wrapper.
        (is (= "<div><span>ported</span></div>" (.-innerHTML target))))
      (finally (dispose)))))

(deftest suspense-shows-fallback-then-content
  (async done
    ;; Wrap the entire body so any synchronous exception from h/render
    ;; (e.g. when solid-js/web loads server.cjs in Node.js) is caught
    ;; by cljs.test rather than escaping the async context and crashing
    ;; the Node process, which would prevent later test suites from running.
         (try
           (let [resolve* (atom nil)
                 fetcher  (fn [] (js/Promise. (fn [r] (reset! resolve* r))))
            ;; createResource must run inside a SolidJS owner. We create it
            ;; inside the inner component fn so solid-h provides that owner,
            ;; rather than calling it at test-body level where no owner exists.
                 inner    (fn []
                            (let [[data] (createResource (fn [] true) fetcher)]
                              (fn [] [:p (or (data) "")])))
                 root     (fresh-root)
                 dispose  (h/render
                           [:suspense {:fallback [:span "loading"]}
                            [inner]]
                           root)]
             (testing "initial render shows :fallback while resource is pending"
               (is (= "<span>loading</span>" (.-innerHTML root))))
             (@resolve* "done")
        ;; Two microtask hops: one for the promise resolution, one for
        ;; Solid's resource update batching.
             (js/queueMicrotask
              (fn []
                (js/queueMicrotask
                 (fn []
                   (try
                     (testing "resource resolves → children render with the data"
                       (is (= "<p>done</p>" (.-innerHTML root))))
                     (finally
                       (dispose)
                       (done))))))))
           (catch :default e
             (is (nil? e) (str "unexpected exception: " (ex-message e)))
             (done)))))

(deftest error-boundary-catches-render-error
  (let [boom (fn [] (throw (js/Error. "boom!")))]
    (with-mount
      [:error-boundary
       {:fallback (fn [err _reset]
                    [:span "caught: " (.-message err)])}
       [boom]]
      (fn [root]
        (testing "render-time error is caught and fallback is rendered"
          (is (= "<span>caught: boom!</span>" (.-innerHTML root))))))))

(deftest index-renders-items-by-position
  (let [xs (satom/atom ["a" "b" "c"])]
    (with-mount
      [:ul
       [:index {:each (fn [] @xs)}
        (fn [item-getter index]
          [:li "#" index " — " (fn [] (item-getter))])]]
      (fn [root]
        (testing "initial render emits one <li> per item at each position"
          (is (= "<ul><li>#0 — a</li><li>#1 — b</li><li>#2 — c</li></ul>"
                 (.-innerHTML root))))
        (testing "atom reset shrinks the list and updates items in place"
          (reset! xs ["x" "y"])
          (is (= "<ul><li>#0 — x</li><li>#1 — y</li></ul>"
                 (.-innerHTML root))))))))
