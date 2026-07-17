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
  (:require [flowdom.rx :as rx]
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

(defn normalize
  "Element vector to [tag props children] with seqs (e.g. from `map`)
  spliced into the child list."
  [v]
  (let [[t & r] v
        [p ch]  (if (props-map? (first r)) [(first r) (rest r)] [nil r])
        ch      (into [] (mapcat #(if (seq? %) % [%])) ch)]
    [t (or p {}) ch]))

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

(defn- assemble [tag props resolved-children]
  (let [head (if (seq props) [tag props] [tag])]
    (reduce (fn [acc c]
              (if (splice? c) (into acc c) (conj acc c)))
            head
            resolved-children)))

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
    (and (element-vec? v) (= :error-boundary (first v))) (interpret-boundary v)
    (element-vec? v)   (interpret-element v)
    (fn? v)            (throw (ex-info "flowdom: bare functions are not valid tree content — components go in vectors [f args], dynamic values in (rx ...)"
                                       {:got v}))
    :else v))

;; ---------------------------------------------------------------------------
;; JVM renderer: the spine is the renderer; sample it, block on it

#?(:clj
   (defn render
     "Mount `hiccup`: interprets it and runs the spine. Returns a handle
  for `snapshot`/`await`; call (:cancel handle) — or use `with-render` —
  to tear every process down."
     [hiccup]
     (let [node  (interpret hiccup)
           state (atom {:value (if (rx/rx? node) ::none node)
                        :waiters []})
           check-waiters!
           (fn [v]
             (let [ws (:waiters @state)]
               (doseq [{:keys [pred prom]} ws]
                 (when (try (pred v) (catch Throwable _ false))
                   (deliver prom v)))
               (swap! state update :waiters
                      (fn [ws] (into [] (remove #(realized? (:prom %))) ws)))))
           cancel
           (if (rx/rx? node)
             ((m/reduce (fn [_ v]
                          (swap! state assoc :value v)
                          (check-waiters! v)
                          nil)
                        nil (rx/unwrap node))
              (fn [_] nil)
              (fn [e] (swap! state assoc :value (rx/->Err e))))
             (fn []))]
       {:state state :cancel cancel})))

#?(:clj
   (defn snapshot
     "The rendered tree as plain hiccup at this instant. Handler fns are
  preserved in props (call them from tests, then snapshot again).
  Returns the `flowdom.rx/pending` marker while the root is pending;
  throws if an uncaught error reached the root."
     [handle]
     (let [v (:value @(:state handle))]
       (cond
         (identical? v ::none) nil
         (rx/err? v)           (throw (:error v))
         :else                 v))))

#?(:clj
   (defn await
     "Block until the rendered tree satisfies `pred`, returning that
  snapshot; throws on timeout (default 2000 ms). For trees fed by
  flows emitting from other threads — synchronous tests don't need it."
     [handle pred & {:keys [timeout] :or {timeout 2000}}]
     (let [prom    (promise)
           current (:value @(:state handle))]
       (if (and (not (identical? current ::none))
                (not (rx/err? current))
                (try (pred current) (catch Throwable _ false)))
         current
         (do (swap! (:state handle) update :waiters conj {:pred pred :prom prom})
             ;; re-check: an emission may have landed between read and register
             (let [v (:value @(:state handle))]
               (when (and (not (identical? v ::none)) (not (rx/err? v))
                          (try (pred v) (catch Throwable _ false)))
                 (deliver prom v)))
             (let [v (deref prom timeout ::timeout)]
               (if (identical? v ::timeout)
                 (throw (ex-info "flowdom: await timed out"
                                 {:timeout timeout
                                  :last (:value @(:state handle))}))
                 v)))))))

#?(:clj
   (defmacro with-render
     "Render `hiccup`, bind the handle, run `body`, always tear down:

      (with-render [t [counter {:start 5}]]
        (is (= [:div [:span 5]] (snapshot t))))"
     [[sym hiccup] & body]
     `(let [~sym (render ~hiccup)]
        (try ~@body (finally ((:cancel ~sym)))))))
