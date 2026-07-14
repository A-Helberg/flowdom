(ns solidrpc.db-transit-test
  "The db-as-value serialization boundary, without Datomic: a fake db
  type stands in for datomic.db.Db. Write side: value → #solid/db
  {:basis-t t}. Read side: a resolver turns the ref back into an
  actual value; without one, the tag reads as a generic Ref record.
  Handlers are supplied per call, the way a consumer supplies them at
  the rpc mount point — the global registry stays untouched."
  (:require [clojure.test :refer [deftest is testing]]
            [solidrpc.transit :as transit]))

(defrecord FakeDb [basis-t notes])

(def ^:private history
  {7 (->FakeDb 7 ["one"])
   9 (->FakeDb 9 ["one" "two"])})

(def ^:private db-out
  {FakeDb {:tag transit/db-tag :rep (fn [db] {:basis-t (:basis-t db)})}})

(def ^:private db-in
  {transit/db-tag (fn [{:keys [basis-t]}]
                    (if basis-t
                      (get history basis-t)
                      (get history 9)))})   ;; nil means now

(deftest db-value-round-trips-through-the-ref
  (testing "value → wire → value: the fn on the other side receives a db"
    (let [wire (transit/write {:fn-name 'api.notes/all-notes<
                               :args    [(get history 7)]}
                              {:handlers db-out})
          {:keys [args]} (transit/read wire {:handlers db-in})]
      (is (= (get history 7) (first args)))
      (is (= ["one"] (:notes (first args))) "resolved, queryable — not a ref")))
  (testing "the wire form is the ref, not the data"
    (let [wire (transit/write (get history 9) {:handlers db-out})]
      (is (re-find #"solid/db" wire))
      (is (not (re-find #"two" wire)) "no domain data crosses")))
  (testing "without a resolver, the tag reads as a generic Ref"
    (let [wire (transit/write (get history 7) {:handlers db-out})]
      (is (= (transit/ref transit/db-tag {:basis-t 7}) (transit/read wire))))))
