(ns server.tx-listener
  "Browser twin of the real listener (tx_listener.clj, inlined on the
  tx-listener page): same fn, same flow of {:db-before :db-after
  :tx-data} reports — minus the blocking queue this platform can't
  have."
  (:require [datomic.api :as d]
            [missionary.core :as m]))

(defn tx-report-flow
  "A discrete flow of tx-reports from `conn`."
  [conn]
  (m/observe
   (fn [emit!]
     (let [k (gensym "tx-report-flow-")]
       (d/listen! conn k emit!)
       #(d/unlisten! conn k)))))
