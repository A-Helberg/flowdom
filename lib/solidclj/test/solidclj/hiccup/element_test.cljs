(ns solidclj.hiccup.element-test
  (:require [cljs.test :refer-macros [deftest is testing] :refer [use-fixtures]]
            [solidclj.hiccup :as h]
            [solidclj.satom :as satom]
            [solidclj.hiccup.test-setup :refer [fresh-root]]))

(use-fixtures :each
  {:before (fn [] (reset! @#'h/warned-keyless* #{}))})

(defn- render-html [hiccup]
  (let [root    (fresh-root)
        dispose (h/render hiccup root)]
    (try (.-innerHTML root)
         (finally (dispose)))))

(deftest keyword-shorthand-renders-class-and-id
  (is (= "<div class=\"foo\"></div>" (render-html [:div.foo])))
  (is (= "<div id=\"main\"></div>"   (render-html [:div#main])))
  (is (= "<div class=\"a b\" id=\"main\"></div>"
         (render-html [:div.a.b#main])))
  (testing ":class prop merges with shorthand classes"
    (is (= "<div class=\"foo bar\"></div>"
           (render-html [:div.foo {:class "bar"}])))))

(deftest falsy-children-are-dropped
  (is (= "<div><span>a</span></div>"
         (render-html [:div nil [:span "a"] false true])))
  (testing "numbers and zero still render"
    (is (= "<div>0</div>" (render-html [:div 0])))))

(deftest falsy-in-sequence-dropped
  (testing "for+when pattern (falsy collapses to nil) regression-guards walk-children"
    (is (= "<ul><li>a</li><li>b</li></ul>"
           (render-html [:ul (for [x [nil "a" false "b" true]]
                               (when (string? x) ^{:key x} [:li x]))]))))
  (testing "true/false literals directly inside a seq are dropped"
    ;; :key metadata is added not because the test needs it, but to
    ;; suppress the keyless-seq warning introduced in Task 10 so the
    ;; test's captured-output remains noise-free.
    (is (= "<div><span>a</span><span>b</span></div>"
           (render-html [:div (list ^{:key 1} [:span "a"] true
                                    ^{:key 2} [:span "b"] false)])))))

(deftest fragment-renders-siblings-no-wrapper
  (is (= "<span>a</span><span>b</span>"
         (render-html [:<> [:span "a"] [:span "b"]])))
  (testing "fragment in a parent"
    (is (= "<div><span>a</span><span>b</span></div>"
           (render-html [:div [:<> [:span "a"] [:span "b"]]]))))
  (testing "empty fragment renders nothing"
    (is (= "" (render-html [:<>]))))
  (testing "leading props map is ignored (Reagent compat)"
    (is (= "<span>a</span>"
           (render-html [:<> {:key 1} [:span "a"]])))
    (is (= "<span>a</span><span>b</span>"
           (render-html [:<> {} [:span "a"] [:span "b"]])))))

(deftest atom-bridge-without-owner-takes-snapshot
  (let [a   (satom/atom "first")
        warns (atom [])
        orig  js/console.warn
        _     (set! js/console.warn (fn [& args] (swap! warns conj (apply str args))))
        g     (try (#'h/atom->signal-getter a)
                   (finally (set! js/console.warn orig)))]
    (is (fn? g))
    (is (= "first" (g)))
    (reset! a "second")
    (is (= "first" (g))
        "no-owner snapshot must not update on swap!")
    (is (some #(re-find #"outside a reactive owner" %) @warns)
        "should warn when bridging without an owner scope")))

(deftest bracket-arrow-with-non-fn-throws
  (let [thrown (atom nil)]
    (try
      (render-html [:> {:not "a fn"} [:span "x"]])
      (catch :default e (reset! thrown e)))
    (is (some? @thrown))
    (is (re-find #"\[:>\] expects" (ex-message @thrown)))))

(defn- with-captured-warns [f]
  (let [warns (atom [])
        orig  js/console.warn]
    (set! js/console.warn (fn [& args] (swap! warns conj (apply str args))))
    (try (f) (finally (set! js/console.warn orig)))
    @warns))

(deftest keyless-sequence-warns
  (let [warns (with-captured-warns
                #(render-html [:ul (for [x ["a" "b" "c"]] [:li x])]))]
    (is (some #(re-find #"without :key metadata" %) warns))))

(deftest keyed-sequence-does-not-warn
  (let [warns (with-captured-warns
                #(render-html [:ul (for [x ["a" "b" "c"]]
                                     ^{:key x} [:li x])]))]
    (is (not-any? #(re-find #"without :key metadata" %) warns))))

(deftest singleton-sequence-does-not-warn
  (let [warns (with-captured-warns
                #(render-html [:ul (for [x ["a"]] [:li x])]))]
    (is (not-any? #(re-find #"without :key metadata" %) warns))))

(deftest bare-atoms-are-not-interpreted
  (testing "plain atom child: renders its printed form, with a dev warning"
    (let [a     (atom "nope")
          html  (volatile! nil)
          warns (with-captured-warns
                  #(vreset! html (render-html [:div a])))]
      (is (re-find #"Atom" @html) "printed representation in the DOM")
      (is (some #(re-find #"bare atom" %) warns))))

  (testing "satom child: printed form, captured at walk time — never live"
    (let [a        (satom/atom "x")
          root     (fresh-root)
          dispose* (volatile! nil)
          warns    (with-captured-warns
                     #(vreset! dispose* (h/render [:div a] root)))]
      (try
        (is (re-find #"SAtom" (.-textContent root)))
        (reset! a "y")
        (is (re-find #"\"x\"" (.-textContent root)) "not live")
        (is (some #(re-find #"bare atom" %) warns))
        (finally (@dispose*)))))

  (testing "prop slot: not interpreted, with a dev warning"
    (let [a     (atom "v")
          warns (with-captured-warns
                  #(render-html [:input {:value a}]))]
      (is (some #(re-find #"bare atom" %) warns)))))
