(ns solidrpc.db-transit-test
  "The db-as-value serialization boundary, without Datomic: a fake db
  type stands in for datomic.db.Db. Write side: value → #solid/db
  {:basis-t t}. Read side: the app-registered resolver turns the ref
  back into an actual value; unregistered, the tag reads as a plain
  DbRef record."
  (:require [clojure.test :refer [deftest is testing]]
            [solidrpc.transit :as transit]))

(defrecord FakeDb [basis-t notes])

(def ^:private history
  {7 (->FakeDb 7 ["one"])
   9 (->FakeDb 9 ["one" "two"])})

(deftest db-value-round-trips-through-the-ref
  (transit/register-write-handler! FakeDb transit/db-tag
                                   (fn [db] {:basis-t (:basis-t db)}))
  (transit/register-read-handler! transit/db-tag
                                  (fn [{:keys [basis-t]}]
                                    (if basis-t
                                      (get history basis-t)
                                      (get history 9))))   ;; nil means now
  (testing "value → wire → value: the fn on the other side receives a db"
    (let [wire (transit/write {:fn-name 'api.notes/all-notes<
                               :args    [(get history 7)]})
          {:keys [args]} (transit/read wire)]
      (is (= (get history 7) (first args)))
      (is (= ["one"] (:notes (first args))) "resolved, queryable — not a ref")))
  (testing "the wire form is the tiny ref, not the data"
    (let [wire (transit/write (get history 9))]
      (is (re-find #"solid/db" wire))
      (is (not (re-find #"two" wire)) "no domain data crosses"))))
