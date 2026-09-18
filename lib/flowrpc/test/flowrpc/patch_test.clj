(ns flowrpc.patch-test
  (:require [clojure.test :refer [deftest is are]]
            [editscript.core :as e]
            [flowrpc.patch :as patch]))

(defn roundtrip [a b]
  (patch/apply-patch a (patch/diff a b)))

(deftest diff-apply-roundtrip
  (are [a b] (= b (roundtrip a b))
    {}                    {:a 1}
    {:a 1}                {:a 2}
    {:a 1 :b 2}           {:a 1 :b 3 :c 4}
    {:a 1 :b 2}           {:a 1}
    [1 2 3]               [1 2 4]
    [1 2 3]               [1 2 3 4]
    {:nested {:a 1}}      {:nested {:a 2 :b 3}}
    {:items [{:id 1 :v "a"} {:id 2 :v "b"}]}
    {:items [{:id 1 :v "a"} {:id 2 :v "c"}]}))

(deftest no-change-produces-empty-diff
  (let [v {:a 1 :b [1 2 3]}]
    (is (= v (roundtrip v v)))))

(deftest diff-is-serializable
  ;; Diffs must be plain Clojure data so transit can carry them.
  (let [edits (patch/diff {:a 1} {:a 2 :b 3})]
    (is (vector? edits))
    (is (every? vector? edits))))

(deftest defaults-do-not-use-a-star
  (let [diff e/diff
        opts (atom nil)]
    (with-redefs [e/diff (fn [a b options]
                          (reset! opts options)
                          (diff a b options))]
      (is (= [3 2 1] (roundtrip [1 2 3] [3 2 1])))
      (is (= {:algo :quick :vec-timeout 10} @opts)))))

(deftest collection-and-nil-roundtrips
  (doseq [algo [:quick :a-star]
          [a b] [[nil {:a 1}] [{:a 1} nil] [nil nil]
                 [#{1 2} #{2 3}] ['(1 2 3) '(3 1)]
                 [[{:id 1} {:id 2}] [{:id 2} {:id 1}]]]]
    (is (= b (patch/apply-patch a (patch/diff a b {:algo algo}))))))
