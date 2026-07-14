(ns api.viewer
  "Who is asking? A request-scoped value type. The client passes a
  ViewerRef marker; the read handler at the mount point (server.core)
  reconstructs the viewer from the request itself — the only identity
  a bare request has: address and user agent. A real app reconstructs
  its current user here instead — cookie → session store, JWT →
  verify, db lookup — the mount-point closure doesn't care which.

  Same convention as db anchors: values on the server (tests and
  in-process callers pass the viewer map directly), refs on the
  client."
  (:require [missionary.core :as m]
            #?@(:cljs [[solidrpc.call.solidjs :as call]
                       [solidrpc.transit :as transit]])))

(def tag "app/viewer")

(defrecord ViewerRef [])

;; The marker always writes the same way, so the client-side write
;; handler is request-independent — registry scope.
#?(:cljs (transit/register-write-handler! ViewerRef tag (fn [_] {})))

(defn whoami<
  "One emission: the viewer, as reconstructed by the server."
  [viewer]
  #?(:clj  (m/ap viewer)
     :cljs (call/query `whoami< viewer)))
