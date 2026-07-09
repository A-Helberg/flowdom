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

(defn db "The current database value." [] @db*)

;; the browser twin of tx-report-flow: a discrete flow of tx-reports.
(def reports
  (m/eduction (remove nil?) (m/watch last-report)))

(defn transact! [datoms]
  (let [before @db*
        after  (reduce (fn [db [e a v]] (assoc-in db [e a] v))
                       before datoms)]
    (reset! db* after)
    (reset! last-report {:t         (swap! t* inc) ;; d/basis-t, at home
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
