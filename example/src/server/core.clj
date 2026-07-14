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
   [api.viewer]))

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
(registry/register! #'api.viewer/whoami<)

;; ---------------------------------------------------------------------------
;; Router & server
;; ---------------------------------------------------------------------------

(defn index-handler [_req]
  {:status  200
   :headers {"content-type"  "text/html; charset=utf-8"
             "cache-control" "no-store, no-cache, must-revalidate, max-age=0"}
   :body    (slurp (clojure.java.io/resource "public/index.html"))})

;; The mount point is where request-scoped reconstruction lives: the
;; fns below have the request in scope, so a read handler is a plain
;; closure over it. This one turns #app/viewer refs in incoming args
;; into a value only the request can supply. A real app resolves its
;; current user here — swap the body for a session-store lookup or a
;; token verification; the shape doesn't change. (Request-independent
;; handlers, like the db resolver, stay in the transit registry —
;; see server.notes.)

(defn- viewer-from [req]
  (fn [_rep]
    {:remote-addr (str (:remote-addr req))
     :user-agent  (get-in req [:headers "user-agent"] "unknown")}))

(defn query-handler [req]
  (solidrpc/handle-query req
                         {:read-handlers {api.viewer/tag (viewer-from req)}}))

(defn command-handler [req]
  (solidrpc/handle-command req
                           {:read-handlers {api.viewer/tag (viewer-from req)}}))

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
