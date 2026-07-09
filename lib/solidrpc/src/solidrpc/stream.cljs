(ns solidrpc.stream
  "Missionary-native SSE flow. Handles :full/:patch diff reconstruction client-side.
   Browser EventSource manages reconnection automatically."
  (:require
   [missionary.core :as m]
   [solidrpc.patch :as patch]
   [solidrpc.transit :as transit])
  (:import
   [missionary Cancelled]))

(defn- diff-xf
  "Stateful transducer: reconstructs full values from :full/:patch SSE events.
   Emits the full reconstructed value on each step."
  []
  (fn [rf]
    (let [prev (atom ::none)]
      (fn
        ([] (rf))
        ([r] (rf r))
        ([r [type data]]
         (let [v (if (= type :full)
                   data
                   (let [p @prev]
                     (when-not (= p ::none)
                       (patch/apply-patch p data))))]
           (if (some? v)
             (do (reset! prev v) (rf r v))
             r)))))))

(defn watchable?
  "True for a deref-able watchable ref (s/atom, hold, plain atom …).
  Query args must otherwise be transit-serializable data, so a
  watchable among them is unambiguous: it means follow it."
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
  "Returns a Missionary flow backed by an SSE connection.
   Handles :full/:patch diff reconstruction. Browser EventSource handles reconnection.
   The flow terminates when cancelled (e.g. via onCleanup)."
  [fn-name args]
  (let [qs  (transit/write {:fn-name fn-name :args args})
        url (str "/api/query?q=" (js/encodeURIComponent qs))]
    (->> (m/observe
          (fn [emit!]
            (let [es (js/EventSource. url)]
              (.addEventListener es "full"
                                 (fn [e]
                                   (try
                                     (emit! [:full (transit/read (.-data e))])
                                     (catch :default ex
                                       (js/console.error "[solidrpc.stream] transit decode failed (full)" (str ex))))))
              (.addEventListener es "patch"
                                 (fn [e]
                                   (try
                                     (emit! [:patch (transit/read (.-data e))])
                                     (catch :default ex
                                       (js/console.error "[solidrpc.stream] transit decode failed (patch)" (str ex))))))
              (.addEventListener es "exception"
                                 (fn [e]
                                   (js/console.error "[solidrpc.stream] server exception"
                                                     (try (transit/read (.-data e))
                                                          (catch :default _ (.-data e))))))
              (fn [] (.close es)))))
         (m/eduction (diff-xf)))))
