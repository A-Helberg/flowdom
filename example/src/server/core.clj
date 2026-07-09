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
   [server.notes]))

;; ---------------------------------------------------------------------------
;; API function whitelist — only registered vars are reachable via the transport.
;; ---------------------------------------------------------------------------

(registry/register! #'api.clock/time-flow)
(registry/register! #'api.clock/slow-time-flow)
(registry/register! #'api.clock/echo)
(registry/register! #'api.clock/scoreboard-flow)
(registry/register! #'server.notes/notes)
(registry/register! #'server.notes/add-note!)

;; ---------------------------------------------------------------------------
;; Router & server
;; ---------------------------------------------------------------------------

(defn index-handler [_req]
  {:status  200
   :headers {"content-type"  "text/html; charset=utf-8"
             "cache-control" "no-store, no-cache, must-revalidate, max-age=0"}
   :body    (slurp (clojure.java.io/resource "public/index.html"))})

(def handler
  (wrap-params
   (ring/ring-handler
    (ring/router
     [["/api/query"   {:get  solidrpc/handle-query}]
      ["/api/command" {:post solidrpc/handle-command}]
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
