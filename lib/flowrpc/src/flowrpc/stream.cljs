(ns flowrpc.stream
  "Missionary-native SSE flow. Handles :full/:patch diff reconstruction client-side.
   Browser EventSource manages reconnection automatically."
  (:require
   [missionary.core :as m]
   [flowrpc.patch :as patch]
   [flowrpc.transit :as transit])
  (:import
   [missionary Cancelled]))

(defn diff-xf
  "Stateful transducer: reconstructs full values from :full/:patch SSE
   events, emitting the full reconstructed value on each step. An
   [:exception data] event THROWS — inside m/eduction that fails the
   flow, so the error reaches whatever is reading the query (in
   flowdom: an Err value, caught by the nearest :error-boundary).
   Public for tests; sse-flow is the consumer."
  []
  (fn [rf]
    (let [prev (atom ::none)]
      (fn
        ([] (rf))
        ([r] (rf r))
        ([r [type data]]
         (when (= type :exception)
           (throw (ex-info "flowrpc: query stream failed"
                           {:flowrpc/error data})))
         (let [v (if (= type :full)
                   data
                   (let [p @prev]
                     (when-not (= p ::none)
                       (patch/apply-patch p data))))]
           (if (some? v)
             (do (reset! prev v) (rf r v))
             r)))))))

(defn watchable?
  "True for a deref-able watchable ref (a plain atom, or anything
  IWatchable + IDeref). Query args must otherwise be
  transit-serializable data, so a watchable among them is unambiguous:
  it means follow it."
  [x]
  (and (satisfies? IWatchable x) (satisfies? IDeref x)))

(def unresolved
  "Initial value for a followed ref whose value isn't known YET
  (distinct from nil = resolved to nothing). While any followed ref
  resolves to this, the query emits nothing — an enclosing rx stays
  pending (loading holds), and the query is never asked the wrong
  (initial) question. That kills the loading → wrong-answer flash when
  a query argument derives from another query's answer."
  ::unresolved)

(def loading-visible
  "Sentinel: include it anywhere in a query's args to make refetches
  visible. By default a followed ref changing is silent — the flow
  simply doesn't emit until the new connection's first value, so
  readers keep the stale answer. With the sentinel, each (re)connect
  first emits the keyword :flowdom.rx/pending — flowdom's pending
  protocol — so flowdom readers re-enter loading (the nearest
  :fallback renders, loading?< reads true) until the answer arrives.
  flowrpc does not depend on flowdom: the marker is a plain keyword,
  and a raw missionary consumer that opts in must expect it among the
  emissions. `unresolved` refs still emit nothing at all — 'not asked
  yet' is not 'loading'."
  ::loading-visible)

(deftype LoadingValue [v]
  IDeref
  (-deref [_] v))

(defn loading-value
  "Wrap a placeholder: include (loading-value x) anywhere in a
  query's args and `x` is emitted before the query's FIRST answer —
  the mount renders `x` immediately instead of pending. First answer
  only: a refetch (followed ref change) does not re-emit it — readers
  hold the stale answer as usual, or see the pending marker if
  `loading-visible` is also passed (the two compose: placeholder
  initially, loading on refetches; the first connect emits the
  placeholder, which preempts the marker). The initial loading state
  is spent on the placeholder — readers can't tell `x` from an
  answer — so pass one only when an immediate value is the right
  semantics. `unresolved` refs still emit nothing at all."
  [v]
  (->LoadingValue v))

(def ^:private pending-marker :flowdom.rx/pending)

(defn follow-args
  "Builds (make-flow resolved-args), where `args` may mix plain values
  and watchable refs. With no refs this is just (make-flow args). With
  refs, the returned flow FOLLOWS them: whenever a watched ref changes
  value, the current inner flow is cancelled and make-flow re-runs
  with the newly resolved args — missionary's switch. Resolved arg
  vectors are deduplicated with CLJS =, so resetting a ref to an equal
  value does not re-run. While any resolved arg is `unresolved`,
  nothing emits and make-flow is not called.

  The `loading-visible` sentinel and a `(loading-value x)` wrapper
  are filtered from `args`. They govern two different moments:
  loading-value is what precedes the FIRST answer ever (the
  placeholder, once); loading-visible marks every (re)connect with
  the pending marker. They compose — placeholder initially, loading
  on refetches — and on the first connect the placeholder preempts
  the marker. The emission and the switch are the same event — there
  is no ref list to keep in sync and no race.

  The Cancelled catch is missionary's switch idiom: cancelling the
  superseded branch raises Cancelled inside it, which would otherwise
  crash the whole flow; yielding (m/amb) — the empty flow — instead
  lets the switch proceed."
  [args make-flow]
  (let [visible? (boolean (some #{loading-visible} args))
        lv       (some #(when (instance? LoadingValue %) %) args)
        args     (into [] (remove #(or (= loading-visible %)
                                       (instance? LoadingValue %)))
                       args)
        prepend  (fn [x flow] (m/ap (m/amb x (m/?> flow))))]
    (if-not (some watchable? args)
      (cond
        lv       (prepend @lv (make-flow args))
        visible? (prepend pending-marker (make-flow args))
        :else    (make-flow args))
      (let [args< (apply m/latest vector
                         (map #(if (watchable? %) (m/watch %) (m/cp %)) args))]
        (m/ap
         ;; per-run state: has this run connected before? (the prefix
         ;; above the first fork runs once per process)
         (let [connected* (atom false)]
           (let [vs (m/?< (m/eduction (dedupe) args<))]
             (if (some #(= unresolved %) vs)
               (m/amb)
               (let [first? (not @connected*)
                     _      (reset! connected* true)
                     inner  (cond
                              (and first? lv) (prepend @lv (make-flow vs))
                              visible?        (prepend pending-marker (make-flow vs))
                              :else           (make-flow vs))]
                 (try
                   (m/?< inner)
                   (catch Cancelled _ (m/amb))))))))))))

(defn sse-flow
  "Returns a Missionary flow backed by an SSE connection, handling
   :full/:patch diff reconstruction.

   Failures FAIL the flow (via diff-xf) so they reach the reader —
   in flowdom, the nearest :error-boundary: a server-side exception
   event, a transit decode failure, and a permanently-closed
   EventSource (non-200 response — e.g. an expired session — makes
   the browser give up silently otherwise). Transient drops are left
   to EventSource's own reconnection: the server re-sends a :full on
   reconnect, so the diff state heals itself."
  [fn-name args]
  (let [qs  (transit/write {:fn-name fn-name :args args})
        url (str "/api/query?q=" (js/encodeURIComponent qs))]
    (->> (m/observe
          (fn [emit!]
            (let [es (js/EventSource. url)
                  decode! (fn [type e]
                            (try
                              (emit! [type (transit/read (.-data e))])
                              (catch :default ex
                                (emit! [:exception {:message (str "transit decode failed (" (name type) "): " ex)}]))))]
              (.addEventListener es "full"  #(decode! :full %))
              (.addEventListener es "patch" #(decode! :patch %))
              (.addEventListener es "exception"
                                 (fn [e]
                                   (emit! [:exception
                                           (try (transit/read (.-data e))
                                                (catch :default _ {:message (str (.-data e))}))])))
              (.addEventListener es "error"
                                 (fn [_]
                                   ;; readyState CONNECTING (0) = the browser is
                                   ;; retrying — let it. CLOSED (2) = it gave up
                                   ;; (non-200); nothing will ever arrive.
                                   (when (= (.-readyState es) js/EventSource.CLOSED)
                                     (emit! [:exception {:message "connection closed by the browser (non-200 response?)"}]))))
              (fn [] (.close es)))))
         (m/eduction (diff-xf)))))
