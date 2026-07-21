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

  Propagation is synchronous by default: a swap! has patched the DOM
  by the time it returns. `(mount hiccup el {:schedule :frame})` opts
  into batching instead — updates coalesce (latest wins, per region)
  and flush once per animation frame; initial mounts always render
  synchronously. Uncaught errors reach the root :on-error hook (see
  `mount`) or console.error without one.

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

     (defn- handler-spec
       "A handler prop value is a fn, or a map
  {:handler f :capture b :passive b :once b} to pass listener options.
  Returns [f options-js-or-false] — the options are also used for
  removeEventListener (capture must match to detach)."
       [v]
       (if (map? v)
         [(:handler v) #js {:capture (boolean (:capture v))
                            :passive (boolean (:passive v))
                            :once    (boolean (:once v))}]
         [v false]))

     ;; Keys whose DOM PROPERTY is authoritative and whose attribute
     ;; either doesn't exist (:indeterminate) or stops reflecting after
     ;; user interaction (:value/:checked). Everything else is an
     ;; attribute — except custom-element props (see use-property?).
     (def ^:private property-keys
       #{:value :checked :selected :indeterminate :muted :volume})

     (defn- custom-element? [el]
       (let [ln (.-localName el)]
         (boolean (and ln (str/includes? ln "-")))))

     (defn- attr-value?
       "A value an HTML attribute can actually hold: string, number,
  boolean, nil. Anything else (a map, a JS object, a fn) can only
  live as a property."
       [v]
       (or (string? v) (number? v) (boolean? v) (nil? v)))

     (defn- use-property?
       "Set k as a JS property rather than an attribute? Always for the
  known property-keys. For a custom element, whenever the property
  already exists on it OR the value is non-attribute data — so rich
  props reach web components unstringified, even before the element
  upgrades and defines the property."
       [el k v]
       (or (contains? property-keys k)
           (and (custom-element? el)
                (or (js/Reflect.has el (name k))
                    (not (attr-value? v))))))

     (defn- set-prop! [el k v]
       (cond
         (= k :class)
         ;; setAttribute, not .className: identical for HTML, and SVG
         ;; elements' className is a read-only SVGAnimatedString
         (.setAttribute el "class" (str (or (fd/class-str v) "")))

         (= k :innerHTML)
         (set! (.-innerHTML el) (str v))

         (= k :style)
         (if (map? v)
           (doseq [[sk sv] v] (.setProperty (.-style el) (name sk) (str sv)))
           (.setAttribute el "style" (str v)))

         (use-property? el k v)
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
  errors — the stopped flag swallows them.

  With a ctx :schedule!, the FIRST emission still applies synchronously
  (initial mounts must render) and later ones are handed to the
  scheduler keyed by this region — latest wins, a disposed region's
  queued patch is a no-op."
       [ctx flow on-value on-fail]
       (let [stopped   (volatile! false)
             schedule! (:schedule! ctx)
             first?    (volatile! true)
             deliver!  (fn [v]
                         (if (or (nil? schedule!) @first?)
                           (do (vreset! first? false)
                               (on-value v))
                           (schedule! stopped
                                      (fn [] (when-not @stopped (on-value v))))))
             cancel    ((m/reduce (fn [_ v] (deliver! v) nil) nil (rx/unwrap flow))
                        (fn [_] nil)
                        (fn [e] (when-not @stopped (on-fail e))))]
         (fn []
           (vreset! stopped true)
           (cancel))))

     (defn- make-scheduler
       "nil/:sync → nil (synchronous propagation). :frame → batch on
  requestAnimationFrame. A fn → custom: called with a 0-arg flush!
  whenever a flush needs scheduling (tests drive this directly).
  Patches coalesce per region between flushes — latest wins."
       [mode]
       (when (and (some? mode) (not= mode :sync))
         (let [request   (if (= mode :frame)
                           (fn [flush!] (js/requestAnimationFrame (fn [_] (flush!))))
                           mode)
               queue     (js/Map.)
               scheduled (volatile! false)]
           (fn schedule! [key thunk]
             (.set queue key thunk)
             (when-not @scheduled
               (vreset! scheduled true)
               (request (fn flush! []
                          (vreset! scheduled false)
                          (let [thunks (js/Array.from (.values queue))]
                            (.clear queue)
                            (doseq [t thunks] (t))))))))))

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
         (let [cancel (spawn! ctx content patch! (fn [e] (report-error! ctx e)))]
           (fn []
             (cancel)
             (dispose-all! inner)))))

;; ---------------------------------------------------------------------------
;; elements

     (defn- spawn-value!
       "A reactive :value on a text field, made IME-safe. While an input
  method is composing (CJK, dead keys), writing .value back cancels
  the in-progress candidate and jumps the caret — so defer writes
  until compositionend, then apply the latest. Listeners ride the
  element and die with it, like static handlers."
       [el pv ctx]
       (let [composing (volatile! false)
             pending   (volatile! ::none)]
         (.addEventListener el "compositionstart"
                            (fn [_] (vreset! composing true)))
         (.addEventListener el "compositionend"
                            (fn [_]
                              (vreset! composing false)
                              (when-not (= ::none @pending)
                                (set-prop! el :value @pending)
                                (vreset! pending ::none))))
         (spawn! ctx pv
                 (fn [v]
                   (cond
                     (rx/pending-value? v) nil
                     (rx/err? v)           (report-error! ctx (:error v))
                     @composing            (vreset! pending v)
                     :else                 (set-prop! el :value v)))
                 (fn [e] (report-error! ctx e)))))

     (defn- spawn-prop! [el k pv ctx]
       (if (= k :value)
         (spawn-value! el pv ctx)
         (spawn! ctx pv
                 (fn [v]
                   (cond
                     (rx/pending-value? v) nil
                     (rx/err? v)           (report-error! ctx (:error v))
                     :else                 (set-prop! el k v)))
                 (fn [e] (report-error! ctx e)))))

     (defn- spawn-handler!
       "An rx-valued :on* prop: the CURRENT emission is the listener
  (a fn, or a {:handler … :capture … :passive … :once …} spec). Each
  change swaps the listener; pending means no listener yet; nil
  detaches."
       [el k pv ctx]
       (let [ev      (event-name k)
             current (volatile! nil)] ;; [f opts] currently attached
         (spawn! ctx pv
                 (fn [v]
                   (cond
                     (rx/pending-value? v) nil
                     (rx/err? v)           (report-error! ctx (:error v))
                     :else
                     (do (when-let [[old opts] @current]
                           (.removeEventListener el ev old opts))
                         (let [[f opts] (handler-spec v)]
                           (vreset! current (when f [f opts]))
                           (when f (.addEventListener el ev f opts))))))
                 (fn [e] (report-error! ctx e)))))

     (def ^:private svg-ns "http://www.w3.org/2000/svg")

     (defn- mount-element [parent anchor ctx v]
       (let [[tag props children] (fd/normalize v)
             fb       (:fallback props)
             ctx      (if (some? fb) (assoc ctx :fallback fb) ctx)
             on-mount (:on-mount props)
             props    (dissoc props :fallback :on-mount)
             ;; <svg> enters the SVG namespace; every descendant stays
             ;; in it except children of <foreignObject>, which are
             ;; HTML again
             svg?     (or (= tag :svg) (boolean (:svg? ctx)))
             ctx      (assoc ctx :svg? (and svg? (not= tag :foreignObject)))
             el       (if svg?
                        (.createElementNS (.-ownerDocument parent) svg-ns (name tag))
                        (.createElement (.-ownerDocument parent) (name tag)))
             ds       (array)]
         (doseq [[k pv] props]
           (cond
             (handler-key? k) (if (rx/rx? pv)
                                (.push ds (spawn-handler! el k pv ctx))
                                (let [[f opts] (handler-spec pv)]
                                  (when f (.addEventListener el (event-name k) f opts))))
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
             ;; pending state — the same contract as the JVM interpreter:
             ;; an items source with no value yet renders the enclosing
             ;; :fallback. Row state is not guaranteed across a pending
             ;; episode (here rows are dropped; the spine retains them).
             fb-dispose (volatile! nil)
             clear-all! (fn []
                          (doseq [[_ e] @cache] ((:dispose e)))
                          (vreset! cache {})
                          (when-let [d @fb-dispose] (d) (vreset! fb-dispose nil))
                          (clear-range! start end))
             show-fallback!
             (fn []
               (when (nil? @fb-dispose)
                 (clear-all!)
                 (vreset! fb-dispose (mount-child parent end ctx (:fallback ctx)))))
             hide-fallback!
             (fn []
               (when-let [d @fb-dispose]
                 (d)
                 (vreset! fb-dispose nil)
                 (clear-range! start end)))
             patch!
             (fn [v]
               (cond
                 (rx/pending-value? v) (show-fallback!)
                 (rx/err? v) (report-error! ctx (:error v))
                 :else
                 (let [xs (vec v)
                       ks (mapv key-fn xs)
                       _  (when (and (seq ks) (not (apply distinct? ks)))
                            ;; same error, same route as the JVM interpreter:
                            ;; to the nearest :error-boundary
                            (throw (ex-info "flowdom: for-by keys must be distinct"
                                            {:keys ks})))
                       _  (hide-fallback!)
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
           (do (try (patch! items)
                    (catch :default e (report-error! ctx e)))
               (fn []
                 (clear-all!)))
           (do
             ;; no value yet — pending, exactly like the JVM interpreter
             (show-fallback!)
             (let [cancel (spawn! ctx (items-flow items)
                                  (fn [v]
                                    (try (patch! v)
                                         (catch :default e (report-error! ctx e))))
                                  (fn [e] (report-error! ctx e)))]
               (fn []
                 (cancel)
                 (clear-all!)))))))

;; ---------------------------------------------------------------------------
;; portal

     (defn- mount-portal
       "[:portal {:mount el} & children] — children render into `el`
  (default: the document body) while keeping their place in the
  process tree: same ctx (fallback/error routing), and disposing the
  portal's position cancels and removes the ported content."
       [parent anchor ctx v]
       (let [[_ props children] (fd/normalize v)
             ctx    (dissoc ctx :svg?)
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

  Opts:
    :on-error  (fn [e remount!]) — the ROOT error hook: every error no
               :error-boundary caught lands here instead of the
               console. `remount!` tears the whole mount down and
               mounts fresh (deferred to a microtask) — the root-level
               retry. Errors inside boundaries never reach it.
    :schedule  :sync (default) — a swap! has patched the DOM by the
               time it returns. :frame — updates coalesce per region
               (latest wins) and flush once per animation frame;
               initial mounts still render synchronously. A fn —
               custom scheduler, called with a 0-arg flush! (tests).

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
        (mount hiccup container {}))
       ([hiccup container {:keys [spine? on-error schedule] :as opts}]
        (if spine?
          (let [expanded    (fd/expand hiccup)
                dom-dispose (mount expanded container (dissoc opts :spine?))
                spine       (fd/render expanded)]
            {:dispose (fn []
                        ((:cancel spine))
                        (dom-dispose))
             :tree    (:tree spine)})
          (let [sched     (make-scheduler schedule)
                current   (volatile! nil)   ;; dispose of the live mount; nil once disposed
                do-mount!
                (fn do-mount! []
                  (let [remounting (volatile! false)
                        ;; the root remount lever: tear the whole mount
                        ;; down and mount fresh. Deferred — the error may
                        ;; arrive synchronously mid-propagation; tearing
                        ;; down inside that stack is the boundary bug all
                        ;; over again.
                        remount!   (fn []
                                     (when-not @remounting
                                       (vreset! remounting true)
                                       (js/queueMicrotask
                                        (fn []
                                          (when-let [d @current]
                                            (d)
                                            (do-mount!))))))
                        ctx        (cond-> {}
                                     (some? sched)  (assoc :schedule! sched)
                                     (fn? on-error) (assoc :on-error
                                                           (fn [e] (on-error e remount!))))
                        d          (mount-child container nil ctx hiccup)]
                    (vreset! current
                             (fn []
                               (d)
                               (set! (.-innerHTML container) "")))))]
            (do-mount!)
            (fn []
              (when-let [d @current]
                (vreset! current nil)
                (d))))))))) ;; end :cljs
