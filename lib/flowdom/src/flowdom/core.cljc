(ns flowdom.core
  "The interpreter: hiccup with rx values embedded, to a value flow.

  Components are plain functions, called once, returning hiccup.
  Reactivity lives in the rx values embedded in the data — the
  interpreter turns each one into a slot and composes a continuous
  flow of the whole tree (the spine). On the JVM this is the renderer:
  `render` mounts the spine, `snapshot` samples it, `await` blocks on
  it. In the browser, flowdom.dom attaches DOM patching to the same
  hiccup grammar.

  Grammar:
    [:tag props? & children]      element (props map optional)
    [component-fn & args]         call the fn once, interpret the result
    (rx ...)                      dynamic position (child or prop value)
    (for-by key-fn items body)    keyed collection
    [:error-boundary {:fallback (fn [err retry] ...)} child]
    anything else                 static content

  Pending: an element with a `:fallback` prop renders the fallback in
  place of any child position whose rx is pending. Without a fallback,
  pending propagates upward (the whole subtree is pending).

  Errors: a throwing rx body propagates upward as a value until an
  `:error-boundary` catches it and renders its fallback; `retry`
  remounts the boundary's subtree."
  (:refer-clojure :exclude [await])
  #?(:cljs (:require-macros [flowdom.core]))
  (:require [clojure.string :as string]
            [flowdom.rx :as rx]
            [missionary.core :as m]))

;; ---------------------------------------------------------------------------
;; grammar helpers (shared with flowdom.dom)

(defrecord ForBy [key-fn items body])

(defn for-by
  "Keyed collection. `items` is a reactive source (atom, flow, rx) or a
  plain vector; `key-fn` identifies items across emissions; `body` is
  called ONCE per key with a read-only atom-like holding that item's
  latest value — read it with `?` inside an rx:

      (for-by :id todos
        (fn [todo] [:li (rx (:title (? todo)))]))

  New keys mount, departed keys unmount, surviving keys keep their
  processes and state; an item whose value changed ticks only its own
  slot."
  [key-fn items body]
  (->ForBy key-fn items body))

(defn for-by? [x] (instance? ForBy x))

(defn props-map? [x] (and (map? x) (not (record? x))))

(defn element-vec? [v] (and (vector? v) (keyword? (first v))))

(defn component-vec? [v] (and (vector? v) (fn? (first v))))

(defn class-str
  "Normalize a :class value — string, keyword, sequential, or a map of
  class → truthiness — to a class string."
  [v]
  (cond
    (nil? v)        nil
    (string? v)     v
    (keyword? v)    (name v)
    (map? v)        (->> v
                         (keep (fn [[k on?]]
                                 (when on? (if (keyword? k) (name k) (str k)))))
                         (string/join " "))
    (sequential? v) (->> v (keep class-str) (string/join " "))
    :else           (str v)))

(defn parse-tag
  "Keyword tag sugar: :p.cls-a.cls-b#some-id → [:p \"cls-a cls-b\" \"some-id\"].
  Class and id are nil when absent."
  [k]
  (let [toks (re-seq #"[#.]?[^#.]+" (name k))
        tag  (first toks)
        cls  (keep #(when (string/starts-with? % ".") (subs % 1)) (rest toks))
        id   (some #(when (string/starts-with? % "#") (subs % 1)) (rest toks))]
    [(keyword tag)
     (when (seq cls) (string/join " " cls))
     id]))

(defn normalize
  "Element vector to [tag props children]: keyword sugar classes/id
  merge into props, seqs (e.g. from `map`) splice into the child list."
  [v]
  (let [[t & r] v
        [p ch]  (if (props-map? (first r)) [(first r) (rest r)] [nil r])
        ch      (into [] (mapcat #(if (seq? %) % [%])) ch)
        [tag cls id] (parse-tag t)
        p       (or p {})
        p       (if (and id (not (contains? p :id))) (assoc p :id id) p)
        p       (if cls
                  (let [existing (:class p)]
                    (assoc p :class
                           (cond
                             (nil? existing)     cls
                             (rx/rx? existing)   (rx/rx* #(str cls " " (class-str (rx/? existing))))
                             :else               (str cls " " (class-str existing)))))
                  p)]
    [tag p ch]))

(defn splice?
  "For-by assemblies emit their children as a vector tagged for
  splicing into the parent's child list."
  [v]
  (boolean (and (vector? v) (::splice (meta v)))))

;; ---------------------------------------------------------------------------
;; interpretation — every dynamic node is itself an rx, so cells persist
;; across re-runs and teardown is cancellation

(declare interpret)

(defn- read-node
  "Read an interpreted node inside an assembly rx: static nodes pass
  through, dynamic ones are read with ? (pending/errors propagate)."
  [node]
  (if (rx/rx? node) (rx/? node) node))

(defn- slot
  "A user rx in a tree position: interpret each emitted value (memoized,
  so inner ticks don't remount), read the interpreted node through."
  [content]
  (let [memo (atom nil)]
    (rx/rx*
     (fn []
       (let [v    (rx/? content)
             node (let [mm @memo]
                    (if (and mm (= (:v mm) v))
                      (:node mm)
                      (let [n (interpret v)]
                        (reset! memo {:v v :node n})
                        n)))]
         (read-node node))))))

(defn- resolve-child [fb child]
  (let [v (if (rx/rx? child)
            (if fb
              (try (rx/? child)
                   (catch #?(:clj Throwable :cljs :default) e
                     (if (rx/pending-ex? e) (read-node fb) (throw e))))
              (rx/? child))
            child)]
    v))

(defn- push-child [acc c]
  (if (splice? c) (reduce push-child acc c) (conj acc c)))

(defn- assemble [tag props resolved-children]
  (let [props (if (contains? props :class)
                (update props :class class-str)
                props)
        head  (if (seq props) [tag props] [tag])]
    (reduce push-child head resolved-children)))

(defn- interpret-fragment
  "[:<> & children] — children splice into the parent, no wrapper."
  [v]
  (let [[_ _ children] (normalize v)
        kids (mapv interpret children)]
    (if (not-any? rx/rx? kids)
      (with-meta (reduce push-child [] kids) {::splice true})
      (rx/rx*
       (fn []
         (with-meta (reduce push-child [] (mapv #(resolve-child nil %) kids))
           {::splice true}))))))

(defn- interpret-element [v]
  (let [[tag props children] (normalize v)
        fb-form   (:fallback props)
        props     (dissoc props :fallback)
        fb        (when (some? fb-form) (interpret fb-form))
        kids      (mapv interpret children)
        dyn-props (into {} (filter (comp rx/rx? val)) props)
        stat-props (reduce dissoc props (keys dyn-props))]
    (if (and (empty? dyn-props) (not-any? rx/rx? kids) (nil? fb))
      (assemble tag stat-props kids)
      (rx/rx*
       (fn []
         (let [p (reduce-kv (fn [acc k pv] (assoc acc k (rx/? pv)))
                            stat-props dyn-props)]
           (assemble tag p (mapv #(resolve-child fb %) kids))))))))

(defn- interpret-boundary [v]
  (let [[_ props children] (normalize v)
        fallback (or (:fallback props)
                     (fn [err _] [:div {:class "flowdom-error"} (str err)]))
        _ (when (not= 1 (count children))
            (throw (ex-info "flowdom: :error-boundary takes exactly one child"
                            {:children (count children)})))
        child (first children)
        gen   (atom 0)
        memo  (atom nil)]
    (rx/rx*
     (fn []
       (let [g    (rx/? gen)
             ;; child interpretation is cached per generation — an error
             ;; renders the fallback but never remounts by itself, so a
             ;; dependency change can heal the same subtree in place;
             ;; only retry (gen bump) re-interprets.
             node (let [mm @memo]
                    (if (and mm (= (:gen mm) g))
                      (:node mm)
                      (let [n (interpret child)]
                        (reset! memo {:gen g :node n})
                        n)))]
         (try
           (read-node node)
           (catch #?(:clj Throwable :cljs :default) e
             (if (rx/pending-ex? e)
               (throw e)
               (let [mm @memo
                     fb-node (if (and mm (= (:gen mm) g) (identical? (:err mm) e))
                               (:fb mm)
                               (let [n (interpret
                                        (fallback e (fn retry [] (swap! gen inc))))]
                                 (swap! memo assoc :err e :fb n)
                                 n))]
                 (read-node fb-node))))))))))

(defn- read-items [items]
  (if (vector? items) items (rx/? items)))

(defn- interpret-for [{:keys [key-fn items body]}]
  (let [cache (atom {})]
    (rx/rx*
     (fn []
       (let [xs (vec (read-items items))
             ks (mapv key-fn xs)]
         (when (and (seq ks) (not (apply distinct? ks)))
           (throw (ex-info "flowdom: for-by keys must be distinct" {:keys ks})))
         (let [prev @cache
               next (reduce
                     (fn [acc [k x]]
                       (if-let [e (get prev k)]
                         (do (when (not= @(:item e) x)
                               (reset! (:item e) x))
                             (assoc acc k e))
                         (let [ia (atom x)]
                           (assoc acc k {:item ia
                                         :node (interpret (body ia))}))))
                     {}
                     (map vector ks xs))]
           (reset! cache next)
           (with-meta
             (mapv (fn [k] (read-node (:node (get next k)))) ks)
             {::splice true})))))))

(defn interpret
  "Hiccup (with rx values embedded) to a node: a plain value when fully
  static, otherwise an Rx whose flow carries the assembled subtree."
  [v]
  (cond
    (rx/rx? v)         (slot v)
    (for-by? v)        (interpret-for v)
    (component-vec? v) (interpret (apply (first v) (rest v)))
    (and (element-vec? v) (= :<> (first v))) (interpret-fragment v)
    ;; portals are a DOM concern; the spine renders content in place
    ;; under a [:portal] marker (the :mount element is not data — drop it)
    (and (element-vec? v) (= :portal (first v)))
    (let [[_ props children] (normalize v)
          props (dissoc props :mount)]
      (interpret-element (into (if (seq props) [:portal props] [:portal]) children)))
    (and (element-vec? v) (= :error-boundary (first v))) (interpret-boundary v)
    (element-vec? v)   (interpret-element v)
    (fn? v)            (throw (ex-info "flowdom: bare functions are not valid tree content — components go in vectors [f args], dynamic values in (rx ...)"
                                       {:got v}))
    :else v))

(defn expand
  "Pre-run every statically-reachable component fn in `hiccup`, splicing
  the results in. The point: two consumers (say, a DOM mount and a
  spine) can then walk the SAME expanded tree — the components ran
  once, HERE, so their rx blocks, local atoms, and handlers are one
  shared instance with two views.

  What stays per-consumer: content inside dynamic positions. Components
  that appear in rx emissions or for-by bodies are instantiated by
  whichever consumer interprets that emission, so slot-local component
  state is not shared across consumers — ns-level state always is.
  :error-boundary children are also left unexpanded: retry's contract
  is a fresh remount, which pre-running the component would defeat."
  [v]
  (cond
    (component-vec? v)
    (expand (apply (first v) (rest v)))

    (and (element-vec? v)
         (not (contains? #{:error-boundary} (first v))))
    (let [[t & r] v
          [p ch]  (if (props-map? (first r)) [(first r) (rest r)] [nil r])]
      (into (if p [t p] [t]) (map expand) ch))

    (seq? v) (doall (map expand v))

    :else v))

;; ---------------------------------------------------------------------------
;; consumers: hold the tree, sample it, block on it
;;
;; The interpreted tree IS a missionary flow; these are the three
;; consumers tests want by name, each derived from it. `render` holds
;; the tree running — the signal is refcounted, so with no standing
;; subscriber a sample would mount and unmount the whole app per call —
;; `snapshot` samples it, `await` blocks until a sample satisfies a
;; predicate. All of them work on any handle with a running :tree.

(def ^:private nothing
  #?(:clj (Object.) :cljs (js-obj)))

(defn render
  "Mount `hiccup`: interprets it and holds the tree flow running.
  Returns a handle {:tree flow :cancel fn} for `snapshot`/`await`;
  call (:cancel handle) — or use `with-render` — to tear every
  process down."
  [hiccup]
  (let [node (interpret hiccup)]
    {:tree   node
     :cancel (if (rx/rx? node)
               ((m/reduce (fn [_ _] nil) nil (rx/unwrap node))
                (fn [_] nil) (fn [_] nil))
               (fn []))}))

(defn snapshot
  "One sample of the tree flow: the rendered tree as plain hiccup at
  this instant. Handler fns are preserved in props (call them from
  tests, then snapshot again). Returns the `flowdom.rx/pending` marker
  while the root is pending; throws if an uncaught error reached the
  root. Cross-platform — works on a JVM `render` handle and on a
  browser spine-mode `mount` handle alike."
  [handle]
  (let [node (:tree handle)]
    (if-not (rx/rx? node)
      node
      (let [captured (volatile! nothing)
            ;; a running signal hands a late subscriber its current
            ;; value synchronously; (reduced v) ends the sampler there
            cancel   ((m/reduce (fn [_ v] (vreset! captured v) (reduced v))
                                nil (rx/unwrap node))
                      (fn [_] nil) (fn [_] nil))
            v        @captured]
        (when (identical? v nothing)
          (cancel)) ;; no synchronous transfer — don't leak the sampler
        (cond
          (identical? v nothing) nil
          (rx/err? v)            (throw (:error v))
          :else                  v)))))

#?(:clj
   (defn await
     "Block until a sample of the tree satisfies `pred`, returning it;
  throws on timeout (default 2000 ms). A reduce-until-predicate over
  the tree flow — for trees fed by flows emitting from other threads;
  synchronous tests don't need it. Error values are skipped, pending
  markers are offered to `pred` like any other sample."
     [handle pred & {:keys [timeout] :or {timeout 2000}}]
     (let [node (:tree handle)
           ok?  (fn [v] (and (not (rx/err? v))
                             (try (boolean (pred v)) (catch Throwable _ false))))]
       (if-not (rx/rx? node)
         (if (ok? node)
           node
           (throw (ex-info "flowdom: await on a static tree that will never match"
                           {:tree node})))
         (let [prom   (promise)
               cancel ((m/reduce (fn [_ v] (if (ok? v) (reduced v) nothing))
                                 nil (rx/unwrap node))
                       (fn [acc] (deliver prom acc))
                       (fn [_]   (deliver prom nothing)))
               v      (deref prom timeout ::timeout)]
           (cond
             (= v ::timeout)
             (do (cancel)
                 (throw (ex-info "flowdom: await timed out"
                                 {:timeout timeout
                                  :last    (snapshot handle)})))

             (identical? v nothing)
             (throw (ex-info "flowdom: tree flow ended before matching" {}))

             :else v))))))

#?(:clj
   (defmacro with-render
     "Render `hiccup`, bind the handle, run `body`, always tear down:

      (with-render [t [counter {:start 5}]]
        (is (= [:div [:span 5]] (snapshot t))))"
     [[sym hiccup] & body]
     `(let [~sym (render ~hiccup)]
        (try ~@body (finally ((:cancel ~sym)))))))
