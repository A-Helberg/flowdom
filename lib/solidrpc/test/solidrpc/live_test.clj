(ns solidrpc.live-test
  "The live combinator against a fake store: an atom of history plays
  the database, a manually-driven m/observe plays the tx-report
  stream. Everything emits synchronously on the test thread, so no
  awaiting anywhere."
  (:require [clojure.test :refer [deftest is testing]]
            [missionary.core :as m]
            [solidrpc.live :as live]))

(defn- fake-store
  "A tiny tx-report-shaped store: history is a vector of db values
  (index = basis-t). transact! swaps the current db and emits a report
  {:db-before … :db-after … :tx-data …} to the (single) subscriber."
  []
  (let [history (atom [{:notes []}])
        emit*   (atom nil)]
    {:history   history
     :emit*     emit*
     :as-of     (fn [t] (nth @history t))
     :env       {:db      (fn [] (peek @history))
                 :reports (m/observe (fn [emit!]
                                       (reset! emit* emit!)
                                       (fn [] (reset! emit* nil))))}
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

(deftest anchor-at-now
  (let [{:keys [env transact!]} (fake-store)
        {:keys [out cancel]} (run-flow! (live/live env ((:db env)) :notes))]
    (is (= [[]] @out) "current answer at subscribe")
    (transact! #(add-note % "a") [[:note "a"]])
    (is (= [[] ["a"]] @out))
    (transact! #(add-note % "b") [[:note "b"]])
    (is (= [[] ["a"] ["a" "b"]] @out))
    (cancel)))

(deftest nil-anchor-passes-through-untouched
  ;; live is not slot-aware: the anchor is not inspected or coerced,
  ;; nil included. Coercing nil to 'now' would read (:db env) a second
  ;; time (substitute + catch-up); passthrough reads it exactly once.
  ;; (The anchor emission itself is unobservable here — latest-wins
  ;; supersedes it with the catch-up before a synchronous consumer
  ;; transfers, same as the stale-anchor case above.) A facade whose
  ;; convention is 'nil means now' substitutes before calling.
  (let [{:keys [env]} (fake-store)
        db-calls (atom 0)
        env      (update env :db (fn [f] (fn [] (swap! db-calls inc) (f))))
        {:keys [out cancel]} (run-flow! (live/live env nil :notes))]
    (is (= 1 @db-calls) "only the catch-up read — no nil coercion")
    (is (= [[]] @out) "the catch-up answer is what a consumer sees")
    (cancel)))

(deftest fresh-anchor-emits-once
  (let [{:keys [env as-of transact!]} (fake-store)]
    (transact! #(add-note % "a") [[:note "a"]])
    (let [{:keys [out cancel]} (run-flow! (live/live env (as-of 1) :notes))]
      (is (= [["a"]] @out)
          "anchor == current: the catch-up dedupes away")
      (cancel))))

(deftest stale-anchor-catches-up
  (let [{:keys [env as-of transact!]} (fake-store)]
    (transact! #(add-note % "a") [[:note "a"]])   ;; t1
    (let [anchor (as-of 1)]
      (transact! #(add-note % "b") [[:note "b"]]) ;; t2 — anchor now stale
      (let [{:keys [out cancel]} (run-flow! (live/live env anchor :notes))]
        ;; the anchor is a LOWER BOUND, not an observable first frame:
        ;; latest-wins (m/relieve) collapses anchor + catch-up before
        ;; the first transfer, so the consumer sees the freshest
        ;; answer directly — and never anything older than the anchor
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
  (let [{:keys [env transact!]} (fake-store)
        calls (atom 0)
        notes-q (fn [db] (swap! calls inc) (:notes db))
        {:keys [out cancel]} (run-flow! (live/live env ((:db env)) notes-q))]
    (is (= [[]] @out))
    (is (= 1 @calls) "anchor + catch-up collapse to one db under latest-wins")
    ;; a transaction that changes the db but NOT this query's answer
    (transact! #(assoc % :pings 1) [[:ping 1]])
    (is (= [[]] @out) "no emission — dedupe suppressed it")
    (is (= 2 @calls) "…but the query DID re-run (no :relevant? given)")
    (transact! #(add-note % "a") [[:note "a"]])
    (is (= [[] ["a"]] @out))
    (cancel)))

(deftest relevant?-skips-the-requery
  (let [{:keys [env transact!]} (fake-store)
        calls (atom 0)
        notes-q (fn [db] (swap! calls inc) (:notes db))
        note-tx? (fn [report] (some #(= :note (first %)) (:tx-data report)))
        {:keys [out cancel]} (run-flow! (live/live env ((:db env)) notes-q :relevant? note-tx?))]
    (let [calls-at-start @calls]
      (transact! #(assoc % :pings 1) [[:ping 1]])
      (is (= calls-at-start @calls) "irrelevant tx: query not re-run at all")
      (is (= [[]] @out))
      (transact! #(add-note % "a") [[:note "a"]])
      (is (= (inc calls-at-start) @calls))
      (is (= [[] ["a"]] @out)))
    (cancel)))

(deftest cancellation-releases-the-report-subscription
  (let [{:keys [env emit*]} (fake-store)
        {:keys [cancel]} (run-flow! (live/live env ((:db env)) :notes))]
    (is (some? @emit*) "live flow subscribed to reports")
    (cancel)
    (is (nil? @emit*) "cancel released the m/observe subscription")))

(deftest missing-env-throws-eagerly
  (is (thrown? clojure.lang.ExceptionInfo
               (live/live {:db (fn [] {})} nil identity))))
