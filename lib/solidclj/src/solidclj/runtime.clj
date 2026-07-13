(ns solidclj.runtime
  "JVM side of the platform seam under solidclj.hiccup and
  solidclj.satom: a SolidJS-semantics simulator.

  On CLJS (runtime.cljs) this surface is a thin re-export of real
  solid-js. Here it is implemented natively: a reactive core (signals,
  effects, owners, cleanup) plus a tree-building `h`, so components
  render and REACT on the JVM — swap! a satom and the tree updates
  fine-grained — and can be snapshotted back to plain hiccup for tests
  and static SSR.

  The tree
  ========
  `h` on a tag string returns an *element node*: an atom holding
  {:node/type :element :tag \"div\" :props {…} :children […]}.
  Fn-valued children and props each get an effect that writes its
  latest value into the node, so the tree is always concrete — reading
  it never runs user code. A dynamic child position is a *slot node*:
  an atom {:node/type :slot :content …} whose content an effect
  replaces. Nested thunks get nested slots, so update granularity
  matches Solid (an inner thunk re-running does not re-run the outer
  one).

  Props semantics (mirroring solid-js/h)
  ======================================
  - :ref            → called once with the element node; not stored
  - :on* keys       → event handlers; stored as data (call them from
                      tests via the snapshot)
  - other fn value  → reactive accessor; a per-prop effect keeps the
                      resolved value current
  - map value       → :style / :classList; fn-valued entries get
                      per-entry effects

  Documented divergences from real Solid
  ======================================
  - Propagation is synchronous and unordered: no batching, no
    topological sorting. Diamond dependencies may re-run an effect
    more than once per write, and it may observe intermediate states.
    Harmless for tree assertions; the parity fixtures are the tripwire.
  - No Suspense timing, no event delegation, no DOM. Browser-only
    features (renderToString/hydrate) intentionally do not exist here.
  - A runaway reactive loop (an effect writing its own dependency)
    throws after a depth limit instead of hanging."
  (:require [clojure.string :as str]))

;; ---- Tracking context --------------------------------------------------------

(def ^:private ^:dynamic *owner*
  "The current owner node: effects and cleanups created now register
  under it. nil outside create-root/render."
  nil)

(def ^:private ^:dynamic *listener*
  "The currently-running computation node — signal reads subscribe it.
  nil when not inside an effect (e.g. a component body: components run
  once and are NOT tracking scopes)."
  nil)

(def ^:private ^:dynamic *depth* 0)

(def ^:private max-depth 256)

;; ---- Owner / computation nodes ------------------------------------------------
;; A node is an atom:
;;   {:node/type :owner
;;    :fn        f-or-nil     ;; present → computation (re-runnable)
;;    :cleanups  [f …]
;;    :children  [node …]     ;; owned computations/owners, disposed with us
;;    :sources   #{signal …}  ;; signals we're subscribed to
;;    :disposed? false}

(defn- make-node []
  (atom {:node/type :owner
         :fn nil :cleanups [] :children [] :sources #{} :disposed? false}))

(defn- adopt!
  "Register `node` as a child of the current owner (if any), so it is
  disposed when the owner cleans/disposes."
  [node]
  (when-some [o *owner*]
    (swap! o update :children conj node))
  node)

(defn- run-cleanup! [f]
  (try
    (f)
    (catch Throwable t
      (binding [*out* *err*]
        (println "[solidclj.runtime] cleanup fn threw:" (.getMessage t))))))

(declare dispose!)

(defn- clean-node!
  "Solid's cleanNode: dispose owned children, run cleanups (LIFO),
  unsubscribe from sources. The node itself stays alive (this is what
  runs before every re-run of a computation)."
  [node]
  (let [{:keys [children cleanups sources]} @node]
    (doseq [c (rseq children)] (dispose! c))
    (doseq [f (rseq cleanups)] (run-cleanup! f))
    (doseq [sig sources] (swap! sig update :subs disj node))
    (swap! node assoc :children [] :cleanups [] :sources #{})))

(defn- dispose! [node]
  (when-not (:disposed? @node)
    (swap! node assoc :disposed? true)
    (clean-node! node)))

(defn- run-computation! [node]
  (when-not (:disposed? @node)
    (when (> *depth* max-depth)
      (throw (ex-info (str "solidclj.runtime: reactive update depth exceeded "
                           max-depth " — likely an effect writing its own dependency")
                      {})))
    (clean-node! node)
    (binding [*owner*    node
              *listener* node
              *depth*    (inc *depth*)]
      ((:fn @node)))))

(defn create-effect
  "Run `f` tracked: signal reads inside subscribe it, and it re-runs
  when they change. Owned by the current owner. Returns the node."
  [f]
  (let [node (make-node)]
    (swap! node assoc :fn f)
    (adopt! node)
    (run-computation! node)
    node))

(defn create-root
  "Run `f` under a fresh root owner (untracked). Returns
  [result dispose!] — dispose! tears down every effect, watcher and
  cleanup created under the root."
  [f]
  (let [root (make-node)]
    (binding [*owner* root *listener* nil]
      [(f) (fn [] (dispose! root))])))

;; ---- Reactive core (seam surface) ---------------------------------------------

(defn create-signal
  "Returns a [getter setter] pair. Reading inside a computation
  subscribes it. `equals` (2-arg fn, default identical?) dedupes
  propagation: writing an equal value does not re-run subscribers.
  Like Solid's setter, a fn argument is treated as an updater
  (old → new) — wrap fn *values* as (fn [_] v)."
  ([init] (create-signal init identical?))
  ([init equals]
   (let [sig (atom {:value init :subs #{}})]
     [(fn signal-get []
        (when-some [l *listener*]
          (when-not (:disposed? @l)
            (swap! sig update :subs conj l)
            (swap! l update :sources conj sig)))
        (:value @sig))
      (fn signal-set [f]
        (let [old (:value @sig)
              new (if (fn? f) (f old) f)]
          (if (and equals (equals old new))
            new
            (do (swap! sig assoc :value new)
                (doseq [c (:subs @sig)]
                  (run-computation! c))
                new))))])))

(defn get-listener
  "The currently-running tracked computation, or nil."
  []
  *listener*)

(defn get-owner
  "The current owner scope, or nil."
  []
  *owner*)

(defn on-cleanup
  "Register `f` to run when the current owner disposes — or, inside an
  effect, before every re-run (Solid semantics). No-op without an
  owner (matching the CLJS side)."
  [f]
  (when-some [o *owner*]
    (swap! o update :cleanups conj f))
  nil)

;; ---- The tree -----------------------------------------------------------------

(defn element-node? [x]
  (and (instance? clojure.lang.IAtom x)
       (= :element (:node/type @x))))

(defn slot-node? [x]
  (and (instance? clojure.lang.IAtom x)
       (= :slot (:node/type @x))))

(declare insert-child)

(defn- insert-dynamic
  "A dynamic child position: an effect keeps the slot's :content
  current. A thunk returning another fn gets a nested slot — its own
  effect — so inner updates don't re-run the outer thunk (Solid's
  granularity)."
  [thunk]
  (let [slot (atom {:node/type :slot :content nil})]
    (create-effect
     (fn []
       (let [v (thunk)]
         (swap! slot assoc :content (insert-child v)))))
    slot))

(defn- insert-child [c]
  (cond
    (fn? c)     (insert-dynamic c)
    (vector? c) (mapv insert-child c)   ;; flattened seqs from ->children
    :else       c))                     ;; element/slot nodes, strings, numbers, nil…

(defn insert
  "Normalize a walked value for insertion at a root: fns get slots,
  vectors recurse. Used by the JVM render entry point in
  solidclj.hiccup — call under an owner (inside create-root)."
  [v]
  (insert-child v))

(defn portal-node? [x]
  (and (instance? clojure.lang.IAtom x)
       (= :portal (:node/type @x))))

(defn- handler-prop?
  "Event-handler props are consumed as callbacks, never as reactive
  accessors — mirrors solid-js/h's on* rule."
  [k]
  (str/starts-with? (name k) "on"))

(defn- apply-prop! [el k v]
  (cond
    ;; ref: called once with the element node (the JVM analog of the
    ;; DOM element); not stored — it isn't an attribute.
    (= k :ref)
    (v el)

    (handler-prop? k)
    (swap! el update :props assoc k v)

    ;; reactive accessor → per-prop effect
    (fn? v)
    (create-effect (fn [] (swap! el update :props assoc k (v))))

    ;; :style / :classList maps: static entries once, fn entries get
    ;; per-entry effects
    (map? v)
    (do (swap! el update :props assoc k
               (reduce-kv (fn [m kk vv] (if (fn? vv) m (assoc m kk vv))) {} v))
        (doseq [[kk vv] v :when (fn? vv)]
          (create-effect (fn [] (swap! el update-in [:props k] assoc kk (vv))))))

    :else
    (swap! el update :props assoc k v)))

(defn- make-element [tag props children]
  (let [el (atom {:node/type :element :tag tag :props {} :children []})]
    (doseq [[k v] props]
      (apply-prop! el k v))
    (swap! el assoc :children (mapv insert-child children))
    el))

(defn- make-component
  "Run a component fn once under its own owner, untracked (component
  bodies are not tracking scopes). Children, when present ([:> comp …]
  usage), are passed via :children in props, like Solid."
  [comp props children]
  (let [node (make-node)]
    (adopt! node)
    (binding [*owner* node *listener* nil]
      (let [props (if (seq children)
                    (assoc (or props {}) :children (mapv insert-child children))
                    props)
            v     (comp props)]
        (if (fn? v) (insert-dynamic v) v)))))

;; ---- Control-flow components ----------------------------------------------------

(def For           ::For)
(def Index         ::Index)
(def Show          ::Show)
(def Switch        ::Switch)
(def Match         ::Match)
(def Suspense      ::Suspense)
(def ErrorBoundary ::ErrorBoundary)
(def Dynamic       ::Dynamic)
(def Portal        ::Portal)

(def fragment ::fragment)

(defn js-truthy?
  "Show/Match conditions are evaluated by REAL Solid with JS
  truthiness, where 0, \"\" and NaN are falsy. Mirror that here so
  {:when count} behaves the same in tests as in the browser — CLJ
  truthiness would silently diverge. Public: the snapshot serializer
  uses it for :classList entries too."
  [v]
  (cond
    (nil? v)    false
    (false? v)  false
    (number? v) (let [d (double v)]
                  (not (or (zero? d) (Double/isNaN d))))
    (string? v) (not= "" v)
    :else       true))

(defn- make-show
  "[:show]: an effect swaps the slot between the (eagerly built)
  children and the fallback. Static children are reused across
  toggles; fn children/fallbacks get fresh slots per toggle (their
  effects are owned by the Show effect, cleaned on each swap)."
  [props children]
  (let [when-fn  (:when props)
        fallback (:fallback props)
        children (vec children)
        slot     (atom {:node/type :slot :content nil})]
    (create-effect
     (fn []
       (swap! slot assoc :content
              (if (js-truthy? (when-fn))
                (mapv insert-child children)
                (insert-child fallback)))))
    slot))

(defn- make-switch
  "[:switch]: matches evaluated in order inside one effect; `some`
  stops at the first truthy :when, so later conditions aren't
  subscribed until an earlier one turns false (Solid's short-circuit)."
  [props matches]
  (let [fallback (:fallback props)
        matches  (vec matches)
        _        (doseq [m matches]
                   (when-not (= :match (:node/type m))
                     (throw (ex-info "solidclj.runtime: children of Switch must be Match nodes"
                                     {:received m}))))
        slot     (atom {:node/type :slot :content nil})]
    (create-effect
     (fn []
       (let [hit (some #(when (js-truthy? ((:when %))) %) matches)]
         (swap! slot assoc :content
                (if hit
                  (mapv insert-child (:children hit))
                  (insert-child fallback))))))
    slot))

(defn- make-dynamic
  "[:dynamic]: re-render under the effect when the :component getter's
  value changes. A string resolves like an element tag, a fn like a
  component; walked children are reused across swaps."
  [props children]
  (let [comp-fn    (:component props)
        rest-props (dissoc props :component)
        children   (vec children)
        slot       (atom {:node/type :slot :content nil})]
    (create-effect
     (fn []
       (let [c (comp-fn)]
         (swap! slot assoc :content
                (cond
                  (nil? c)    nil
                  (string? c) (make-element c rest-props children)
                  (fn? c)     (make-component c rest-props children)
                  :else       c)))))
    slot))

(defn- make-portal
  "[:portal]: no foreign DOM to mount into on the JVM — children
  render inline under a marker node so snapshots can distinguish
  portal content. :mount stays an unresolved getter."
  [props children]
  (atom {:node/type :portal
         :mount     (:mount props)
         :children  (mapv insert-child children)}))

(defn- make-error-boundary
  "[:error-boundary]: the walker (JVM branch) passes a single builder
  thunk that walks the children; running it inside the boundary's
  effect means component bodies execute HERE, and a build-time throw
  renders the fallback with (err, reset). `reset` clears the error
  signal and re-attempts the build from scratch (components
  re-instantiate). Update-time errors (inside effects, triggered by
  later writes) are NOT caught — they propagate to the swap! caller,
  which in a test is exactly where you want them."
  [props children]
  (let [fb    (:fallback props)
        build (first children)   ;; 0-arg fn → vector of walked children
        slot  (atom {:node/type :slot :content nil})
        [get-err set-err] (create-signal nil)
        reset-fn (fn [] (set-err (fn [_] nil)))]
    (create-effect
     (fn []
       (if-some [err (get-err)]
         (swap! slot assoc :content
                (insert-child
                 (cond
                   (nil? fb) nil
                   ;; walker wraps fn fallbacks as (fn [err reset] …);
                   ;; an atom-held fallback is a 0-arg thunk instead.
                   (fn? fb)  (try (fb err reset-fn)
                                  (catch clojure.lang.ArityException _ (fb)))
                   :else     fb)))
         ;; Attempt the build WITHOUT writing the slot on failure:
         ;; set-err re-runs this effect synchronously (re-entrant), so
         ;; the nested run installs the fallback — writing nil here
         ;; afterwards would clobber it.
         (let [result (try {:ok (mapv insert-child (build))}
                           (catch Throwable t {:error t}))]
           (if-some [t (:error result)]
             (set-err (fn [_] t))
             (swap! slot assoc :content (:ok result)))))))
    slot))

;; ---- Keyed lists: For / Index ------------------------------------------------
;; Rows are DETACHED owners (Solid's mapArray uses manual roots): they
;; must survive re-runs of the For/Index effect, whose clean-node!
;; would otherwise dispose them on every update. The effect disposes
;; rows itself when items leave, and an on-cleanup on the enclosing
;; owner tears everything down when the list unmounts.

(defn- make-row
  "Build one row's content under its own detached owner, untracked
  (Solid runs the render fn untracked — reads inside row thunks
  subscribe row effects, never the list effect)."
  [build]
  (let [owner (make-node)]
    {:owner   owner
     :content (binding [*owner* owner *listener* nil]
                (let [v (build)]
                  (if (fn? v) (insert-dynamic v) v)))}))

(defn- make-for
  "[:for]: rows keyed by referential identity (Solid keys on ===).
  Reused rows keep their owner (row-local state survives) and get
  their index signal updated; duplicate identities are matched
  first-come first-served; departed rows are disposed."
  [props render-fn]
  (let [each    (:each props)
        each-fn (if (fn? each) each (fn [] each))
        slot    (atom {:node/type :slot :content []})
        rows*   (atom [])]
    (on-cleanup (fn [] (doseq [r @rows*] (dispose! (:owner r)))))
    (create-effect
     (fn []
       (let [items (vec (each-fn))
             pool  (java.util.IdentityHashMap.)]
         (doseq [r @rows*]
           (.put pool (:item r) (conj (or (.get pool (:item r)) []) r)))
         (let [take-row! (fn [item]
                           (when-let [rs (seq (.get pool item))]
                             (.put pool item (vec (rest rs)))
                             (first rs)))
               new-rows  (vec
                          (map-indexed
                           (fn [i item]
                             (if-some [r (take-row! item)]
                               (do ((:set-index r) (fn [_] i)) r)
                               (let [[get-i set-i] (create-signal i =)]
                                 (assoc (make-row #(render-fn item get-i))
                                        :item item :set-index set-i))))
                           items))]
           (doseq [rs (.values pool), r rs]
             (dispose! (:owner r)))
           (reset! rows* new-rows)
           (swap! slot assoc :content (mapv :content new-rows))))))
    slot))

(defn- make-index
  "[:index]: rows keyed by POSITION. Each position holds an item
  signal; updates write through it (compared with identical?,
  mirroring Solid's ===), the list only grows/shrinks at the tail."
  [props render-fn]
  (let [each    (:each props)
        each-fn (if (fn? each) each (fn [] each))
        slot    (atom {:node/type :slot :content []})
        rows*   (atom [])]
    (on-cleanup (fn [] (doseq [r @rows*] (dispose! (:owner r)))))
    (create-effect
     (fn []
       (let [items (vec (each-fn))
             old   @rows*
             n-new (count items)
             kept  (vec
                    (map-indexed
                     (fn [i row]
                       ((:set-item row) (fn [_] (nth items i)))
                       row)
                     (take n-new old)))
             grown (vec
                    (for [i (range (count old) n-new)]
                      (let [[get-item set-item] (create-signal (nth items i) identical?)]
                        (assoc (make-row #(render-fn get-item i))
                               :set-item set-item))))]
         (doseq [r (drop n-new old)]
           (dispose! (:owner r)))
         (let [rows (into kept grown)]
           (reset! rows* rows)
           (swap! slot assoc :content (mapv :content rows))))))
    slot))

;; ---- h -------------------------------------------------------------------------

(defn h
  "Hyperscript entry point (the JVM twin of solid-js/h):
   - string tag        → element node
   - fn                → component (runs once under a fresh owner)
   - fragment          → children as a vector
   - control-flow value → the corresponding simulator implementation
     (Suspense is a pass-through stub: children render, no resource
     timing)"
  [tag & [props & children]]
  (cond
    (string? tag)        (make-element tag props children)
    (= fragment tag)     (mapv insert-child children)
    (= Show tag)         (make-show props children)
    (= Switch tag)       (make-switch props children)
    (= Match tag)        {:node/type :match
                          :when      (:when props)
                          :children  (vec children)}
    (= For tag)          (make-for props (first children))
    (= Index tag)        (make-index props (first children))
    (= Dynamic tag)      (make-dynamic props children)
    (= Portal tag)       (make-portal props children)
    (= Suspense tag)     (mapv insert-child children)
    (= ErrorBoundary tag) (make-error-boundary props children)
    (fn? tag)            (make-component tag props children)
    :else
    (throw (ex-info "solidclj.runtime/h: unsupported tag" {:tag tag}))))

;; ---- Data boundary --------------------------------------------------------------
;; The walker builds pure CLJ data; on the JVM it stays CLJ data.

(defn ->props
  "Identity on the JVM — the simulator consumes CLJ maps directly."
  [m]
  m)

(defn lazy-prop!
  "Attach `getter` (a zero-arg fn) as a lazily-read prop. On the JVM
  props are immutable maps, so this is a plain assoc — callers must
  use the return value. The simulator treats fn-valued props as
  accessors, read inside a tracking scope."
  [props k getter]
  (assoc props k getter))

(defn ->children
  "Children-collection boundary: a vector on the JVM."
  [xs]
  (vec xs))

(defn ->each
  "The [:for]/[:index] :each boundary: a vector on the JVM (item
  identities preserved)."
  [coll]
  (if (nil? coll) [] (vec coll)))

;; ---- Dev ergonomics --------------------------------------------------------------

(def debug?
  "Dev-time structural checks and warnings are always on for the JVM
  runtime — its whole purpose is tests."
  true)

(def ^:dynamic *warn*
  "Rebindable warning sink so tests can capture or silence warnings."
  (fn [msg] (binding [*out* *err*] (println msg))))

(defn warn! [msg] (*warn* msg))
