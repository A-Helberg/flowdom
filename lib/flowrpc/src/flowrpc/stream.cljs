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

(defn follow-args
  "Builds (make-flow resolved-args), where `args` may mix plain values
  and watchable refs. With no refs this is just (make-flow args). With
  refs, the returned flow FOLLOWS them: whenever a watched ref changes
  value, the current inner flow is cancelled and make-flow re-runs
  with the newly resolved args — missionary's switch. Resolved arg
  vectors are deduplicated with CLJS =, so resetting a ref to an equal
  value does not re-run.

  The Cancelled catch is missionary's switch idiom: cancelling the
  superseded branch raises Cancelled inside it, which would otherwise
  crash the whole flow; yielding (m/amb) — the empty flow — instead
  lets the switch proceed."
  [args make-flow]
  (if-not (some watchable? args)
    (make-flow (vec args))
    (let [args< (apply m/latest vector
                       (map #(if (watchable? %) (m/watch %) (m/cp %)) args))]
      (m/ap
       (let [vs (m/?< (m/eduction (dedupe) args<))]
         (try
           (m/?< (make-flow vs))
           (catch Cancelled _ (m/amb))))))))

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
