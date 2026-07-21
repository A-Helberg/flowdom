(ns flowrpc.transit-test
  (:require [clojure.test :refer [deftest is are]]
            [flowrpc.transit :as transit]))

(deftest roundtrip
  (are [v] (= v (transit/read (transit/write v)))
    {:fn-name "api.clock/time-flow" :args []}
    {:fn-name 'api.clock/time-flow :args [1 "two" :three]}
    [1 2 3]
    {:nested {:a 1 :b [2 3]}}
    "plain string"
    42
    nil))

(deftest symbol-survives
  ;; Transit encodes symbols as ~$ — critical for fn-name dispatch.
  (let [sym 'api.my-domain/things-flow]
    (is (= sym (transit/read (transit/write sym))))))
