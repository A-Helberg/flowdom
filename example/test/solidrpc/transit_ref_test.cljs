(ns solidrpc.transit-ref-test
  "The client side of the generic Ref: zero configuration in either
  direction. Outgoing, a Ref writes under its own tag; incoming, any
  tag without a handler reads as a Ref."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [solidrpc.transit :as transit]))

(deftest generic-ref-round-trips
  (testing "outgoing: a Ref writes under its own tag"
    (let [r    (transit/ref "app/viewer" {:sid "abc"})
          wire (transit/write r)]
      (is (re-find #"app/viewer" wire))
      (is (= r (transit/read wire)) "…and reads back as itself")))
  (testing "incoming: an unregistered server tag arrives as a Ref"
    ;; a raw server emission — the client has no handler for solid/db
    (let [wire "[\"~#solid/db\",[\"^ \",\"~:basis-t\",1010]]"]
      (is (= (transit/ref "solid/db" {:basis-t 1010})
             (transit/read wire)))))
  (testing "refs are plain data: value equality, readable rep"
    (is (= (transit/ref "t" {:a 1}) (transit/ref "t" {:a 1})))
    (let [r (transit/read "[\"~#solid/db\",[\"^ \",\"~:basis-t\",1010]]")]
      (is (transit/ref? r))
      (is (= "solid/db" (transit/ref-tag r)))
      (is (= 1010 (:basis-t (transit/ref-rep r)))))))
