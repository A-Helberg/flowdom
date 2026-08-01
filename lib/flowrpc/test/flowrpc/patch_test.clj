(ns flowrpc.patch-test
  (:require [clojure.test :refer [deftest is are]]
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
