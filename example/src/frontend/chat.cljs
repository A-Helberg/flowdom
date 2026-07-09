(ns frontend.chat
  "The chat API. In a full-stack app this is a .cljc file: the :clj
  branch is the real implementation (registered with
  solidrpc.registry/register! under the same symbol), the :cljs
  branch delegates to solidrpc — callers on either side just call
  (chat/messages) and the rpc plumbing stays in this one file:

      (defn messages []
        #?(:clj  (db/messages-flow)
           :cljs (rpc/query 'chat/messages)))

  On this static site the :cljs branch talks to the fake server
  instead, so this file is plain .cljs."
  (:require [frontend.fake-rpc :as rpc]))

(defn messages []
  (rpc/query 'chat/messages))

(defn send! [text]
  (rpc/command 'chat/send! text))

(defn room-messages
  "A query with a REACTIVE argument: `room<` is an s/atom (anything
  watchable). query follows watchable args — when the atom changes,
  the running connection is closed and a fresh query starts for the
  new value. Callers just see a flow."
  [room<]
  (rpc/query 'chat/room-messages room<))

(defn send-to! [room text]
  (rpc/command 'chat/send-to! room text))
