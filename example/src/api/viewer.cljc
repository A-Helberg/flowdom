(ns api.viewer
  "Who is asking? A request-scoped value type. The client passes a
  generic marker token; the read handler at the mount point
  (server.core) reconstructs the viewer from the request itself — the
  only identity a bare request has: address and user agent. A real
  app reconstructs its current user here instead — cookie → session
  store, JWT → verify, db lookup — the mount-point closure doesn't
  care which.

  Same convention as db anchors: values on the server (tests and
  in-process callers pass the viewer map directly), tokens on the
  client. The marker needs no registration anywhere — a generic token
  writes under its own tag."
  (:require [missionary.core :as m]
            [solidrpc.transit :as transit]
            #?(:cljs [solidrpc.call.solidjs :as call])))

(def tag "app/viewer")

(defn viewer-token
  "The marker the client passes."
  []
  (transit/token tag))

(defn whoami<
  "One emission: the viewer, as reconstructed by the server."
  [viewer]
  #?(:clj  (m/ap viewer)
     :cljs (call/query `whoami< viewer)))
