(ns server.core
  (:require
   [aleph.http :as http]
   [reitit.ring :as ring]
   [ring.middleware.params :refer [wrap-params]]
   [taoensso.timbre :as log]
   [solidrpc.registry :as registry]
   [solidrpc.server :as solidrpc]
   ;; Require api namespaces so their vars exist before registration.
   [api.clock]
   [api.notes]
   [api.server-info]
   [api.viewer]
   [server.notes]))

;; ---------------------------------------------------------------------------
;; API function whitelist — only registered vars are reachable via the transport.
;; ---------------------------------------------------------------------------

(registry/register! #'api.clock/time-flow)
(registry/register! #'api.clock/slow-time-flow)
(registry/register! #'api.clock/echo)
(registry/register! #'api.clock/scoreboard-flow)
;; the cljc facades register under their own symbols: the client's
;; (call/query `all-notes< db) resolves to the same var, whose :clj
;; branch produces the flow — one name, nothing to drift.
(registry/register! #'api.notes/all-notes<)
(registry/register! #'api.notes/add-note!)
(registry/register! #'api.notes/ping!)
(registry/register! #'api.server-info/server-info<)
(registry/register! #'api.viewer/whoami<)

;; ---------------------------------------------------------------------------
;; Router & server
;; ---------------------------------------------------------------------------

(defn index-handler [_req]
  {:status  200
   :headers {"content-type"  "text/html; charset=utf-8"
             "cache-control" "no-store, no-cache, must-revalidate, max-age=0"}
   :body    (slurp (clojure.java.io/resource "public/index.html"))})

;; The mount point is the app's whole value vocabulary, per request:
;; every token tag the wire speaks and how it reconstructs, in one map.
;; Three closures, three lifetimes, one mechanism: server.notes
;; contributes the db handlers (over the conn), server-info closes
;; over the startup instant, and the viewer closes over THIS request.
;; A real app resolves its current user like the viewer — swap the
;; body for a session-store lookup or a token verification; the shape
;; doesn't change.

(defonce ^:private started-at (System/currentTimeMillis))

(def ^:private startup-read-handlers
  {api.server-info/tag (fn [_rep]
                         {:started-at started-at
                          :uptime-ms  (- (System/currentTimeMillis) started-at)})})

(defn- viewer-from [req]
  (fn [_rep]
    {:remote-addr (str (:remote-addr req))
     :user-agent  (get-in req [:headers "user-agent"] "unknown")}))

(defn- rpc-opts [req]
  (update server.notes/transit-handlers
          :read-handlers merge
          startup-read-handlers
          {api.viewer/tag (viewer-from req)}))

(defn query-handler [req]
  (solidrpc/handle-query req (rpc-opts req)))

(defn command-handler [req]
  (solidrpc/handle-command req (rpc-opts req)))

(def handler
  (wrap-params
   (ring/ring-handler
    (ring/router
     [["/api/query"   {:get  query-handler}]
      ["/api/command" {:post command-handler}]
      ["/js/*"        {:get (ring/create-resource-handler {:root "public/js"})}]
      ["/css/*"       {:get (ring/create-resource-handler {:root "public/css"})}]])
    index-handler)))

(defonce server* (atom nil))

(defn start! []
  (let [s (http/start-server handler {:port 1300})]
    (reset! server* s)
    (log/info "Server started on http://localhost:1300")))

(defn stop! []
  (when-let [s @server*]
    (.close s)
    (reset! server* nil)
    (log/info "Server stopped")))

(defn run [_opts]
  (start!)
  @(promise))
