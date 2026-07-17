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
        cell)))

(defn ?
  "Read a reactive source inside an rx block: returns its current value
  and subscribes the block to it. Works at any call depth — helper
  functions called from the block read freely. Accepts atoms (anything
  IWatchable), missionary flows, and rx values."
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
  on every run, so conditional branches subscribe only what they use."
  [ctx]
  (let [used @(:used ctx)]
    (doseq [[src cell] @(:cells ctx)]
      (when-not (contains? used src)
        (swap! (:cells ctx) dissoc src)
        ((:stop! cell))))))

(defn- run-rx! [ctx]
  (locked
   (:lock ctx)
   (fn []
     (reset! (:dirty ctx) true)
     (when (and @(:alive ctx) (not @(:running ctx)))
       (reset! (:running ctx) true)
       (try
         (loop []
           (when (and @(:alive ctx) @(:dirty ctx))
             (reset! (:dirty ctx) false)
             (reset! (:used ctx) #{})
             (let [v (try
                       (binding [*ctx* ctx] ((:thunk ctx)))
                       (catch #?(:clj Throwable :cljs :default) e
                         (if (pending-ex? e) pending (->Err e))))]
               (gc-cells! ctx)
               (when (not= v @(:last ctx))
                 (reset! (:last ctx) v)
                 ((:emit! ctx) v)))
             (recur)))
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
                  (let [ctx {:cells   (atom {})
                             :used    (atom #{})
                             :dirty   (atom false)
                             :running (atom false)
                             :alive   (atom true)
                             :last    (atom ::unset)
                             :thunk   thunk
                             :emit!   emit!
                             :lock    #?(:clj (Object.) :cljs nil)}]
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
