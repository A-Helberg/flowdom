(ns solidrpc.live-test
  "The live combinator against a fake store: an atom of history plays
  the database, a manually-driven m/observe plays the tx-report
  stream, and a last-report atom gives the feed its catch-up head —
  the reports< contract. Everything emits synchronously on the test
  thread, so no awaiting anywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [missionary.core :as m]
            [solidrpc.live :as live]))

(defn- fake-store
  "A tiny tx-report-shaped store: history is a vector of db values
  (index = basis-t). transact! swaps the current db and emits a
  report to the (single) live subscriber. :reports< is the contract
  flow: a catch-up head — the current db at spawn, no datoms — then
  every new report."
  []
  (let [history (atom [{:notes []}])
        emit*   (atom nil)]
    {:history   history
     :emit*     emit*
     :as-of     (fn [t] (nth @history t))
     :reports<  (m/ap (m/amb {:db-after (peek @history) :tx-data []}
                             (m/?> (m/observe (fn [emit!]
                                                (reset! emit* emit!)
                                                (fn [] (reset! emit* nil)))))))
     :transact! (fn [update-fn tx-data]
                  (let [before (peek @history)
                        after  (update-fn before)]
                    (swap! history conj after)
                    (when-some [emit! @emit*]
                      (emit! {:db-before before :db-after after :tx-data tx-data}))))}))

(defn- run-flow!
  "Runs a flow, collecting emissions/termination synchronously.
  Returns {:out (atom [...]) :cancel fn}."
  [flow]
  (let [out    (atom [])
        cancel ((m/reduce (fn [_ v] (swap! out conj v) nil) nil flow)
                (fn [_] (swap! out conj ::done))
                (fn [e] (swap! out conj [::failed (ex-message e)])))]
    {:out out :cancel cancel}))

(defn- add-note [db text] (update db :notes conj text))

;; ---------------------------------------------------------------------------

(deftest head-is-the-present
  ;; no anchor: the feed's head report supplies the current answer at
  ;; subscribe, then reports drive it
  (let [{:keys [reports< transact!]} (fake-store)
        {:keys [out cancel]} (run-flow! (live/live reports< nil :notes))]
    (is (= [[]] @out) "current answer at subscribe — from the head")
    (transact! #(add-note % "a") [[:note "a"]])
    (is (= [[] ["a"]] @out))
    (transact! #(add-note % "b") [[:note "b"]])
    (is (= [[] ["a"] ["a" "b"]] @out))
    (cancel)))

(deftest nil-anchor-means-no-floor
  ;; nil is not coerced or passed to f — there is simply no floor
  ;; emission; the head already supplies the present
  (let [{:keys [reports< transact!]} (fake-store)
        calls (atom 0)
        notes-q (fn [db] (swap! calls inc) (:notes db))]
    (transact! #(add-note % "a") [[:note "a"]])
    (let [{:keys [out cancel]} (run-flow! (live/live reports< nil notes-q))]
      (is (= [["a"]] @out) "one emission: the head's answer")
      (is (= 1 @calls) "f ran once — never on nil")
      (cancel))))

(deftest fresh-anchor-emits-once
  (let [{:keys [reports< as-of transact!]} (fake-store)]
    (transact! #(add-note % "a") [[:note "a"]])
    (let [{:keys [out cancel]} (run-flow! (live/live reports< (as-of 1) :notes))]
      (is (= [["a"]] @out)
          "anchor == current: the head dedupes away")
      (cancel))))

(deftest stale-anchor-catches-up
  (let [{:keys [reports< as-of transact!]} (fake-store)]
    (transact! #(add-note % "a") [[:note "a"]])   ;; t1
    (let [anchor (as-of 1)]
      (transact! #(add-note % "b") [[:note "b"]]) ;; t2 — anchor now stale
      (let [{:keys [out cancel]} (run-flow! (live/live reports< anchor :notes))]
        ;; the anchor is a LOWER BOUND, not an observable first frame:
        ;; latest-wins (m/relieve) collapses anchor + head before the
        ;; first transfer, so the consumer sees the freshest answer
        ;; directly — and never anything older than the anchor
        (is (= [["a" "b"]] @out))
        (cancel)))))

(deftest as-of-views-are-plain-function-calls
  ;; no pinned mode, no flow: as-of views are immutable values, so
  ;; 'the answer at t' is function application
  (let [{:keys [as-of transact!]} (fake-store)]
    (transact! #(add-note % "one") [[:note "one"]])
    (transact! #(add-note % "two") [[:note "two"]])
    (is (= ["one"] (:notes (as-of 1))))
    (testing "same value, same answer — forever"
      (transact! #(add-note % "three") [[:note "three"]])
      (is (= ["one"] (:notes (as-of 1)))))))

(deftest live-dedupes-unchanged-answers
  (let [{:keys [reports< as-of transact!]} (fake-store)
        calls (atom 0)
        notes-q (fn [db] (swap! calls inc) (:notes db))
        {:keys [out cancel]} (run-flow! (live/live reports< (as-of 0) notes-q))]
    (is (= [[]] @out))
    (is (= 1 @calls) "anchor + head collapse to one db under latest-wins")
    ;; a transaction that changes the db but NOT this query's answer
    (transact! #(assoc % :pings 1) [[:ping 1]])
    (is (= [[]] @out) "no emission — dedupe suppressed it")
    (is (= 2 @calls) "…but the query DID re-run (no :relevant? given)")
    (transact! #(add-note % "a") [[:note "a"]])
    (is (= [[] ["a"]] @out))
    (cancel)))

(deftest relevant?-skips-the-requery
  (let [{:keys [reports< transact!]} (fake-store)
        calls (atom 0)
        notes-q (fn [db] (swap! calls inc) (:notes db))
        note-tx? (fn [report] (some #(= :note (first %)) (:tx-data report)))
        {:keys [out cancel]} (run-flow! (live/live reports< nil notes-q :relevant? note-tx?))]
    (let [calls-at-start @calls]
      (transact! #(assoc % :pings 1) [[:ping 1]])
      (is (= calls-at-start @calls) "irrelevant tx: query not re-run at all")
      (is (= [[]] @out))
      (transact! #(add-note % "a") [[:note "a"]])
      (is (= (inc calls-at-start) @calls))
      (is (= [[] ["a"]] @out)))
    (cancel)))

(deftest the-head-is-exempt-from-relevant?
  ;; catch-up is unconditional: a subscriber arriving right after an
  ;; IRRELEVANT transaction must still reach the present, or a stale
  ;; anchor would show until the next relevant write happens to land
  (let [{:keys [reports< as-of transact!]} (fake-store)
        note-tx? (fn [report] (some #(= :note (first %)) (:tx-data report)))]
    (transact! #(add-note % "a") [[:note "a"]])   ;; t1
    (transact! #(add-note % "b") [[:note "b"]])   ;; t2
    (transact! #(assoc % :pings 1) [[:ping 1]])   ;; t3 — latest is irrelevant
    (let [{:keys [out cancel]}
          (run-flow! (live/live reports< (as-of 1) :notes :relevant? note-tx?))]
      (is (= [["a" "b"]] @out)
          "the head (no datoms — irrelevant by note-tx?) still carried the catch-up")
      (cancel))))

(deftest cancellation-releases-the-report-subscription
  (let [{:keys [reports< emit*]} (fake-store)
        {:keys [cancel]} (run-flow! (live/live reports< nil :notes))]
    (is (some? @emit*) "live flow subscribed to reports")
    (cancel)
    (is (nil? @emit*) "cancel released the m/observe subscription")))

(deftest missing-reports-flow-throws-eagerly
  (is (thrown? clojure.lang.ExceptionInfo
               (live/live nil nil identity))))
