(ns server.tx-listener
  "Datomic's tx-report-queue as a missionary flow.

  Peers learn about transactions by pulling from a BlockingQueue
  (d/tx-report-queue) — and pulling is exactly missionary's model, so
  the listener needs no adapter thread: park on the blocking take,
  emit, repeat.

  One Datomic caveat shapes everything here: a connection has ONE
  report queue, and concurrent takers steal reports from each other.
  So run `tx-report-flow` once per connection and share the running
  listener — (m/stream (tx-report-flow conn)) — as server.notes does."
  (:require
   [datomic.api :as d]
   [missionary.core :as m])
  (:import
   [java.util.concurrent BlockingQueue]))

(defn tx-report-flow
  "A discrete flow of tx-reports from `conn` — each a map of
  :db-before, :db-after and :tx-data (the datoms of that transaction).

  A flow is a recipe: nothing attaches to the connection until a
  consumer runs this. Spawning registers the report queue; cancelling
  deregisters it — a queue nobody drains would accumulate reports
  forever."
  [conn]
  (m/ap
    ;; m/observe is the acquire/release bracket: it emits the queue
    ;; once at spawn, and its cleanup thunk runs exactly once at
    ;; cancel. (A try/finally around the loop below would NOT work:
    ;; m/amb forks the process and `finally` runs once per fork —
    ;; detaching the queue right after the first report.)
   (let [^BlockingQueue queue
         (m/?> (m/observe (fn [emit!]
                            (emit! (d/tx-report-queue conn))
                            #(d/remove-tx-report-queue conn))))]
     (loop []
        ;; .take blocks, so park on the blocking executor; a
        ;; cancelled run interrupts the take and unwinds the loop.
       (m/amb (m/? (m/via m/blk (.take queue)))
              (recur))))))

;; db-flow and q-flow moved to solidrpc.live, generalized: the same
;; composition is now (live/live env db-ref f), storage-agnostic and
;; with pinned (as-of) reads and :relevant? filtering. See
;; server.notes for the wiring.
