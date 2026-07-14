(ns api.txes
  "The tx-feed api namespace — browser twin of the .cljc shown on the
  tx-listener page, talking to the stand-ins directly."
  (:require [datomic.api :as d]
            [missionary.core :as m]
            [server.notes :as notes]))

(defn reports
  "The tx-report feed so far — shaped for the wire, no db values."
  []
  (->> notes/tx-reports
       (m/eduction (map (fn [{:keys [db-after tx-data]}]
                          {:t (d/basis-t db-after)
                           :tx-data tx-data})))
       (m/reductions conj [])))

(defn add-note! [text]
  (notes/add-note! text))

(defn ping! []
  (notes/ping!))
