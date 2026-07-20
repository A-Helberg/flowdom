(ns frontend.notes-view-test
  "The full-stack purity test: the REAL pure component + REAL facade +
  REAL solidrpc.live + REAL in-memory Datomic, rendered by the JVM
  interpreter — zero HTTP, zero mocks. (The CLJS half of the facade is
  covered by frontend.notes-facade-test under node, and by running the
  actual server.)

  The Datomic conn is a shared defonce, so assertions are containment
  -based with unique note texts — tests must not assume an empty db."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [api.notes :as notes]
            [flowdom.core :as fd :refer [with-render snapshot]]
            [frontend.notes-view :as nv]
            [server.core :as core]
            [server.notes :as store]
            [solidrpc.transit :as transit]))

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
    (with-render [t [nv/notes-view nil]]            ;; nil = now
      (testing "initial render catches up to existing notes"
        (is (fd/await t #(some #{"hello from datomic"} (note-texts %)))))
      (testing "driving the UI through snapshot handlers"
        (let [snap (snapshot t)]
          ((prop snap :input :on-input) note)     ;; type (event-value passthrough)
          ((prop snap :button :on-click) :click)) ;; click Add → transact
        (is (fd/await t #(some #{note} (note-texts %)) :timeout 3000)
            "note came back through the tx-report stream")
        (is (= "" (prop (snapshot t) :input :value))
            "draft cleared after add")))))

(deftest irrelevant-transactions-do-not-touch-the-view
  (with-render [t [nv/notes-view nil]]
    (fd/await t #(some #{"hello from datomic"} (note-texts %)))
    (let [before (snapshot t)]
      ;; touches no :note/* attribute — note-tx? filters it before the
      ;; query even re-runs; nothing should change
      @(d/transact store/conn [{:db/ident (keyword "noise" (str (gensym)))}])
      (Thread/sleep 200)
      (is (= before (snapshot t))))))

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
    (with-render [t [nv/notes-view anchor]]
      (let [names (note-texts (fd/await t #(some #{after-anchor} (note-texts %))))]
        (is (some #{before-anchor} names))
        (is (some #{after-anchor} names) "caught up past the anchor")))))

(deftest db-value-round-trips-the-wire-as-a-token
  ;; the transit boundary: value → #solid/db {:basis-t t} → value,
  ;; using the same handler maps server.core supplies at the mount
  ;; point — with a real Datomic db value.
  (let [db0  (d/db store/conn)
        wire (transit/write db0 {:handlers (:write-handlers store/transit-handlers)})]
    (is (re-find #"solid/db" wire))
    (is (not (re-find #"hello from datomic" wire)) "no domain data crosses")
    (testing "without a resolver, the client's view: a generic token"
      (is (= (transit/token transit/db-tag {:basis-t (d/basis-t db0)})
             (transit/read wire))))
    (let [restored (transit/read wire {:handlers (:read-handlers store/transit-handlers)})]
      (is (= (d/basis-t db0) (d/basis-t restored)))
      (is (= (notes/all-notes db0) (notes/all-notes restored))
          "the resolved value answers queries identically"))))

(deftest command-returns-the-post-tx-db
  (testing "in-process: a real db value — anchor a read with it"
    (let [note (str "ryw-" (gensym))
          db1  (notes/add-note! note)]
      (is (some #{note} (notes/all-notes db1))
          "read-your-writes: the returned value already contains the write")))
  (testing "over the wire: the value leaves as a token (the write handler)"
    (let [note (str "ryw-wire-" (gensym))
          resp (core/command-handler
                {:body (java.io.StringReader.
                        (transit/write {:fn-name 'api.notes/add-note!
                                        :args    [note]}))})
          body (transit/read (:body resp))]   ;; no read handlers: a generic token
      (is (= 200 (:status resp)))
      (is (transit/token? (:result body)))
      (is (= transit/db-tag (transit/token-tag (:result body))))
      (is (pos? (:basis-t (transit/token-rep (:result body))))
          "a basis-t is all that crossed"))))

(deftest cancel-releases-the-flow-and-a-fresh-sample-remounts
  ;; `render` keeps the refcounted tree alive; :cancel releases its
  ;; standing subscriber, which cascades: the rx cells stop, the live
  ;; flow is cancelled, the report subscription is released. There is
  ;; no frozen tree afterwards — flowdom trees are flows, so a later
  ;; sample re-interprets from scratch (mount, sample, unmount) and
  ;; sees everything written while nothing was subscribed.
  (let [note   (str "after-cancel-" (gensym))
        handle (fd/render [nv/notes-view nil])]
    (fd/await handle #(some #{"hello from datomic"} (note-texts %)))
    ((:cancel handle))
    (notes/add-note! note)
    (is (fd/await handle #(some #{note} (note-texts %)))
        "a post-cancel sample is a fresh mount against current state")))
