(ns flowrpc.server
  "Ring handlers for /api/query (SSE) and /api/command (transit POST).
   Expects ring.middleware.params/wrap-params applied around the handler
   so that :query-params is already decoded when handle-query runs.
   Mount these in your router:
     [\"/api/query\"   {:get  flowrpc.server/handle-query}]
     [\"/api/command\" {:post flowrpc.server/handle-command}]

   CSRF: if your endpoints carry authority from a session cookie, that
   cookie MUST be SameSite=Lax or Strict — it is the only control that
   also covers the SSE query stream, because EventSource cannot send a
   CSRF header or a custom content-type. handle-command additionally
   requires content-type application/transit+json (a forged cross-site
   POST can't send it without a preflight your CORS policy refuses).
   For SameSite=None deployments add an Origin allowlist / double-submit
   token in front of these handlers. The trust boundary for AUTHZ is
   the endpoint fn itself (authorize against current state); the
   content-type check is only anti-forgery."
  (:require
   [clojure.string]
   [manifold.stream :as s]
   [missionary.core :as m]
   [taoensso.timbre :as log]
   [flowrpc.sse :as sse]
   [flowrpc.registry :as registry]
   [flowrpc.transit :as transit]))

(defn- ensure-stream [x]
  (if (s/stream? x)
    x
    (let [out (s/stream 1)]
      @(s/put! out x)
      (s/close! out)
      out)))

(defn flow->stream
  "Runs a missionary flow into a manifold stream — the adapter between
  flowrpc.live flows (or any flow) and the SSE transport. The
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

(def default-max-bytes
  "Default cap on an incoming request's transit size (chars). Bounds
  parse cost and memory for untrusted input; override per handler with
  :max-bytes (nil = unlimited)."
  (* 1 1024 1024))

(def default-max-depth
  "Default cap on an incoming request's nesting depth. The load-bearing
  DoS guard — the JSON parser recurses per level. Override with
  :max-depth (nil = unlimited)."
  64)

(defn- error-status
  "Response status for a thrown decode/endpoint exception: honors
  :flowrpc/status in ex-data (e.g. a session-rejecting read handler
  throwing (ex-info \"no session\" {:flowrpc/status 401})), else 500.
  Walks the cause chain — transit wraps handler exceptions in a
  RuntimeException."
  [t]
  (or (loop [e t]
        (when e
          (or (:flowrpc/status (ex-data e))
              (recur (ex-cause e)))))
      500))

(defn handle-query
  "GET /api/query — looks up fn-name in the registry, calls it, streams
   result as SSE.

   Opts (supply them where you mount the handler — your router fn has
   the request in scope, so request-dependent handlers are plain
   closures over it):
     :read-handlers   {tag (fn [rep] …)} — runs while the incoming
                      args decode; the return value becomes the
                      argument the endpoint fn receives in place of
                      the token
     :write-handlers  {type {:tag … :rep …}} — per-request transit
                      write handlers for the outgoing stream"
  ([req] (handle-query req nil))
  ([req {:keys [read-handlers write-handlers max-bytes max-depth]
         :or   {max-bytes default-max-bytes max-depth default-max-depth}}]
   (let [qs (get (:query-params req) "q")]
     (if (nil? qs)
       (do (log/error "handle-query: missing q param" {:query-params (:query-params req)})
           {:status 400 :headers {"content-type" "application/json"}
            :body   "{\"error\":\"missing q param\"}"})
       (try
         (let [{:keys [fn-name args]} (transit/read qs {:handlers   read-handlers
                                                        :max-bytes  max-bytes
                                                        :max-depth  max-depth})
               v                      (registry/lookup (str fn-name))]
           (if (nil? v)
             (do (log/error "handle-query: fn not in registry" {:fn-name fn-name})
                 {:status 404 :headers {"content-type" "application/json"}
                  :body   (str "{\"error\":\"not found: " fn-name "\"}")})
             (let [result (apply v args)
                   ;; flow-returning endpoints (flowrpc.live facades)
                   ;; are adapted here — endpoint authors never touch
                   ;; the transport. Flows are fns; anything else
                   ;; non-stream is wrapped as a one-shot.
                   stream (cond
                            (s/stream? result) result
                            (fn? result)       (flow->stream result)
                            :else              (ensure-stream result))]
               {:status  200
                :headers sse/headers
                :body    (sse/manifold->sse stream {:write-handlers write-handlers})})))
         (catch Throwable t
           (log/error t "handle-query: exception" {:qs qs})
           {:status  (error-status t)
            :headers {"content-type" "application/json"}
            :body    (str "{\"error\":\"" (.getMessage t) "\"}")}))))))

(def ^:private transit-ct "application/transit+json")

(defn- transit-content-type?
  "True when the request declares application/transit+json (params like
  ;charset allowed). This is a CSRF control, not politeness: a forged
  cross-site POST can only send a CORS 'simple' content-type
  (text/plain, form-urlencoded, multipart) without tripping a
  preflight — and a preflight for transit+json is refused by any
  server without a permissive CORS policy. Requiring the transit
  content-type therefore forces would-be forgers through a preflight
  they can't pass. Pair it with SameSite cookies (see the ns
  docstring); EventSource can't set headers, so the SSE query relies
  on SameSite alone."
  [req]
  (when-let [ct (get-in req [:headers "content-type"])]
    (clojure.string/starts-with? ct transit-ct)))

(defn handle-command
  "POST /api/command — looks up fn-name in the registry, calls it,
   returns transit JSON. Takes the same opts as handle-query.

   Rejects any request not declaring content-type
   application/transit+json with 415 (a CSRF guard — see
   `transit-content-type?`), and over-large / over-deep payloads with
   413 (DoS guard — :max-bytes / :max-depth, defaulted)."
  ([req] (handle-command req nil))
  ([req {:keys [read-handlers write-handlers max-bytes max-depth]
         :or   {max-bytes default-max-bytes max-depth default-max-depth}}]
   (let [wopts {:handlers write-handlers}]
     (if-not (transit-content-type? req)
       {:status  415
        :headers {"content-type" "application/transit+json"}
        :body    (transit/write {:type :command/exception :ok false :fn-name "<unknown>"
                                 :exception {:message (str "content-type must be " transit-ct)}}
                                wopts)}
     (try
       (let [body                   (some-> req :body slurp)
             {:keys [fn-name args]} (transit/read body {:handlers   read-handlers
                                                         :max-bytes  max-bytes
                                                         :max-depth  max-depth})
             v                      (registry/lookup (str fn-name))]
         (if (nil? v)
           (do (log/error "handle-command: fn not in registry" {:fn-name fn-name})
               {:status  404
                :headers {"content-type" "application/transit+json"}
                :body    (transit/write {:type :command/exception :ok false :fn-name fn-name
                                         :exception {:message (str "not found: " fn-name)}}
                                        wopts)})
           (let [result (apply v args)]
             {:status  200
              :headers {"content-type" "application/transit+json"}
              :body    (transit/write {:type :command/result :ok true
                                       :fn-name fn-name :result result}
                                      wopts)})))
       (catch Throwable t
         (log/error t "handle-command: exception")
         {:status  (error-status t)
          :headers {"content-type" "application/transit+json"}
          :body    (transit/write {:type :command/exception :ok false :fn-name "<unknown>"
                                   :exception {:message (.getMessage t)}}
                                  wopts)}))))))
