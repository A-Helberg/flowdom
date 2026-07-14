(ns solidrpc.transit-token-test
  "The client side of the generic token: zero configuration in either
  direction. Outgoing, a token writes under its own tag; incoming, any
  tag without a handler reads as a token."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [solidrpc.transit :as transit]))

(deftest generic-token-round-trips
  (testing "outgoing: a token writes under its own tag"
    (let [r    (transit/token "app/viewer" {:sid "abc"})
          wire (transit/write r)]
      (is (re-find #"app/viewer" wire))
      (is (= r (transit/read wire)) "…and reads back as itself")))
  (testing "incoming: an unregistered server tag arrives as a token"
    ;; a raw server emission — the client has no handler for solid/db
    (let [wire "[\"~#solid/db\",[\"^ \",\"~:basis-t\",1010]]"]
      (is (= (transit/token "solid/db" {:basis-t 1010})
             (transit/read wire)))))
  (testing "tokens are plain data: value equality, readable rep"
    (is (= (transit/token "t" {:a 1}) (transit/token "t" {:a 1})))
    (let [r (transit/read "[\"~#solid/db\",[\"^ \",\"~:basis-t\",1010]]")]
      (is (transit/token? r))
      (is (= "solid/db" (transit/token-tag r)))
      (is (= 1010 (:basis-t (transit/token-rep r))))
      (is (= 1010 (get-in r [:rep :basis-t]))
          "lookup paths read the same as the JVM record"))))
