(ns datomic.api
  "The browser stand-in for Datomic's peer API — same names, same
  shapes, backed by an atom — so demo code on this site reads exactly
  as it would in a real application. On the JVM this namespace is the
  real peer library; the cljs compiler resolves it here instead.

  Supported surface (what the demos use): create-database, connect
  (memoized per uri, like the real thing), db, basis-t, as-of,
  transact (deref-able, returns {:db-before :db-after :tx-data} with
  [e a v] tx-data). listen!/unlisten! back the tx-report-flow twin —
  the real peer's report queue has no browser equivalent.")

(defonce ^:private conns (atom {}))

(defn- db-value
  "A database value: the eid→entity map, with basis-t and the history
  needed by as-of riding along as metadata."
  [{:keys [data t history]}]
  (with-meta data {:basis-t t :history history}))

(defn create-database [uri]
  (if (contains? @conns uri)
    false
    (do (swap! conns assoc uri
               (atom {:data {} :t 1000 :eid 0
                      :history {1000 {}} :listeners {}}))
        true)))

(defn connect [uri]
  (or (get @conns uri)
      (do (create-database uri)
          (get @conns uri))))

(defn db [conn]
  (db-value @conn))

(defn basis-t [db]
  (:basis-t (meta db)))

(defn as-of
  "The database as of `t` — the greatest recorded basis ≤ t."
  [db t]
  (let [history (:history (meta db))
        at      (or (->> (keys history) (filter #(<= % t)) (apply max))
                    (apply min (keys history)))]
    (with-meta (get history at) {:basis-t at :history history})))

(defn- entity->datoms [eid m]
  (mapv (fn [[a v]] [eid a v]) (dissoc m :db/id)))

(defn transact
  "Entity maps in, deref-able report out — {:db-before :db-after
  :tx-data} with [e a v] tx-data, like the real report's datoms."
  [conn tx-data]
  (let [report
        (loop []
          (let [state  @conn
                before (db-value state)
                [data eid datoms]
                (reduce (fn [[data eid datoms] m]
                          (let [id (or (:db/id m) (inc eid))]
                            [(update data id merge (dissoc m :db/id))
                             (max eid id)
                             (into datoms (entity->datoms id m))]))
                        [(:data state) (:eid state) []]
                        tx-data)
                t      (inc (:t state))
                state' (-> state
                           (assoc :data data :t t :eid eid)
                           (update :history assoc t data))]
            (if (compare-and-set! conn state state')
              {:db-before before
               :db-after  (db-value state')
               :tx-data   datoms}
              (recur))))]
    (doseq [[_ f] (:listeners @conn)]
      (f report))
    (reify IDeref
      (-deref [_] report))))

(defn ^:no-doc listen! [conn k f]
  (swap! conn update :listeners assoc k f))

(defn ^:no-doc unlisten! [conn k]
  (swap! conn update :listeners dissoc k))
