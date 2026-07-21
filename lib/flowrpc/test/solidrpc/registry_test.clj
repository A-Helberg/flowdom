(ns flowrpc.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [flowrpc.registry :as registry]))

(defn test-fn-a [] :a)
(defn test-fn-b [x] x)
(defn not-api-fn [] :nope)

;; Reset the registry around each test so state doesn't leak.
(use-fixtures :each
  (fn [f]
    (with-redefs [registry/registry (atom {})]
      (f))))

(deftest register-and-lookup
  (registry/register! #'test-fn-a)
  (is (= #'test-fn-a (registry/lookup "flowrpc.registry-test/test-fn-a"))))

(deftest lookup-returns-nil-for-unregistered
  (is (nil? (registry/lookup "flowrpc.registry-test/test-fn-a"))))

(deftest lookup-returns-nil-for-unknown-name
  (registry/register! #'test-fn-a)
  (is (nil? (registry/lookup "does.not/exist"))))

(deftest registered-fn-is-callable
  (registry/register! #'test-fn-b)
  (let [v (registry/lookup "flowrpc.registry-test/test-fn-b")]
    (is (= 42 (v 42)))))

(deftest register-requires-var
  (is (thrown? AssertionError (registry/register! test-fn-a))))
