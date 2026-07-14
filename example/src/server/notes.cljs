(ns server.notes
  "Browser twin of the real storage wiring (notes.clj, collapsed on
  the tx-listener page): the same conn, the same shared tx-reports
  stream, the same add-note! — plus ping!, standing in for any other
  domain writing to the same connection."
  (:require [datomic.api :as d]
            [missionary.core :as m]
            [server.tx-listener :as txl]))

(defonce conn (d/connect "datomic:mem://notes"))

;; ONE listener per connection, shared
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))

(defn add-note!
  "Returns the post-transaction db value."
  [text]
  (when (seq text)
    (:db-after @(d/transact conn [{:note/text text}]))))

(defonce ^:private pings* (atom 0))

(defn ping!
  "Any other write on the same connection — no :note/* attributes."
  []
  (d/transact conn [{:db/id 1 :app/pings (swap! pings* inc)}]))
