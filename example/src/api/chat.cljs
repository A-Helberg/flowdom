(ns api.chat
  "The chat api namespace. The Queries & commands page shows the
  full-stack .cljc version, where the :clj branch is the real
  implementation. This site is static, so this file is plain .cljs
  and its queries and commands talk to the fake server."
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
