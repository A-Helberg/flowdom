(ns frontend.notes-view-test
  "The full-stack purity test: the REAL pure component + REAL facade +
  REAL solidrpc.live + REAL in-memory Datomic, rendered in the JVM
  runtime — zero HTTP, zero mocks. (The CLJS half of the facade is
  covered by frontend.notes-facade-test under node, and by running the
  actual server.)

  The Datomic conn is a shared defonce, so assertions are containment
  -based with unique note texts — tests must not assume an empty db."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [api.notes :as notes]
            [frontend.notes-view :as nv]
            [server.notes :as store]
            [solidclj.api :as s]
            [solidclj.hiccup :as hic]
            [solidrpc.transit :as transit]))

(defn- await-until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

(defn- els [snap tag]
  (->> (tree-seq vector? seq snap)
       (filter #(and (vector? %) (= tag (first %))))))

(defn- note-texts [snap] (map last (els snap :li)))

(defn- prop [snap tag k] (-> (els snap tag) first second k))

;; ---------------------------------------------------------------------------

(deftest facade-is-lazy-and-pure-on-the-jvm
  ;; constructing the read runs nothing: a flow is a recipe — no
  ;; query, no report-queue subscription, until something subscribes
  (is (fn? (notes/all-notes< nil))))

(deftest live-ui-roundtrip
  (let [note (str "roundtrip-" (gensym))]
    (hic/with-render [t [nv/notes-view nil]]        ;; nil = now
      (testing "initial render shows existing notes"
        (is (some #{"hello from datomic"} (note-texts (s/snapshot t)))))
      (testing "driving the UI through snapshot handlers"
        (let [snap (s/snapshot t)]
          ((prop snap :input :onInput) note)     ;; type (event-value passthrough)
          ((prop snap :button :onClick) :click)) ;; click Add → transact
        (is (await-until #(some #{note} (note-texts (s/snapshot t))) 3000)
            "note came back through the tx-report stream")
        (is (= "" (prop (s/snapshot t) :input :value))
            "draft cleared after add")))))

(deftest irrelevant-transactions-do-not-touch-the-view
  (hic/with-render [t [nv/notes-view nil]]
    (let [before (s/snapshot t)]
      ;; touches no :note/* attribute — note-tx? filters it before the
      ;; query even re-runs; nothing should change
      @(d/transact store/conn [{:db/ident (keyword "noise" (str (gensym)))}])
      (Thread/sleep 200)
      (is (= before (s/snapshot t))))))

(deftest as-of-views-are-plain-function-calls
  ;; no pinned flow, no render lifecycle: as-of views are immutable
  ;; values, so 'the answer at t' is function application
  (let [marker (str "pre-asof-" (gensym))]
    (notes/add-note! marker)
    (let [db0   (d/db store/conn)
          at-t0 (notes/all-notes db0)
          later (str "post-asof-" (gensym))]
      (notes/add-note! later)
      (is (some #{marker} at-t0))
      (is (not (some #{later} at-t0)) "the value was captured before the tx")
      (testing "same value, same answer — forever"
        (is (= at-t0 (notes/all-notes db0))))
      (testing "and as-of reconstructs it from just a t"
        (is (= at-t0 (notes/all-notes
                      (d/as-of (d/db store/conn) (d/basis-t db0)))))))))

(deftest anchored-render-catches-up
  ;; an anchor is a lower bound: rendering against an old value shows
  ;; the current answer (the catch-up), never less than the anchor
  (let [before-anchor (str "before-anchor-" (gensym))
        _      (notes/add-note! before-anchor)
        anchor (d/db store/conn)
        after-anchor (str "after-anchor-" (gensym))
        _      (notes/add-note! after-anchor)]
    (hic/with-render [t [nv/notes-view anchor]]
      (let [names (note-texts (s/snapshot t))]
        (is (some #{before-anchor} names))
        (is (some #{after-anchor} names) "caught up past the anchor")))))

(deftest db-value-round-trips-the-wire-as-a-ref
  ;; the transit boundary: value → #solid/db {:basis-t t} → value,
  ;; using the same handler maps server.core supplies at the mount
  ;; point — with a real Datomic db value.
  (let [db0  (d/db store/conn)
        wire (transit/write db0 {:handlers (:write-handlers store/transit-handlers)})]
    (is (re-find #"solid/db" wire))
    (is (not (re-find #"hello from datomic" wire)) "no domain data crosses")
    (testing "without a resolver, the client's view: a generic ref"
      (is (= (transit/ref transit/db-tag {:basis-t (d/basis-t db0)})
             (transit/read wire))))
    (let [restored (transit/read wire {:handlers (:read-handlers store/transit-handlers)})]
      (is (= (d/basis-t db0) (d/basis-t restored)))
      (is (= (notes/all-notes db0) (notes/all-notes restored))
          "the resolved value answers queries identically"))))

(deftest dispose-freezes-the-tree-and-releases-the-flow
  (let [note   (str "after-dispose-" (gensym))
        handle (s/render [nv/notes-view nil])
        before (s/snapshot handle)]
    ((:dispose handle))
    ;; the hold released its last subscriber → flow cancelled (the
    ;; solidrpc.live tests assert the report unsubscription directly;
    ;; here we observe the consumer side: the tree is frozen)
    (notes/add-note! note)
    (Thread/sleep 300)
    (is (= before (s/snapshot handle)) "no updates after dispose")
    (is (not (some #{note} (note-texts (s/snapshot handle)))))))
