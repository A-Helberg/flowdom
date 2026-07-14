(ns solidclj.missionary
  "Bridges between Missionary (tasks & flows) and SolidJS reactivity.

  Suggested requires:

      (:require [missionary.core     :as m]
                [solidclj.missionary :as sm])

  Naming policy
  =============
  Missionary owns its vocabulary; we never reuse a `missionary.core`
  name to mean something else. Names deliberately AVOIDED here because
  missionary already defines them (b.47 var list):

    watch      ref → flow            (so flow → ref is NOT called watch)
    signal     shared continuous flow (so nothing here is called signal,
                                       even though Solid says \"signal\")
    subscribe  publisher subscription
    dispose!   publisher disposal     (ours is halt!)
    observe    callback → flow
    reduce / reductions / eduction / zip / sample / relieve / buffer
    attempt / absolve / compel       (task error plumbing)
    memo / stream / group-by / debounce / delay-each
    ? ?< ?> ?? ?= !                  (await/fork operators)

  `solidclj.api/?` predates this namespace and stays: it is the Solid
  analogue of `m/?` (\"await this reactive thing in the current scope\"),
  same meaning transposed, not an overwrite. Its flow machinery lives
  here (see `flow-getter`) so `solidclj.hiccup` does not require
  missionary directly.

  The API in one screen
  =====================
  Missionary → Solid:

    (sm/hold flow & opts)      flow  → read-only reactive ref (deref like s/atom)
    (sm/resource task & opts)  task  → read-only reactive ref, one-shot,
                               reloadable, suspends [:suspense] while pending
    (sm/spawn! task)           run a task under the current owner; cancel on cleanup

    accessors on hold/resource refs:
    (sm/pending? r)            reactive: no value yet?
    (sm/error r)               reactive: last failure, or nil
    (sm/reload! r)             resource only: re-run the task
    (sm/halt! r)               escape hatch: permanently stop a ref

  Solid → Missionary:

    (m/watch satom)            s/atoms are IWatchable — missionary's own
                               watch just works; we add nothing.
    (sm/tracked thunk)         Solid-tracked computation → continuous flow

  Hiccup story
  ============
  Flows are fns, so they are indistinguishable from render thunks in a
  child slot — the renderer does NOT auto-bridge them (same philosophy
  as plain atoms: nothing implicit). You always name the bridge:

      (defonce price (sm/hold (rpc/query 'ticker/price \"AAPL\")
                              :initial \"—\"))

      [:span \"AAPL: \" @price]                      ;; h macro wraps deref
      (fn [] (if (sm/pending? price) [spinner] [:p @price]))

  (`hold` is lazy, so a defonce like this costs nothing until a page
  renders it — see Lifecycle below.)

  Lifecycle (decided 2026-07-04)
  ==============================
  `hold` is LAZY and refcounted, Reagent-reaction style: calling `hold`
  runs nothing. The flow starts on the first reactive subscription
  (deref in a tracking scope, or add-watch), every subscriber counts,
  and when the last one is cleaned up the flow is cancelled. A later
  subscription restarts it from scratch. defonce holds are therefore
  safe: no SSE connection opens until the page that reads them
  renders, and navigating away closes it.

  `resource` is EAGER, matching createResource: the task starts when
  `resource` is called. Create resources in component bodies for
  fetch-on-render; cleanup of the owner cancels an in-flight task.

  `spawn!` is owner-tied: cancelled in onCleanup.

  Semantics shared by hold/resource refs
  ======================================
  - Satisfy IReactiveAtom + IDeref + IWatchable (NOT IReset/ISwap —
    read-only; writes belong to the flow). IWatchable means they also
    cross the React bridge as props.
  - Deref inside a tracking scope subscribes it, exactly like s/atom.
  - Hold propagation dedups with `=` (same as s/atom). Resources
    notify on every refetch settle — createResource has no custom
    equality — though the IWatchable watcher path does dedup with `=`.
  - On failure a hold keeps serving its last good value alongside the
    error (see `error`); a failed *resource* deref re-throws the error
    (createResource semantics — pair with [:error-boundary], or branch
    on (sm/error r) first). On normal flow completion a hold keeps the
    final value forever.
  - Discrete flows are consumed at Solid speed (synchronous transfer),
    which IS the backpressure — no implicit m/relieve; compose one
    yourself if the producer must not block.

  JVM
  ===
  Everything above works against solidclj.runtime's simulator. The
  differences: [:suspense] is a pass-through stub on the JVM, so a
  pending resource deref does not suspend — it serves :initial (branch
  on (sm/pending? r) in tests). Deferred hold shutdown uses the
  runtime's post-turn queue instead of a microtask, so refcounts have
  settled by the time a swap! returns. Flows may emit from other
  threads (blocking executors, timers); emissions propagate
  synchronously on the emitting thread — wait for the emission before
  asserting."
  (:require [missionary.core :as m]
            [solidclj.runtime :as rt]
            [solidclj.satom :as satom]
            #?@(:cljs [["solid-js" :refer [createResource createRenderEffect]]])))

(defprotocol IAsyncRef
  "Read-side surface shared by hold and resource refs."
  (-pending? [r])
  (-error [r])
  (-reload! [r])
  (-halt! [r]))

;; -----------------------------------------------------------------------
;; hold — flow → lazy, refcounted, read-only reactive ref
;; -----------------------------------------------------------------------

;; Callback rule shared by both deftypes: emissions/terminations from
;; the running flow call back into protocol methods carrying the run
;; generation `g`, and the method compares against the current `gen`.
;; (On the JVM this is forced — CLJ deftype mutable fields are only
;; touchable from method bodies, never closures — and the CLJS type
;; follows the same shape so the two stay in lockstep.)

(defprotocol ^:private IHoldImpl
  (-subscribe! [r])
  (-release! [r])
  (-maybe-stop! [r])
  (-start! [r])
  (-stop! [r])
  (-emit-value! [r g v])
  (-run-complete! [r g])
  (-run-failed! [r e g]))

#?(:cljs
   (deftype HoldRef [flow initial
                     ^:mutable state ^:mutable pending ^:mutable err
                     vget vset pget pset eget eset
                     ^:mutable watches ^:mutable subs
                     ^:mutable cancel ^:mutable gen ^:mutable halted
                     ^:mutable starting]
     Object
     (equiv [this other] (-equiv this other))

     satom/IReactiveAtom

     IHoldImpl
     (-start! [r]
       ;; `gen` invalidates callbacks from superseded runs: -stop!/-halt!
       ;; bump it, so a straggling emission or failure from a cancelled
       ;; process is ignored (the check lives in -emit-value!/-run-*).
       (let [g (inc gen)]
         (set! gen g)
         (set! starting true)
         ;; untrack: a start triggered by a tracked deref would otherwise
         ;; leak the subscribing computation into the flow's synchronous
         ;; setup — e.g. (m/watch a) sampling @a would subscribe the outer
         ;; effect to `a` itself.
         (let [done (volatile! false)
               c    (rt/untrack
                     (fn []
                       ((m/reduce (fn [_ v] (-emit-value! r g v) nil)
                                  nil flow)
                        (fn [_] (vreset! done true) (-run-complete! r g))
                        (fn [e] (vreset! done true) (-run-failed! r e g)))))]
           ;; a flow that terminates synchronously (m/seed, immediate crash)
           ;; is already done — don't store a cancel thunk for a dead run.
           (when-not @done
             (set! cancel c)))
         (set! starting false)))

     (-stop! [r]
       (when (some? cancel)
         (let [c cancel]
           (set! gen (inc gen))
           (set! cancel nil)
           (c))
         ;; reset so a future subscription restarts from scratch
         (set! state initial)
         (set! pending true)
         (set! err nil)
         (vset (fn [_] initial))
         (pset (fn [_] true))
         (eset (fn [_] nil))))

     (-emit-value! [r g v]
       (when (== g gen)
         (let [old state]
           (set! state v)
           (set! pending false)
           ;; A synchronous emission during -start! must NOT write the
           ;; signals: the start was triggered by a subscriber mid-flush,
           ;; which reads `state` directly right after — a reactive write
           ;; here would re-run it once more with the same value. The
           ;; signals are pure change-propagation channels; their stored
           ;; value is never read (deref returns `state`).
           (when-not starting
             (vset (fn [_] v))
             (pset (fn [_] false)))
           (when (some? watches)
             (-notify-watches r old v)))))

     (-run-complete! [r g]
       ;; natural termination: keep the final value forever
       (when (== g gen)
         (set! cancel nil)))

     (-run-failed! [r e g]
       (when (== g gen)
         (set! cancel nil)
         (set! err e)
         (set! pending false)
         (when-not starting
           (eset (fn [_] e))
           (pset (fn [_] false)))
         (when rt/debug?
           (rt/warn! (str "[solidclj.missionary] hold flow failed: " e)))))

     (-subscribe! [r]
       ;; one increment per tracked read, matched by an onCleanup on the
       ;; *subscribing* computation. Reads within one run are symmetric
       ;; (n increments, n cleanups), so no per-computation dedup needed.
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] subscription to a halted ref ignored"))
         (do (set! subs (inc subs))
             (when (and (== 1 subs) (nil? cancel) pending (nil? err))
               (-start! r))
             (rt/on-cleanup (fn [] (-release! r))))))

     (-release! [r]
       (set! subs (dec subs))
       (when (zero? subs)
         ;; deferred: when a computation re-runs, Solid disposes it (count
         ;; 1→0) and re-executes it (0→1) synchronously — cancelling in
         ;; between would tear down the flow on every update. defer!
         ;; outlives the synchronous re-run.
         (rt/defer! (fn [] (-maybe-stop! r)))))

     (-maybe-stop! [r]
       (when (and (zero? subs) (not halted))
         (-stop! r)))

     IAsyncRef
     (-pending? [r]
       (when (some? (rt/get-listener))
         (-subscribe! r)
         (pget))
       pending)
     (-error [r]
       (when (some? (rt/get-listener))
         (-subscribe! r)
         (eget))
       err)
     (-reload! [_]
       (throw (js/Error. "reload! applies to resources; a hold restarts when its subscribers release it")))
     (-halt! [r]
       (when-not halted
         (set! halted true)
         (when (some? cancel)
           (let [c cancel]
             (set! gen (inc gen))
             (set! cancel nil)
             (c)))))

     IDeref
     (-deref [r]
       (if (some? (rt/get-listener))
         (do (-subscribe! r)
             (vget))
         (when (and rt/debug? pending (nil? cancel) (not halted))
           (rt/warn!
            (str "[solidclj.missionary] hold deref'd outside a tracking scope "
                 "while its flow is not running — returning the initial value. "
                 "Deref inside a render thunk/effect to start the flow."))))
       state)

     IWatchable
     (-notify-watches [r old new]
       (doseq [[k f] watches]
         (f k r old new)))
     (-add-watch [r k f]
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] add-watch on a halted ref ignored"))
         (let [new-key? (not (contains? watches k))]
           ;; register before starting so a synchronously-emitting flow
           ;; notifies this watcher too
           (set! watches (assoc watches k f))
           (when new-key?
             (set! subs (inc subs))
             (when (and (== 1 subs) (nil? cancel) pending (nil? err))
               (-start! r)))))
       r)
     (-remove-watch [r k]
       (when (contains? watches k)
         (set! watches (dissoc watches k))
         (-release! r))
       r)

     IEquiv
     (-equiv [o other] (identical? o other))

     IHash
     (-hash [o] (goog/getUid o))

     IPrintWithWriter
     (-pr-writer [_ writer opts]
       (-write writer "#<Hold: ")
       (pr-writer state writer opts)
       (-write writer ">"))))

#?(:clj
   ;; The JVM twin, method-for-method against solidclj.runtime; the
   ;; JVM missionary tests and the parity suite keep the two in
   ;; lockstep. See the callback rule above IHoldImpl.
   (deftype HoldRef [flow initial
                     ^:volatile-mutable state ^:volatile-mutable pending ^:volatile-mutable err
                     vget vset pget pset eget eset
                     ^:volatile-mutable watches ^:volatile-mutable subs
                     ^:volatile-mutable cancel ^:volatile-mutable gen ^:volatile-mutable halted
                     ^:volatile-mutable starting]
     satom/IReactiveAtom

     IHoldImpl
     (-start! [r]
       (let [g (inc gen)]
         (set! gen g)
         (set! starting true)
         (let [done (volatile! false)
               c    (rt/untrack
                     (fn []
                       ((m/reduce (fn [_ v] (-emit-value! r g v) nil)
                                  nil flow)
                        (fn [_] (vreset! done true) (-run-complete! r g))
                        (fn [e] (vreset! done true) (-run-failed! r e g)))))]
           (when-not @done
             (set! cancel c)))
         (set! starting false)))

     (-stop! [r]
       (when (some? cancel)
         (let [c cancel]
           (set! gen (inc gen))
           (set! cancel nil)
           (c))
         (set! state initial)
         (set! pending true)
         (set! err nil)
         (vset (fn [_] initial))
         (pset (fn [_] true))
         (eset (fn [_] nil))))

     (-emit-value! [r g v]
       (when (== g gen)
         (let [old state]
           (set! state v)
           (set! pending false)
           (when-not starting
             (vset (fn [_] v))
             (pset (fn [_] false)))
           (doseq [[k f] watches]
             (f k r old v)))))

     (-run-complete! [r g]
       (when (== g gen)
         (set! cancel nil)))

     (-run-failed! [r e g]
       (when (== g gen)
         (set! cancel nil)
         (set! err e)
         (set! pending false)
         (when-not starting
           (eset (fn [_] e))
           (pset (fn [_] false)))
         (when rt/debug?
           (rt/warn! (str "[solidclj.missionary] hold flow failed: " e)))))

     (-subscribe! [r]
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] subscription to a halted ref ignored"))
         (do (set! subs (inc subs))
             (when (and (== 1 subs) (nil? cancel) pending (nil? err))
               (-start! r))
             (rt/on-cleanup (fn [] (-release! r))))))

     (-release! [r]
       (set! subs (dec subs))
       (when (zero? subs)
         (rt/defer! (fn [] (-maybe-stop! r)))))

     (-maybe-stop! [r]
       (when (and (zero? subs) (not halted))
         (-stop! r)))

     IAsyncRef
     (-pending? [r]
       (when (some? (rt/get-listener))
         (-subscribe! r)
         (pget))
       pending)
     (-error [r]
       (when (some? (rt/get-listener))
         (-subscribe! r)
         (eget))
       err)
     (-reload! [_]
       (throw (UnsupportedOperationException.
               "reload! applies to resources; a hold restarts when its subscribers release it")))
     (-halt! [r]
       (when-not halted
         (set! halted true)
         (when (some? cancel)
           (let [c cancel]
             (set! gen (inc gen))
             (set! cancel nil)
             (c)))))

     clojure.lang.IDeref
     (deref [r]
       (if (some? (rt/get-listener))
         (do (-subscribe! r)
             (vget))
         (when (and rt/debug? pending (nil? cancel) (not halted))
           (rt/warn!
            (str "[solidclj.missionary] hold deref'd outside a tracking scope "
                 "while its flow is not running — returning the initial value. "
                 "Deref inside a render thunk/effect to start the flow."))))
       state)

     clojure.lang.IRef
     (setValidator [_ _]
       (throw (UnsupportedOperationException. "holds are read-only")))
     (getValidator [_] nil)
     (getWatches [_] (or watches {}))
     (addWatch [r k f]
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] add-watch on a halted ref ignored"))
         (let [new-key? (not (contains? watches k))]
           ;; register before starting so a synchronously-emitting flow
           ;; notifies this watcher too
           (set! watches (assoc watches k f))
           (when new-key?
             (set! subs (inc subs))
             (when (and (== 1 subs) (nil? cancel) pending (nil? err))
               (-start! r)))))
       r)
     (removeWatch [r k]
       (when (contains? watches k)
         (set! watches (dissoc watches k))
         (-release! r))
       r)))

#?(:clj
   (defmethod print-method HoldRef [^HoldRef h ^java.io.Writer w]
     (.write w "#<Hold: ")
     (print-method (.-state h) w)
     (.write w ">")))

(defn hold
  "Runs `flow` and returns a read-only reactive ref of its latest value
  — the FRP hold (events → behaviour). Deref it exactly like an s/atom:

      (defonce temps (sm/hold (m/watch sensor) :initial 21))
      [:p \"it is \" @temps \"°C\"]

  Options:
    :initial  value returned by deref until the first emission
              (default nil; continuous flows emit immediately, so this
              mostly matters for discrete flows — see `pending?`)

  Lifecycle — lazy + refcounted (Reagent-reaction style):
  - Calling `hold` runs nothing. The flow starts on the first reactive
    subscription — deref/pending?/error inside a tracking scope, or
    add-watch (so React-bridge props count) — and is cancelled when the
    last subscriber is cleaned up.
  - Re-subscribing later restarts the flow FROM SCRATCH: value back to
    :initial, pending? true again. For SSE queries that is correct (a
    fresh :full snapshot); if remount flicker ever grates, a keep-alive
    grace period can be added later without changing this API.
  - Wart, inherited from Reagent reactions: deref outside any tracking
    scope while nothing is subscribed does NOT run the flow — it
    returns :initial or whatever the last run left behind. Stale by
    design; dev mode warns.

  Sharing: one active hold = one flow subscription. To share an
  upstream among several holds, share on the missionary side — and
  laziness makes that compose: (sm/hold (m/signal f)) starts and stops
  the shared publisher with the hold's own subscribers."
  [flow & {:keys [initial]}]
  (let [[vg vs] (rt/create-signal initial =)
        [pg ps] (rt/create-signal true)
        [eg es] (rt/create-signal nil)]
    (HoldRef. flow initial
              initial true nil
              vg vs pg ps eg es
              nil 0
              nil 0 false false)))

;; -----------------------------------------------------------------------
;; resource — task → eager, suspense-aware, read-only reactive ref
;; -----------------------------------------------------------------------

#?(:cljs
   (deftype ResourceRef [res ctl run-vol ^:mutable watches ^:mutable halted]
     Object
     (equiv [this other] (-equiv this other))

     satom/IReactiveAtom

     IAsyncRef
     (-pending? [_] (.-loading ^js res))
     (-error [_] (.-error ^js res))
     (-reload! [r]
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] reload! on a halted resource ignored"))
         (let [old @run-vol]
           ((.-refetch ^js ctl))
           ;; kill the superseded run AFTER refetch so the resource is
           ;; already tracking the new promise; the old promise never
           ;; settles (its callbacks are gated on :alive).
           (when (some? old)
             (vreset! (:alive old) false)
             ((:cancel old))))))
     (-halt! [r]
       (when-not halted
         (set! halted true)
         (when-let [run @run-vol]
           (vreset! (:alive run) false)
           ((:cancel run)))))

     IDeref
     ;; reading the resource accessor is what registers with an enclosing
     ;; Suspense boundary while loading, and re-throws a task failure
     ;; (ErrorBoundary catches it).
     (-deref [_] (res))

     IWatchable
     (-notify-watches [r old new]
       (doseq [[k f] watches]
         (f k r old new)))
     (-add-watch [r k f]
       (set! watches (assoc watches k f))
       r)
     (-remove-watch [r k]
       (set! watches (dissoc watches k))
       r)

     IEquiv
     (-equiv [o other] (identical? o other))

     IHash
     (-hash [o] (goog/getUid o))

     IPrintWithWriter
     (-pr-writer [_ writer opts]
       (-write writer "#<Resource: ")
       (-write writer (if (.-loading ^js res) "pending" "ready"))
       (-write writer ">"))))

#?(:clj
   (defprotocol ^:private IResourceImpl
     (-start-run! [r])
     (-settle-ok! [r v])
     (-settle-err! [r e])))

#?(:clj
   ;; The JVM twin is built directly on runtime signals (no
   ;; createResource): same eager/reload!/halt! semantics, same
   ;; failed-deref-rethrows contract. [:suspense] is a stub on the JVM,
   ;; so a pending deref serves :initial instead of suspending.
   (deftype ResourceRef [task run-vol
                         ^:volatile-mutable value ^:volatile-mutable pending ^:volatile-mutable err
                         vget vset pget pset eget eset
                         ^:volatile-mutable watches ^:volatile-mutable halted]
     satom/IReactiveAtom

     IResourceImpl
     (-start-run! [r]
       (set! pending true)
       (pset (fn [_] true))
       (set! err nil)
       (eset (fn [_] nil))
       (let [alive (volatile! true)]
         (vreset! run-vol
                  {:alive  alive
                   :cancel (task (fn [v] (when @alive (-settle-ok! r v)))
                                 (fn [e] (when @alive (-settle-err! r e))))})))
     (-settle-ok! [r v]
       (let [old value]
         (set! value v)
         (set! pending false)
         (vset (fn [_] v))
         (pset (fn [_] false))
         (doseq [[k f] watches]
           (f k r old v))))
     (-settle-err! [r e]
       (set! err e)
       (set! pending false)
       (eset (fn [_] e))
       (pset (fn [_] false))
       (when rt/debug?
         (rt/warn! (str "[solidclj.missionary] resource task failed: " e))))

     IAsyncRef
     (-pending? [_]
       (when (some? (rt/get-listener)) (pget))
       pending)
     (-error [_]
       (when (some? (rt/get-listener)) (eget))
       err)
     (-reload! [r]
       (if halted
         (when rt/debug?
           (rt/warn! "[solidclj.missionary] reload! on a halted resource ignored"))
         (let [old @run-vol]
           ;; start the new run first; the superseded one can never
           ;; settle (its callbacks are gated on :alive), then cancel it.
           (-start-run! r)
           (when (some? old)
             (vreset! (:alive old) false)
             ((:cancel old))))))
     (-halt! [r]
       (when-not halted
         (set! halted true)
         (when-let [run @run-vol]
           (vreset! (:alive run) false)
           ((:cancel run)))))

     clojure.lang.IDeref
     (deref [_]
       ;; subscribe to both value and error transitions, then mirror
       ;; createResource: a failed resource deref re-throws.
       (when (some? (rt/get-listener)) (vget) (eget))
       (if (some? err) (throw err) value))

     clojure.lang.IRef
     (setValidator [_ _]
       (throw (UnsupportedOperationException. "resources are read-only")))
     (getValidator [_] nil)
     (getWatches [_] (or watches {}))
     (addWatch [r k f]
       (set! watches (assoc watches k f))
       r)
     (removeWatch [r k]
       (set! watches (dissoc watches k))
       r)))

#?(:clj
   (defmethod print-method ResourceRef [^ResourceRef r ^java.io.Writer w]
     (.write w "#<Resource: ")
     (.write w ^String (if (.-pending r) "pending" "ready"))
     (.write w ">")))

#?(:cljs
   (defn resource
     "Runs `task` and returns a read-only reactive ref of its result,
  built ON TOP of Solid's createResource — so it cooperates with the
  rest of Solid: deref while pending registers with an enclosing
  [:suspense {:fallback …} …] boundary, and reload! composes with
  transitions (stale value shown while the new run is in flight).

      (defn profile-page [{:keys [id]}]
        (let [user (sm/resource (fetch-user id))]
          [:suspense {:fallback [spinner]}
           (fn [] [profile @user])]))

  Or branch manually — pending?/error do not suspend, only a pending
  deref does (mirrors resource.loading vs resource() in Solid):

      (fn [] (cond (sm/pending? user) [spinner]
                   (sm/error user)    [oops (sm/error user)]
                   :else              [profile @user]))

  Options:
    :initial  value served by deref while the first run is pending
              (default nil) — relevant when reading outside a suspense
              boundary or branching on pending? manually. It does NOT
              opt out of suspension: createResource registers any
              in-flight read with an enclosing boundary, initialValue
              or not, so under [:suspense] the fallback still shows.

  Eager like createResource: the task starts when `resource` is called.
  Create resources in component bodies (under a Solid owner): cleanup
  cancels an in-flight run, and `reload!` cancels it and re-runs the
  task (tasks are recipes — rerunning is free to express).

  Missionary cancellation is preserved: the task's cancel thunk is kept
  and invoked on owner cleanup, reload! and halt!, even though the
  promise handed to createResource cannot itself cancel — a deliberate
  cancel leaves the old promise unsettled rather than rejecting, so no
  transient error state leaks into the UI."
     [task & {:keys [initial] :as opts}]
     (let [run-vol (volatile! nil)
           fetcher (fn [_ _]
                     (js/Promise.
                      (fn [resolve reject]
                        (let [alive (volatile! true)]
                          (vreset! run-vol
                                   {:alive  alive
                                    :cancel (task (fn [v] (when @alive (resolve v)))
                                                  (fn [e] (when @alive (reject e))))})))))
           pair    (if (contains? opts :initial)
                     (createResource fetcher #js {:initialValue initial})
                     (createResource fetcher))
           res     (aget pair 0)
           ctl     (aget pair 1)
           ref     (ResourceRef. res ctl run-vol nil false)]
       (if (some? (rt/get-owner))
         (do (rt/on-cleanup (fn []
                              (when-let [run @run-vol]
                                (vreset! (:alive run) false)
                                ((:cancel run)))))
             ;; feed IWatchable watchers (React bridge et al.) from the
             ;; resource's reactive state; only ready values notify.
             (createRenderEffect
              (fn [prev]
                (let [l (.-loading ^js res)
                      e (.-error ^js res)
                      v (when (and (not l) (nil? e)) (res))]
                  (when (and (some? (.-watches ref)) (not= prev v))
                    (-notify-watches ref prev v))
                  v))
              nil))
         (when rt/debug?
           (rt/warn!
            (str "[solidclj.missionary] resource created outside a Solid owner: "
                 "the task cannot be auto-cancelled and watchers will not fire. "
                 "Create resources in component bodies."))))
       ref))

   :clj
   (defn resource
     "JVM twin of the CLJS resource — see that docstring for the full
  semantics: eager (task starts now), reload! cancels and re-runs,
  cleanup of the owner cancels an in-flight run, deref of a failed
  resource re-throws. One difference: [:suspense] is a stub on the
  JVM, so a pending deref serves :initial instead of suspending —
  branch on (sm/pending? r)."
     [task & {:keys [initial]}]
     (let [[vg vs] (rt/create-signal initial (fn [_ _] false))
           [pg ps] (rt/create-signal true)
           [eg es] (rt/create-signal nil)
           run-vol (volatile! nil)
           ref     (ResourceRef. task run-vol
                                 initial true nil
                                 vg vs pg ps eg es
                                 nil false)]
       (-start-run! ref)
       (if (some? (rt/get-owner))
         (rt/on-cleanup (fn []
                          (when-let [run @run-vol]
                            (vreset! (:alive run) false)
                            ((:cancel run)))))
         (when rt/debug?
           (rt/warn!
            (str "[solidclj.missionary] resource created outside a Solid owner: "
                 "the task cannot be auto-cancelled. Create resources in "
                 "component bodies."))))
       ref)))

;; -----------------------------------------------------------------------
;; accessors
;; -----------------------------------------------------------------------

(defn pending?
  "Reactive: true until `r` (a hold/resource ref) has produced its first
  value. Deref-like — subscribes the current tracking scope, and for
  holds that subscription counts toward the refcount (reading pending?
  starts the flow). Never suspends — branch on it freely inside a
  [:suspense] boundary."
  [r]
  (-pending? r))

(defn error
  "Reactive: the failure that terminated `r`'s flow/task, or nil.
  Deref-like — subscribes the current tracking scope (counts toward a
  hold's refcount). Never suspends and never throws — the safe way to
  inspect a failed resource."
  [r]
  (-error r))

(defn reload!
  "Re-runs a `resource`'s task: cancels any in-flight run, refetches.
  Inside a transition the previous value keeps rendering until the new
  run lands (createResource refetch semantics). Throws on `hold` refs —
  to restart a hold, its subscribers must release it."
  [r]
  (-reload! r))

(defn halt!
  "Escape hatch: permanently stops a hold/resource — cancels any running
  flow/task, pins the current value, and ignores future subscriptions
  (dev mode warns on them). Rarely needed now that hold lifecycle is
  subscriber-driven; intended for tests and hot-reload teardown hooks."
  [r]
  (-halt! r))

;; -----------------------------------------------------------------------
;; spawn! — run a task for effect under the current owner
;; -----------------------------------------------------------------------

(defn spawn!
  "Runs `task` for effect under the current Solid owner and returns a
  cancel thunk. The task is cancelled automatically in onCleanup.

  This is the one effect primitive; flows arrive here through missionary:

      (sm/spawn! (m/reduce (fn [_ e] (log! e)) nil event-flow))

  Calling with no current owner throws in dev — an unowned effect is
  almost always a leak; use a plain `(task success failure)` call if you
  really mean fire-and-forget."
  [task]
  (when (nil? (rt/get-owner))
    (if rt/debug?
      (throw (ex-info (str "[solidclj.missionary] spawn! outside a Solid owner — "
                           "the task would never be cancelled. Run "
                           "(task success failure) directly if you mean "
                           "fire-and-forget.")
                      {}))
      (rt/warn! "[solidclj.missionary] spawn! outside a Solid owner; task will never be cancelled")))
  (let [alive   (volatile! true)
        cancel  (task (fn [_] nil)
                      (fn [e]
                        (when @alive
                          (rt/warn! (str "[solidclj.missionary] spawn! task failed: " e)))))
        cancel! (fn []
                  (vreset! alive false)
                  (cancel))]
    (when (some? (rt/get-owner))
      (rt/on-cleanup cancel!))
    cancel!))

;; -----------------------------------------------------------------------
;; Solid → Missionary
;; -----------------------------------------------------------------------
;;
;; For a single s/atom, use missionary directly: (m/watch a).

(defn tracked
  "Returns a continuous flow of the values of `thunk`, a zero-arg fn run
  in a Solid tracking scope. Any s/atom (or signal) deref'd inside it
  re-runs the computation and the flow emits the new value:

      (def celsius (s/atom 21))
      (sm/tracked #(-> @celsius (* 9/5) (+ 32)))   ;; flow of °F

  The Solid root lives exactly as long as the flow: created on spawn,
  disposed on cancel. Emissions dedup with `=` (memo semantics)."
  [thunk]
  (m/relieve {}
             (m/observe
              (fn [emit!]
                (let [[_ dispose] (rt/create-root
                                   (fn []
                                     (let [v (rt/create-memo thunk =)]
                                       (rt/create-effect (fn [] (emit! (v)))))))]
                  dispose)))))

;; -----------------------------------------------------------------------
;; flow-getter — building block for solidclj.api/?
;; -----------------------------------------------------------------------

(defn- run-getter [flow]
  (let [[get-v set-v] (rt/create-signal nil)
        cancel        (rt/untrack
                       (fn []
                         ((m/reduce (fn [_ v] (set-v (fn [_] v)) nil) nil flow)
                          (fn [_] nil)
                          (fn [e] (rt/warn! (str "[solidclj.missionary] flow error: " e))))))]
    ;; wrap: missionary processes are IFn deftypes, invokable from CLJS
    ;; but not native JS functions — Solid calls cleanups as JS fns.
    (rt/on-cleanup (fn [] (cancel)))
    get-v))

#?(:cljs
   (def ^:private owner-cache (js/WeakMap.)))

(defn flow-getter
  "Materialises `flow` into a Solid signal getter under the current
  owner and returns it; call the getter to read the latest value.
  Cached per owner, so repeated calls with the same flow share one
  running process. Runs for the owner's whole lifetime (no refcount) —
  this is the component-scoped building block behind `solidclj.api/?`;
  prefer `hold` for a shareable, lazily-managed ref."
  [flow]
  (if-let [owner (rt/get-owner)]
    #?(:cljs
       (let [cache (or (.get owner-cache owner)
                       (let [c (js/Map.)]
                         (.set owner-cache owner c)
                         (rt/on-cleanup (fn [] (.delete owner-cache owner)))
                         c))]
         (or (.get cache flow)
             (let [getter (run-getter flow)]
               (.set cache flow getter)
               getter)))
       :clj
       ;; the owner node is an atom — the cache lives on it and dies
       ;; with it; on-cleanup clears it so a computation re-run builds
       ;; fresh getters (the old run's processes were cancelled with it).
       (or (get-in @owner [::flow-cache flow])
           (let [getter (run-getter flow)]
             (when (nil? (::flow-cache @owner))
               (rt/on-cleanup (fn [] (swap! owner dissoc ::flow-cache))))
             (swap! owner assoc-in [::flow-cache flow] getter)
             getter)))
    (do (when rt/debug?
          (rt/warn! "[solidclj.missionary] flow-getter called outside a reactive owner; returning a constantly-nil getter."))
        (fn [] nil))))
