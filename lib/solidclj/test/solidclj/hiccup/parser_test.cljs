(ns solidclj.hiccup.parser-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["solid-js" :refer [createRoot]]
            [solidclj.hiccup :as h]
            [solidclj.satom :as satom]
            [solidclj.hiccup.test-setup]))

(deftest parse-tag-plain
  (is (= ["div" [] nil] (#'h/parse-tag :div)))
  (is (= ["span" [] nil] (#'h/parse-tag :span))))

(deftest parse-tag-classes
  (is (= ["div" ["foo"] nil]       (#'h/parse-tag :div.foo)))
  (is (= ["div" ["foo" "bar"] nil] (#'h/parse-tag :div.foo.bar)))
  (is (= ["section" ["a" "b" "c"] nil]
         (#'h/parse-tag :section.a.b.c))))

(deftest parse-tag-id
  (is (= ["div" [] "main"]       (#'h/parse-tag :div#main)))
  (is (= ["p"   [] "hello-id"]   (#'h/parse-tag :p#hello-id))))

(deftest parse-tag-class-and-id
  (is (= ["div" ["foo"] "main"]         (#'h/parse-tag :div.foo#main)))
  (is (= ["div" ["foo" "bar"] "main"]   (#'h/parse-tag :div.foo.bar#main))))

(deftest parse-tag-implicit-div
  (testing "leading . means :div implied"
    (is (= ["div" ["foo"] nil] (#'h/parse-tag :.foo)))
    (is (= ["div" [] "main"]   (#'h/parse-tag :#main)))
    (is (= ["div" ["a" "b"] "x"] (#'h/parse-tag :.a.b#x)))))

(deftest parse-tag-tolerates-degenerate-forms
  (testing "trailing dot collapses to no extra classes"
    (is (= ["div" [] nil] (#'h/parse-tag :div.))))
  (testing "consecutive dots collapse to single class"
    (is (= ["div" ["foo"] nil] (#'h/parse-tag :div..foo))))
  (testing "trailing # with no id becomes nil id"
    (is (= ["div" [] nil] (#'h/parse-tag :div#))))
  (testing "lone # becomes implicit :div with nil id"
    (is (= ["div" [] nil] (#'h/parse-tag :#)))))

(deftest normalize-style-passthrough
  (is (= nil       (#'h/normalize-style nil)))
  (is (= "color:red" (#'h/normalize-style "color:red"))))

(deftest normalize-style-kebab-to-camel
  (let [out (#'h/normalize-style {:background-color "red"
                                  :font-size        "12px"
                                  :z-index          5})]
    (is (= "red"   (.-backgroundColor out)))
    (is (= "12px" (.-fontSize out)))
    (is (= 5      (.-zIndex out)))))

(deftest normalize-style-already-camel
  (let [out (#'h/normalize-style {:color "blue" :marginTop "1rem"})]
    (is (= "blue"  (.-color out)))
    (is (= "1rem" (.-marginTop out)))))

(deftest normalize-style-values-are-not-interpreted
  (testing "an accessor fn style value reads live"
    (createRoot
     (fn [_dispose]
       (let [a   (satom/atom "red")
             out (#'h/normalize-style {:color (fn [] @a)})
             v   (.-color out)]
         (is (fn? v))
         (is (= "red" (v)))
         (reset! a "blue")
         (is (= "blue" (v))))
       nil)))
  (testing "a bare atom style value passes through as the object itself"
    (let [a   (satom/atom "red")
          out (#'h/normalize-style {:color a})]
      (is (identical? a (.-color out))))))

(deftest normalize-style-preserves-css-custom-properties
  (testing "CSS custom properties (--var) keep their leading dashes"
    (let [out (#'h/normalize-style {:--brand-primary "#2563eb"
                                    :--brand-radius  "0.375rem"
                                    :background-color "white"})]
      (is (= "#2563eb"  (aget out "--brand-primary")))
      (is (= "0.375rem" (aget out "--brand-radius")))
      (is (= "white"    (.-backgroundColor out)))))
  (testing "vendor prefixes (single leading dash) still PascalCase"
    (let [out (#'h/normalize-style {:-webkit-tap-highlight-color "transparent"})]
      (is (= "transparent" (.-WebkitTapHighlightColor out))))))

(deftest normalize-class-strings
  (let [[s lst] (#'h/normalize-class "foo bar")]
    (is (= "foo bar" s))
    (is (nil? lst)))
  (let [[s lst] (#'h/normalize-class :foo)]
    (is (= "foo" s))
    (is (nil? lst))))

(deftest normalize-class-vector
  (let [[s lst] (#'h/normalize-class ["a" "b" nil "c" false])]
    (is (= "a b c" s))
    (is (nil? lst))))

(deftest normalize-class-map-becomes-classlist
  (let [[s lst] (#'h/normalize-class {:active true :disabled false})]
    (is (nil? s))
    (is (some? lst))
    (is (true?  (.-active lst)))
    (is (false? (.-disabled lst)))))

(deftest normalize-class-nil
  (is (= [nil nil] (#'h/normalize-class nil))))

(deftest normalize-class-accessor-fn-passes-through
  (createRoot
   (fn [_dispose]
     (testing "a fn :class passes through as the accessor"
       (let [a       (satom/atom "foo")
             [s lst] (#'h/normalize-class (fn [] @a))]
         (is (fn? s))
         (is (nil? lst))
         (is (= "foo" (s)))
         (reset! a "bar")
         (is (= "bar" (s)))))
     (testing "a bare atom is NOT interpreted — stringified like any value"
       (let [a     (satom/atom "foo")
             [s _] (#'h/normalize-class a)]
         (is (string? s))
         (is (re-find #"SAtom" s))))
     nil)))

(deftest normalize-class-map-with-accessor-values
  (createRoot
   (fn [_dispose]
     (testing "fn-valued classList entries pass through as accessors"
       (let [a         (satom/atom true)
             [s ^js lst] (#'h/normalize-class {:active (fn [] @a) :static true})]
         (is (nil? s))
         (is (some? lst))
         (is (true? (.-static lst)))
         (let [g (.-active lst)]
           (is (fn? g))
           (is (= true  (g)))
           (reset! a false)
           (is (= false (g))))))
     nil)))

(deftest normalize-props-shorthand-merging
  (let [^js out (#'h/normalize-props {:class "bar"} ["foo"] "main")]
    (is (= "foo bar" (.-class out)))
    (is (= "main"    (.-id out))))
  (testing "explicit :id wins over shorthand id"
    (let [^js out (#'h/normalize-props {:id "explicit"} [] "shorthand")]
      (is (= "explicit" (.-id out))))))

(deftest normalize-props-style-map
  (let [out (#'h/normalize-props {:style {:background-color "red"}} [] nil)]
    (is (= "red" (.. out -style -backgroundColor)))))

(deftest normalize-props-class-map-becomes-classlist
  (let [out (#'h/normalize-props {:class {:active true}} [] nil)]
    (is (true? (.. out -classList -active)))))

(deftest normalize-props-shorthand-only-when-no-class
  (let [^js out (#'h/normalize-props {} ["foo"] nil)]
    (is (= "foo" (.-class out)))))

(deftest normalize-props-passes-through-other-props
  (let [out (#'h/normalize-props {:value "x" :placeholder "p"} [] nil)]
    (is (= "x" (.-value out)))
    (is (= "p" (.-placeholder out)))))

(deftest normalize-props-shorthand-with-atom-class
  (createRoot
   (fn [_dispose]
     (testing "shorthand classes prepend onto a reactive class accessor"
       (let [a     (satom/atom "bar")
             ^js out (#'h/normalize-props {:class (fn [] @a)} ["foo"] nil)
             v     (.-class out)]
         (is (fn? v))
         (is (= "foo bar" (v)))
         (reset! a "baz")
         (is (= "foo baz" (v)))
         (reset! a "")
         (is (= "foo" (v))
             "empty atom value should NOT introduce a trailing space")
         (reset! a nil)
         (is (= "foo" (v))
             "nil atom value should also NOT introduce a trailing space")))
     nil)))

(deftest normalize-props-does-not-interpret-atom-valued-props
  (testing "a bare atom prop passes through as the object itself"
    (let [a   (satom/atom "initial")
          out (#'h/normalize-props {:value a} [] nil)]
      (is (identical? a (.-value out)))))
  (createRoot
   (fn [_dispose]
     (testing "an accessor fn prop reads live"
       (let [a   (satom/atom "initial")
             out (#'h/normalize-props {:value (fn [] @a)} [] nil)
             v   (.-value out)]
         (is (fn? v))
         (is (= "initial" (v)))
         (reset! a "updated")
         (is (= "updated" (v)))))
     nil)))

(deftest normalize-props-empty-class-string-with-shorthand
  (testing "explicit empty class string falls through to just shorthand"
    (let [^js out (#'h/normalize-props {:class ""} ["foo"] nil)]
      (is (= "foo" (.-class out))
          "should be \"foo\" not \"foo \""))))
