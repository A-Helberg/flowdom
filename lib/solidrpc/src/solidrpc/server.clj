(ns solidrpc.server
  "Ring handlers for /api/query (SSE) and /api/command (transit POST).
   Expects ring.middleware.params/wrap-params applied around the handler
   so that :query-params is already decoded when handle-query runs.
   Mount these in your router:
     [\"/api/query\"   {:get  solidrpc.server/handle-query}]
     [\"/api/command\" {:post solidrpc.server/handle-command}]"
  (:require
   [manifold.stream :as s]
   [missionary.core :as m]
   [taoensso.timbre :as log]
   [solidrpc.sse :as sse]
   [solidrpc.registry :as registry]
   [solidrpc.transit :as transit]))

(defn- ensure-stream [x]
  (if (s/stream? x)
    x
    (let [out (s/stream 1)]
      @(s/put! out x)
      (s/close! out)
      out)))

(defn flow->stream
  "Runs a missionary flow into a manifold stream — the adapter between
  solidrpc.live flows (or any flow) and the SSE transport. The
  blocking put is the backpressure; closing the stream (client
  disconnected) cancels the flow, releasing its subscriptions (e.g. a
  shared tx-report listener)."
  [flow]
  (let [out    (s/stream 16)
        cancel ((m/reduce (fn [_ v] @(s/put! out v) nil) nil flow)
                (fn [_] (s/close! out))
                (fn [e]
                  ;; a cancelled run fails by design — only log failures
                  ;; that happened while anyone was still listening.
                  (when-not (s/closed? out)
                    (log/error e "flow->stream: flow failed"))
                  (s/close! out)))]
    (s/on-closed out cancel)
    out))

(defn handle-query
  "GET /api/query — looks up fn-name in the registry, calls it, streams result as SSE."
  [req]
  (let [qs (get (:query-params req) "q")]
    (if (nil? qs)
      (do (log/error "handle-query: missing q param" {:query-params (:query-params req)})
          {:status 400 :headers {"content-type" "application/json"}
           :body   "{\"error\":\"missing q param\"}"})
      (try
        (let [{:keys [fn-name args]} (transit/read qs)
              v                      (registry/lookup (str fn-name))]
          (if (nil? v)
            (do (log/error "handle-query: fn not in registry" {:fn-name fn-name})
                {:status 404 :headers {"content-type" "application/json"}
                 :body   (str "{\"error\":\"not found: " fn-name "\"}")})
            (let [result (apply v args)
                  ;; flow-returning endpoints (solidrpc.live facades)
                  ;; are adapted here — endpoint authors never touch
                  ;; the transport. Flows are fns; anything else
                  ;; non-stream is wrapped as a one-shot.
                  stream (cond
                           (s/stream? result) result
                           (fn? result)       (flow->stream result)
                           :else              (ensure-stream result))]
              {:status 200 :headers sse/headers :body (sse/manifold->sse stream)})))
        (catch Throwable t
          (log/error t "handle-query: exception" {:qs qs})
          {:status  500
           :headers {"content-type" "application/json"}
           :body    (str "{\"error\":\"" (.getMessage t) "\"}")})))))

(defn handle-command
  "POST /api/command — looks up fn-name in the registry, calls it, returns transit JSON."
  [req]
  (try
    (let [body                   (some-> req :body slurp)
          {:keys [fn-name args]} (transit/read body)
          v                      (registry/lookup (str fn-name))]
      (if (nil? v)
        (do (log/error "handle-command: fn not in registry" {:fn-name fn-name})
            {:status  404
             :headers {"content-type" "application/transit+json"}
             :body    (transit/write {:type :command/exception :ok false :fn-name fn-name
                                      :exception {:message (str "not found: " fn-name)}})})
        (let [result (apply v args)]
          {:status  200
           :headers {"content-type" "application/transit+json"}
           :body    (transit/write {:type :command/result :ok true
                                    :fn-name fn-name :result result})})))
    (catch Throwable t
      (log/error t "handle-command: exception")
      {:status  500
       :headers {"content-type" "application/transit+json"}
       :body    (transit/write {:type :command/exception :ok false :fn-name "<unknown>"
                                :exception {:message (.getMessage t)}})})))
