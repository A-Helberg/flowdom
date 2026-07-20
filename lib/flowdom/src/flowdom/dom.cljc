(ns flowdom.dom
  "The patch consumer: mount hiccup (with rx values embedded) onto real
  DOM. Same grammar as flowdom.core; instead of composing a value flow,
  each dynamic position spawns a small process that patches its own DOM
  region in place when its rx emits.

  - scalar slots update a text node's data in place
  - structural slots replace the nodes between their comment markers,
    cancelling the old content's processes (unmount = cancellation)
  - for-by keeps one mounted range per key: item updates tick in place,
    reorders move DOM nodes without touching processes, removals cancel
  - pending renders the nearest `:fallback`; errors reach the nearest
    :error-boundary, whose retry remounts its subtree

  Propagation is synchronous: a swap! has patched the DOM by the time
  it returns. (Frame batching is future work — see the design doc.)

  CLJS only; the file is .cljc so the shared namespace loads on the JVM
  (where flowdom.core is the renderer), but `mount` is browser-side."
  (:require [clojure.string :as str]
            [flowdom.core :as fd]
            [flowdom.rx :as rx]
            [missionary.core :as m]))

#?(:cljs
   (do

;; ---------------------------------------------------------------------------
;; small DOM helpers

     (defn- dispose-all! [arr]
       (loop []
         (when-let [f (.pop arr)]
           (f)
           (recur))))

     (defn- clear-range!
       "Remove the nodes strictly between `start` and `end`."
       [start end]
       (loop []
         (let [n (.-nextSibling start)]
           (when-not (identical? n end)
             (.remove n)
             (recur)))))

     (defn- remove-range!
       "Remove `start`..`end` inclusive."
       [start end]
       (loop [n start]
         (let [nx (.-nextSibling n)]
           (.remove n)
           (when-not (identical? n end)
             (recur nx)))))

     (defn- move-range!
       "Move `start`..`end` inclusive before `anchor` (same parent)."
       [parent start end anchor]
       (loop [n start]
         (let [nx (.-nextSibling n)]
           (.insertBefore parent n anchor)
           (when-not (identical? n end)
             (recur nx)))))

     (defn- primitive? [v] (or (string? v) (number? v)))

;; ---------------------------------------------------------------------------
;; props

     (defn- handler-key? [k] (str/starts-with? (name k) "on"))

     (defn- event-name [k]
       (-> (name k) (subs 2) (str/replace #"^-" "") str/lower-case))

     (defn- set-prop! [el k v]
       (cond
         (= k :class)
         (set! (.-className el) (str (or (fd/class-str v) "")))

         (= k :innerHTML)
         (set! (.-innerHTML el) (str v))

         (= k :style)
         (if (map? v)
           (doseq [[sk sv] v] (.setProperty (.-style el) (name sk) (str sv)))
           (.setAttribute el "style" (str v)))

         (or (= k :value) (= k :checked))
         (aset el (name k) v)

         (or (nil? v) (false? v))
         (.removeAttribute el (name k))

         (true? v)
         (.setAttribute el (name k) "")

         :else
         (.setAttribute el (name k) (str v))))

;; ---------------------------------------------------------------------------
;; processes

     (defn- report-error! [ctx e]
       (if-let [f (:on-error ctx)]
         (f e)
         (js/console.error "flowdom: uncaught error outside any :error-boundary" e)))

     (defn- spawn!
       "Run a consumer process over a flow; on-value per emission. Returns a
  cancel thunk. Failures after cancellation are teardown noise, not
  errors — the stopped flag swallows them."
       [flow on-value on-fail]
       (let [stopped (volatile! false)
             cancel  ((m/reduce (fn [_ v] (on-value v) nil) nil (rx/unwrap flow))
                      (fn [_] nil)
                      (fn [e] (when-not @stopped (on-fail e))))]
         (fn []
           (vreset! stopped true)
           (cancel))))

     (declare mount-child)

     (defn- mount-children [parent anchor ctx children]
       (let [ds (array)]
         (doseq [c children]
           (.push ds (mount-child parent anchor ctx c)))
         (fn [] (dispose-all! ds))))

;; ---------------------------------------------------------------------------
;; slots

     (defn- mount-slot [parent anchor ctx content]
       (let [start  (.createComment (.-ownerDocument parent) "slot")
             end    (.createComment (.-ownerDocument parent) "/slot")
             inner  (array)
             text   (volatile! nil) ;; current single text node, when scalar
             render!
             (fn [v]
               (if (and (some? @text) (primitive? v))
                 (set! (.-data @text) (str v))
                 (do (dispose-all! inner)
                     (clear-range! start end)
                     (if (primitive? v)
                       (let [tn (.createTextNode (.-ownerDocument parent) (str v))]
                         (.insertBefore parent tn end)
                         (vreset! text tn))
                       (do (vreset! text nil)
                           (.push inner (mount-child parent end ctx v)))))))
             patch!
             (fn [v]
               (cond
                 (rx/pending-value? v) (render! (:fallback ctx))
                 (rx/err? v)           (report-error! ctx (:error v))
                 :else                 (render! v)))]
         (.insertBefore parent start anchor)
         (.insertBefore parent end anchor)
         (let [cancel (spawn! content patch! (fn [e] (report-error! ctx e)))]
           (fn []
             (cancel)
             (dispose-all! inner)))))

;; ---------------------------------------------------------------------------
;; elements

     (defn- spawn-prop! [el k pv ctx]
       (spawn! pv
               (fn [v]
                 (cond
                   (rx/pending-value? v) nil
                   (rx/err? v)           (report-error! ctx (:error v))
                   :else                 (set-prop! el k v)))
               (fn [e] (report-error! ctx e))))

     (defn- mount-element [parent anchor ctx v]
       (let [[tag props children] (fd/normalize v)
             fb       (:fallback props)
             ctx      (if (some? fb) (assoc ctx :fallback fb) ctx)
             on-mount (:on-mount props)
             props    (dissoc props :fallback :on-mount)
             el       (.createElement (.-ownerDocument parent) (name tag))
             ds       (array)]
         (doseq [[k pv] props]
           (cond
             (handler-key? k) (.addEventListener el (event-name k) pv)
             (rx/rx? pv)      (.push ds (spawn-prop! el k pv ctx))
             :else            (set-prop! el k pv)))
         (.push ds (mount-children el nil ctx children))
         (.insertBefore parent el anchor)
         ;; :on-mount runs once with the now-connected element; a fn it
         ;; returns is its teardown, cancelled with the element's processes.
         (when on-mount
           (let [teardown (on-mount el)]
             (when (fn? teardown) (.push ds teardown))))
         (fn [] (dispose-all! ds))))

;; ---------------------------------------------------------------------------
;; for-by

     (defn- items-flow [items]
       (cond
         (rx/rx? items)                 (rx/unwrap items)
         (satisfies? IWatchable items)  (m/watch items)
         :else                          items))

     (defn- mount-for [parent anchor ctx {:keys [key-fn items body]}]
       (let [start (.createComment (.-ownerDocument parent) "for")
             end   (.createComment (.-ownerDocument parent) "/for")
             cache (volatile! {})
             patch!
             (fn [v]
               (cond
                 (rx/pending-value? v) nil
                 (rx/err? v) (report-error! ctx (:error v))
                 :else
                 (let [xs (vec v)
                       ks (mapv key-fn xs)
                       prev @cache]
              ;; unmount departed keys
                   (doseq [[k e] prev]
                     (when-not (some #(= k %) ks)
                       ((:dispose e))
                       (remove-range! (:start e) (:end e))))
              ;; create/update, then walk into position
                   (let [next
                         (loop [pos start, pairs (map vector ks xs), acc {}]
                           (if-let [[k x] (first pairs)]
                             (let [e (get prev k)]
                               (if e
                                 (do (when (not= @(:item e) x)
                                       (reset! (:item e) x))
                                     (when-not (identical? (.-nextSibling pos) (:start e))
                                       (move-range! parent (:start e) (:end e)
                                                    (.-nextSibling pos)))
                                     (recur (:end e) (rest pairs) (assoc acc k e)))
                                 (let [ia (atom x)
                                       s  (.createComment (.-ownerDocument parent) "item")
                                       e2 (.createComment (.-ownerDocument parent) "/item")
                                       at (.-nextSibling pos)]
                                   (.insertBefore parent s at)
                                   (.insertBefore parent e2 at)
                                   (let [d (mount-child parent e2 ctx (body ia))]
                                     (recur e2 (rest pairs)
                                            (assoc acc k {:item ia :start s :end e2
                                                          :dispose d}))))))
                             acc))]
                     (vreset! cache next)))))]
         (.insertBefore parent start anchor)
         (.insertBefore parent end anchor)
         (if (vector? items)
           (do (patch! items)
               (fn []
                 (doseq [[_ e] @cache] ((:dispose e)))))
           (let [cancel (spawn! (items-flow items) patch!
                                (fn [e] (report-error! ctx e)))]
             (fn []
               (cancel)
               (doseq [[_ e] @cache] ((:dispose e))))))))

;; ---------------------------------------------------------------------------
;; portal

     (defn- mount-portal
       "[:portal {:mount el} & children] — children render into `el`
  (default: the document body) while keeping their place in the
  process tree: same ctx (fallback/error routing), and disposing the
  portal's position cancels and removes the ported content."
       [parent anchor ctx v]
       (let [[_ props children] (fd/normalize v)
             target (or (:mount props)
                        (.-body (.-ownerDocument parent)))
             ;; a marker holds the portal's place in its own parent so
             ;; enclosing region operations behave normally
             marker (.createComment (.-ownerDocument parent) "portal")
             start  (.createComment (.-ownerDocument target) "portal-content")
             end    (.createComment (.-ownerDocument target) "/portal-content")]
         (.appendChild target start)
         (.appendChild target end)
         (let [d (mount-children target end ctx children)]
           (.insertBefore parent marker anchor)
           (fn []
             (d)
             (remove-range! start end)))))

;; ---------------------------------------------------------------------------
;; error boundary

     (defn- mount-boundary [parent anchor ctx v]
       (let [[_ props children] (fd/normalize v)
             fallback (or (:fallback props)
                          (fn [e _] [:div {:class "flowdom-error"} (str e)]))
             _ (when (not= 1 (count children))
                 (throw (ex-info "flowdom: :error-boundary takes exactly one child"
                                 {:children (count children)})))
             child (first children)
             start (.createComment (.-ownerDocument parent) "boundary")
             end   (.createComment (.-ownerDocument parent) "/boundary")
             inner (array)
             remount!
             (fn remount! []
               (dispose-all! inner)
               (clear-range! start end)
               (let [errored (volatile! false)
                     ctx'    (assoc ctx :on-error
                                    (fn [e]
                                      (when-not @errored
                                        (vreset! errored true)
                                   ;; defer: the error may arrive synchronously
                                   ;; mid-mount; tear down outside that stack
                                        (js/queueMicrotask
                                         (fn []
                                           (dispose-all! inner)
                                           (clear-range! start end)
                                           (.push inner
                                                  (mount-child parent end ctx
                                                               (fallback e remount!))))))))]
                 (.push inner (mount-child parent end ctx' child))))]
         (.insertBefore parent start anchor)
         (.insertBefore parent end anchor)
         (remount!)
         (fn [] (dispose-all! inner))))

;; ---------------------------------------------------------------------------
;; dispatch + public mount

     (defn- mount-child [parent anchor ctx v]
       (cond
         (or (nil? v) (boolean? v)) (fn [])
         (rx/rx? v)                 (mount-slot parent anchor ctx v)
         (fd/for-by? v)             (mount-for parent anchor ctx v)
         (fd/component-vec? v)      (mount-child parent anchor ctx
                                                 (apply (first v) (rest v)))
         (and (fd/element-vec? v) (= :<> (first v)))
         (mount-children parent anchor ctx (nth (fd/normalize v) 2))
         (and (fd/element-vec? v) (= :portal (first v)))
         (mount-portal parent anchor ctx v)
         (and (fd/element-vec? v) (= :error-boundary (first v)))
         (mount-boundary parent anchor ctx v)
         (fd/element-vec? v)        (mount-element parent anchor ctx v)
         (seq? v)                   (mount-children parent anchor ctx (vec v))
         (primitive? v)             (let [tn (.createTextNode (.-ownerDocument parent) (str v))]
                                      (.insertBefore parent tn anchor)
                                      (fn []))
         :else (throw (ex-info "flowdom: cannot mount value" {:got v}))))

     (defn mount
       "Mount `hiccup` into DOM element `container`. Returns a dispose fn
  that cancels every process and clears the container.

  Dev mode — `(mount hiccup container {:spine? true})` — attaches BOTH
  consumers: the DOM is patched as usual, and a spine keeps the live
  tree as a value. Returns a handle instead of a fn:

      {:dispose fn    — cancel everything, clear the container
       :tree    flow  — the whole UI as a missionary flow of hiccup}

  Sample it with flowdom.core/snapshot — the same fn JVM tests use.

  The hiccup is `expand`ed first so both walks share one instance of
  every statically-reachable component (rx blocks are m/signal-shared,
  so the computations run once and fan out). Caveat: component state
  inside dynamic content — rx emissions, for-by bodies — is
  instantiated per consumer and won't be reflected in the spine;
  ns-level state always is."
       ([hiccup container]
        (let [d (mount-child container nil {} hiccup)]
          (fn []
            (d)
            (set! (.-innerHTML container) ""))))
       ([hiccup container {:keys [spine?]}]
        (if-not spine?
          (mount hiccup container)
          (let [expanded    (fd/expand hiccup)
                dom-dispose (mount expanded container)
                spine       (fd/render expanded)]
            {:dispose (fn []
                        ((:cancel spine))
                        (dom-dispose))
             :tree    (:tree spine)})))))) ;; end :cljs
