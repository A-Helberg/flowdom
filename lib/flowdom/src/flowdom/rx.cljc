(ns flowdom.rx
  "The kernel: `rx` blocks and the universal read `?`.

  An rx block delimits a restartable computation. Inside it, `?` reads
  the current value of a reactive source — a Clojure atom, a missionary
  flow, or another rx — and records the dependency. When any recorded
  dependency changes, the block re-runs from scratch and the rx emits
  the new result (deduplicated with `=`).

  The result of `rx` is a shared missionary continuous flow (wrapped in
  the Rx type so renderers can recognize dynamic positions). It is lazy:
  nothing runs until a consumer subscribes, every subscriber shares one
  running computation (m/signal), and cancelling the last subscriber
  tears everything down — dependency subscriptions included. There is
  no manual disposal anywhere.

  Pending and errors travel as values, not control flow:
  - reading a source that has no value yet aborts the run and the rx
    emits the `pending` marker; it re-runs when the source first emits.
    Reading an rx that is currently pending propagates pending.
  - a body that throws (or a dependency that failed) makes the rx emit
    an `Err` record; reading an errored rx re-throws, so errors travel
    upward until something (an error boundary) catches them.

  Concurrency: on the JVM a dependency may emit from any thread; runs
  of one rx are serialized by a per-rx lock and propagation happens
  synchronously on the emitting thread. In CLJS everything is
  single-threaded and synchronous."
  #?(:cljs (:require-macros [flowdom.rx]))
  (:require [missionary.core :as m]))

;; ---------------------------------------------------------------------------
;; markers

(def pending
  "Value an rx emits while one of its dependencies has no value yet."
  ::pending)

;; = not identical?: CLJS dev builds don't intern keyword literals as
;; single instances, so identity comparison on markers silently fails
(defn pending-value? [v] (= v ::pending))

(def ^:private none
  "Cell sentinel for 'no value yet' — an opaque singleton, so no user
  value can collide with it."
  #?(:clj (Object.) :cljs (js-obj)))

(defrecord Err [error])

(defn err? [v] (instance? Err v))

;; ---------------------------------------------------------------------------
;; the Rx type — a missionary flow the renderer can recognize

(deftype Rx [flow]
  #?@(:clj  [clojure.lang.IFn
             (invoke [_ n t] (flow n t))]
      :cljs [IFn
             (-invoke [_ n t] (flow n t))]))

(defn rx? [x] (instance? Rx x))

(defn unwrap
  "The raw missionary flow inside an Rx (identity on anything else)."
  [x]
  (if (rx? x) (.-flow ^Rx x) x))

;; ---------------------------------------------------------------------------
;; tracking context

(def ^:dynamic *ctx* nil)

(def ^:private pending-tag ::pending-signal)

(defn- pending-ex []
  (ex-info "flowdom: dependency pending" {pending-tag true}))

(defn pending-ex?
  "Is `e` the internal control-flow exception that aborts a run because
  a dependency has no value yet? Error boundaries must re-throw these."
  [e]
  (boolean (and (ex-data e) (get (ex-data e) pending-tag))))

(defn- locked
  "Run `f` under `lock` on the JVM (runs are serialized per rx);
  single-threaded CLJS just runs it."
  [lock f]
  #?(:clj  (locking lock (f))
     :cljs (f)))

(defn- atom-like? [x]
  #?(:clj  (instance? clojure.lang.IRef x)
     :cljs (satisfies? IWatchable x)))

(declare run-rx!)

(defn- make-atom-cell [ctx src]
  (let [k #?(:clj (Object.) :cljs (js-obj))]
    (add-watch src k (fn [_ _ o n] (when (not= o n) (run-rx! ctx))))
    {:value #(deref src)
     :stop! #(remove-watch src k)}))

(defn- make-flow-cell [ctx src]
  (let [state   (atom none)
        stopped (atom false)
        cancel  ((m/reduce (fn [_ v]
                             (reset! state v)
                             (run-rx! ctx)
                             nil)
                           nil (unwrap src))
                 (fn [_] nil) ;; completed: keep serving the last value
                 (fn [e]
                   (when-not @stopped
                     (reset! state (->Err e))
                     (run-rx! ctx))))]
    {:value #(deref state)
     :stop! (fn [] (reset! stopped true) (cancel))}))

(defn- ensure-cell! [ctx src]
  (or (get @(:cells ctx) src)
      (let [cell (if (atom-like? src)
                   (make-atom-cell ctx src)
                   (if (ifn? src)
                     (make-flow-cell ctx src)
                     (throw (ex-info "flowdom: ? expects an atom, a flow, or an rx"
                                     {:got src}))))]
        (swap! (:cells ctx) assoc src cell)
        (swap! (:fresh ctx) conj src)
        cell)))

(defn ?
  "Read a reactive source inside an rx block: returns its current value
  and subscribes the block to it. Works at any call depth — helper
  functions called from the block read freely. Accepts atoms (anything
  IWatchable), missionary flows, and rx values.

  Subscriptions are keyed by the SOURCE OBJECT's identity, and the
  block's lifetime rules follow from that:

  - A flow built inside the body is a NEW source on every re-run: its
    old subscription is cancelled and its work restarts (connections
    reopen, timers restart). Build the flow once, outside the body,
    and read that one value — memoize by args when parameterized.
    Dev runs of this pattern trigger the churn warning below.
  - A run that doesn't read a source unsubscribes it, so a cold flow
    read in a conditional branch stops when the branch flips away and
    restarts from scratch when it flips back. That laziness is usually
    what you want; to keep a subscription alive across branches, read
    the flow through an outer rx bound outside the conditional — rx
    blocks are shared, so the outer rx holds the one subscription."
  [src]
  (let [ctx *ctx*]
    (when (nil? ctx)
      (throw (ex-info "flowdom: ? called outside an rx block" {:src src})))
    (let [cell (ensure-cell! ctx src)]
      (swap! (:used ctx) conj src)
      (let [v ((:value cell))]
        (cond
          (identical? v none)   (throw (pending-ex))
          (pending-value? v)    (throw (pending-ex))
          (err? v)              (throw (:error v))
          :else v)))))

(defn- gc-cells!
  "Unsubscribe every cell the last run did not read. Sources are re-read
  on every run, so conditional branches subscribe only what they use.
  Returns the srcs that were dropped."
  [ctx]
  (let [used @(:used ctx)]
    (reduce-kv (fn [dropped src cell]
                 (if (contains? used src)
                   dropped
                   (do (swap! (:cells ctx) dissoc src)
                       ((:stop! cell))
                       (conj dropped src))))
               []
               @(:cells ctx))))

;; ---------------------------------------------------------------------------
;; churn detection — the build-a-flow-inside-the-body footgun
;;
;; ? keys subscriptions by object identity, so a flow constructed in
;; the rx body is a brand-new dependency every run: each re-run drops
;; the old cell (cancelling its work) and subscribes a fresh one —
;; reconnect loops, timers restarting from zero. The signature we
;; detect: consecutive runs that BOTH drop cells AND create cells for
;; sources never seen before. Re-creating a recently-dropped source is
;; exempt — that's a conditional branch flipping back, which is fine.

(def ^:private churn-threshold 3)
(def ^:private recent-drops-cap 16)

(def ^:dynamic *on-churn*
  "Test/tooling hook: when bound, called with {:streak n} instead of
  printing the churn warning."
  nil)

(defn- warn-churn! [info]
  (if *on-churn*
    (*on-churn* info)
    (let [msg (str "[flowdom.rx] an rx re-created flow dependencies on "
                   (:streak info) " consecutive runs. This usually means a "
                   "flow is being BUILT inside the rx body — making it a new "
                   "dependency every run, so its old subscription is "
                   "cancelled and its work restarts (connections reopen, "
                   "timers restart). Build the flow once, outside the body, "
                   "and read that one value; memoize by args when "
                   "parameterized. Warned once per rx.")]
      #?(:clj  (binding [*out* *err*] (println msg))
         :cljs (js/console.warn msg)))))

(defn- note-churn! [ctx dropped]
  (let [recent  (:recent-drops ctx)
        seen    (set @recent)
        novel   (remove seen @(:fresh ctx))
        churn?  (and (seq dropped) (seq novel))
        state   (:churn ctx)]
    (swap! recent #(into [] (take-last recent-drops-cap (into % dropped))))
    (if churn?
      (let [{:keys [streak warned?]} (swap! state update :streak inc)]
        (when (and (>= streak churn-threshold) (not warned?))
          (swap! state assoc :warned? true)
          (warn-churn! {:streak streak})))
      (swap! state assoc :streak 0))))

(def ^:private runaway-cap
  "Max self-triggered re-runs in one propagation turn. The inner loop
  only repeats when the body's own run dirtied the rx — a body that
  writes state it reads, or a flow built inside the body whose first
  (synchronous) emission re-triggers it. Past the cap that is a
  runaway, and without a breaker a synchronously-ready source (m/watch,
  an already-emitted flow) spins forever."
  100)

(defn- runaway-error []
  (ex-info (str "flowdom: runaway rx — the body re-ran synchronously "
                runaway-cap "+ times in one propagation turn. Either the "
                "body writes state it reads, or a flow is built inside "
                "the body (a new dependency every run, whose first "
                "emission re-triggers the run). Build flows once, outside "
                "the body; don't write what you read.")
           {:flowdom/runaway true}))

(defn- run-rx! [ctx]
  (locked
   (:lock ctx)
   (fn []
     (reset! (:dirty ctx) true)
     (when (and @(:alive ctx) (not @(:running ctx)))
       (reset! (:running ctx) true)
       (try
         (loop [n 0]
           (when (and @(:alive ctx) @(:dirty ctx))
             (if (> n runaway-cap)
               ;; THROW, don't emit: mid-spin the observe consumer may
               ;; not be draining yet, so a second emission would block
               ;; forever. Throwing fails the surrounding process —
               ;; missionary's error channel carries it to the reader.
               (do (reset! (:dirty ctx) false)
                   (throw (runaway-error)))
               (do
                 (reset! (:dirty ctx) false)
                 (reset! (:used ctx) #{})
                 (reset! (:fresh ctx) #{})
                 (let [v (try
                           (binding [*ctx* ctx] ((:thunk ctx)))
                           (catch #?(:clj Throwable :cljs :default) e
                             (if (pending-ex? e) pending (->Err e))))]
                   (note-churn! ctx (gc-cells! ctx))
                   (when (not= v @(:last ctx))
                     (reset! (:last ctx) v)
                     ((:emit! ctx) v)))
                 (recur (inc n))))))
         (finally
           (reset! (:running ctx) false)))))))

;; ---------------------------------------------------------------------------
;; rx

(defn rx*
  "Function form of `rx`: a zero-argument thunk to a shared continuous
  flow of its values. Prefer the macro."
  [thunk]
  (->Rx
   (m/signal
    (m/relieve {}
               (m/observe
                (fn [emit!]
                  (let [ctx {:cells        (atom {})
                             :used         (atom #{})
                             :fresh        (atom #{})
                             :recent-drops (atom [])
                             :churn        (atom {:streak 0 :warned? false})
                             :dirty        (atom false)
                             :running      (atom false)
                             :alive        (atom true)
                             :last         (atom ::unset)
                             :thunk        thunk
                             :emit!        emit!
                             :lock         #?(:clj (Object.) :cljs nil)}]
                    (run-rx! ctx)
                    (fn cleanup []
                      (locked
                       (:lock ctx)
                       (fn []
                         (reset! (:alive ctx) false)
                         (doseq [[_ cell] @(:cells ctx)]
                           ((:stop! cell)))
                         (reset! (:cells ctx) {})))))))))))

#?(:clj
   (defmacro rx
     "Delimit a restartable reactive computation. Inside the body, `?`
  reads atoms/flows/rxs and subscribes the block; any change re-runs
  the body. Evaluates to a shared continuous flow of the body's values,
  recognized by renderers as a dynamic position.

      (rx (str \"$\" (? price)))"
     [& body]
     `(rx* (fn [] ~@body))))

(defn effect
  "Run `flow` for its side effects for as long as the position is
  mounted: an rx that reads the flow and renders nothing. Place it
  anywhere in the tree — mount subscribes (the flow starts), unmount
  cancels it. Lifetime is subscription, like everything else; this
  just names the run-for-effect case:

      [:p (effect heartbeat) \"❤ beating…\"]"
  [flow]
  (rx* (fn [] (? flow) nil)))

(defn hold
  "Share a cold flow as an rx: any number of readers `?` the hold over
  ONE subscription (for a flowrpc query, one SSE connection), and the
  last reader leaving tears it down. Pending until the flow's first
  value — the nearest `:fallback` renders — so definitive states only
  appear once the source has actually answered; pass `initial` only
  when an immediate value is the right semantics. Build once (ns level
  or component body), never inside an rx body — a rebuilt hold is a
  fresh subscription:

      (def projects (hold (rpc/list-projects)))"
  ([flow] (rx* (fn [] (? flow))))
  ([flow initial]
   (let [src (m/ap (m/amb initial (m/?> (unwrap flow))))]
     (rx* (fn [] (? src))))))
