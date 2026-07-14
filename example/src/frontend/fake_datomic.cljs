(ns frontend.fake-datomic
  "A stand-in for the Datomic side of server.tx-listener so this page
  can run on a static site — same shapes, no JVM. A map of eid →
  entity plays the database, transact! applies [e a v] datoms and
  publishes a report shaped like the real thing ({:db-before …
  :db-after … :tx-data …}), and `reports` plays the shared
  tx-report-flow — here an m/watch on the 'queue', since a browser
  button can't block a thread."
  (:require [missionary.core :as m]))

(defonce ^:private db*         (atom {1 {:note/text "first note"}}))
(defonce ^:private last-report (atom nil))
(defonce ^:private t*          (atom 1000))
(defonce ^:private eid*        (atom 1))
;; every db value ever, keyed by basis-t — what lets `as-of` hand out
;; immutable as-of views, Datomic's superpower faked in one map
(defonce ^:private history*    (atom {1000 {1 {:note/text "first note"}}}))

(defn db "The current database value." [] @db*)

(defn basis-t "The t of the current database value." [] @t*)

(defn as-of "The database value as-of `t` — immutable, forever." [t]
  (get @history* t))

;; the browser twin of tx-report-flow: a discrete flow of tx-reports.
(def reports
  (m/eduction (remove nil?) (m/watch last-report)))

(def env
  "A solidrpc.live env over this fake — the same two keys the real
  server wires from Datomic (see server.notes/env)."
  {:db      db
   :reports reports})

(defn transact! [datoms]
  (let [before @db*
        after  (reduce (fn [db [e a v]] (assoc-in db [e a] v))
                       before datoms)
        t      (swap! t* inc)] ;; d/basis-t, at home
    (reset! db* after)
    (swap! history* assoc t after)
    (reset! last-report {:t         t
                         :db-before before
                         :db-after  after
                         :tx-data   datoms})))

(defn add-note! []
  (let [e (swap! eid* inc)]
    (transact! [[e :note/text (str "note " e)]])))

(defn ping!
  "A transaction that touches nothing note-related."
  []
  (transact! [[0 :app/pings (inc (get-in @db* [0 :app/pings] 0))]]))
