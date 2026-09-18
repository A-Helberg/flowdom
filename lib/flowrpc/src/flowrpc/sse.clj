(ns flowrpc.sse
  (:require
   [manifold.deferred :as dfr]
   [manifold.stream :as s]
   [taoensso.timbre :as log]
   [flowrpc.patch :as patch]
   [flowrpc.transit :as transit]))

(def headers
  {"Content-Type"      "text/event-stream; charset=utf-8"
   "Cache-Control"     "no-cache, no-transform, no-store"
   "X-Accel-Buffering" "no"})

(defn- sse-event [event payload wopts]
  (str "event: " (name event) "\n"
       "data: " (transit/write payload wopts) "\n\n"))

(defn- sse-message [m wopts]
  (cond
    (= m :keepalive)
    (sse-event :keepalive 1 wopts)

    (and (map? m) (= :exception (:flowrpc.sse/event m)))
    (sse-event :exception (:flowrpc.sse/payload m) wopts)

    ;; Full value on first emission or when diffing exceeds the size budget
    (and (map? m) (= :full (:rpc/type m)))
    (sse-event :full (:rpc/data m) wopts)

    ;; Patch on subsequent emissions
    (and (map? m) (= :patch (:rpc/type m)))
    (sse-event :patch (:rpc/data m) wopts)

    :else
    (str "data: " (transit/write m wopts) "\n\n")))

(defn- ensure-stream [flow]
  (if (s/stream? flow)
    flow
    (let [single (s/stream 1)]
      @(s/put! single flow)
      (s/close! single)
      single)))

(defn- diffable?
  "Bounded, iterative walk: count collections, map entries, keys and leaves.
   Root depth is zero. Neither a full index nor a flattened tree is allocated."
  [value max-size]
  (loop [stack [[(list value) 0]] visited 0]
    (if (empty? stack)
      true
      (let [[items depth] (peek stack)]
        (if-let [items (seq items)]
          (if (or (>= visited max-size) (> depth 64))
            false
            (let [v (first items)
                  stack (conj (pop stack) [(rest items) depth])]
              (recur (cond-> stack
                       (coll? v) (conj [v (inc depth)]))
                     (inc visited))))
          (recur (pop stack) visited))))))

(defn- diff-stream
  "Transforms a source stream into one that emits {:rpc/type :full/:patch :rpc/data ...}.
   First emission is always :full; subsequent ones are :patch only when both
   values fit the node/depth budget. Every :full resets the patch baseline.
   nil is a value and participates in diffing like any other query result."
  ([source] (diff-stream source {}))
  ([source {:keys [diff-algo max-diff-size vec-timeout]
            :or {diff-algo :quick max-diff-size 10000 vec-timeout 10}}]
   (when-not (and (#{:quick :a-star} diff-algo)
                 (integer? max-diff-size) (<= 0 max-diff-size)
                 (number? vec-timeout) (pos? vec-timeout))
     (throw (ex-info "Invalid SSE diff options"
                     {:diff-algo diff-algo :max-diff-size max-diff-size
                      :vec-timeout vec-timeout})))
   (let [out  (s/stream 16)
         prev (atom nil)
         done (s/consume
               (fn [v]
                 ;; An exception envelope is transport-level, not a query
                 ;; value: pass it through untouched so sse-message emits
                 ;; an :exception event (which fails the client flow)
                 ;; instead of wrapping it as a :full \"answer\".
                 (if (and (map? v) (= :exception (:flowrpc.sse/event v)))
                   @(s/put! out v)
                   (let [p @prev
                         eligible? (diffable? v max-diff-size)
                         msg (if (and (:diffable? p) eligible?)
                               {:rpc/type :patch
                                :rpc/data (patch/diff (:value p) v
                                                     {:algo diff-algo :vec-timeout vec-timeout})}
                               {:rpc/type :full :rpc/data v})]
                     ;; Oversized values cannot be patch bases; don't retain them.
                     (reset! prev (when eligible? {:value v :diffable? true}))
                     @(s/put! out msg))))
               source)]
     (dfr/on-realized done
                      (fn [_] (s/close! out))
                      (fn [_] (s/close! out)))
     out)))

(defn manifold->sse
  "Wraps a Manifold stream (or single value) into an SSE output stream.
   Streams bounded diffs, falling back to full values for large/deep results.
   Sends a keepalive every 20 s. Closes cleanly when the source closes.

   Opts:
     :keepalive-ms    keepalive interval (default 20000)
     :diff-algo       :quick (default) or :a-star (expensive; opt-in)
     :max-diff-size   nodes per value (default 10000); 0 always sends :full.
                      Counts collections, map entries, keys and leaves.
                      Values deeper than 64 also bypass diffing.
     :vec-timeout     milliseconds per sequence diff (default 10);
                      cooperative, not a whole-diff deadline
     :write-handlers  per-request transit write handlers
                      ({type {:tag … :rep …}}); the map rides the
                      stream's closures, so encoding stays
                      request-scoped for the connection's lifetime."
  ([flow] (manifold->sse flow {}))
  ([flow {:keys [keepalive-ms write-handlers] :or {keepalive-ms 20000} :as opts}]
   (let [wopts     {:handlers write-handlers}
         source    (diff-stream (ensure-stream flow) opts)
         data      (s/stream 16)
         sse       (s/map #(sse-message % wopts) data)
         out       (s/stream 16)
         _         (s/connect sse out)
         keepalive (s/periodically (long keepalive-ms) (constantly :keepalive))
         _         (s/connect keepalive data)
         done      (s/consume (fn [v]
                                (try
                                  @(s/put! data v)
                                  (catch Throwable t
                                    (log/error t "Failed to write SSE message")
                                    (s/close! out)
                                    (throw t))))
                              source)]
     (dfr/on-realized done
                      (fn [_] (s/close! out))
                      (fn [e]
                        (log/error e "SSE stream errored")
                        (when-not (s/closed? out)
                          @(s/put! out (sse-event :exception {:message (.getMessage ^Throwable e)} wopts)))))
     (s/on-closed out (fn []
                        (try (s/close! keepalive) (catch Throwable _))
                        (try (s/close! source)    (catch Throwable _))
                        (try (s/close! data)      (catch Throwable _))))
     out)))
