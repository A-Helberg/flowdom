(ns api.server-info
  "How long has the server been up? A second token-passable value type,
  next to api.viewer: same mechanism, different closure. The viewer's
  handler closes over the request; this one closes over server
  startup state (see server.core). The only difference between a
  request-scoped and a startup-scoped value is what its handler's
  closure captures — the marker, the facade and the wire are
  identical."
  (:require [missionary.core :as m]
            [solidrpc.transit :as transit]
            #?(:cljs [solidrpc.call.solidjs :as call])))

(def tag "app/server-info")

(defn server-info-token
  "The marker the client passes."
  []
  (transit/token tag))

(defn server-info<
  "One emission: startup instant and uptime — reconstructed at
  request time, from a closure made at startup time."
  [info]
  #?(:clj  (m/ap info)
     :cljs (call/query `server-info< info)))
