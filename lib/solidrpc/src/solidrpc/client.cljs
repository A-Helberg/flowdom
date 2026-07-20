(ns solidrpc.client
  "Browser adapter: query returns a Missionary flow; command returns
  a Promise. Framework-free — a query composes with plain missionary,
  and flowdom reads it directly: (rx (? messages))."
  (:require
   [promesa.core :as p]
   [solidrpc.stream :as stream]
   [solidrpc.transit :as transit]))

(defn query
  "Returns a Missionary flow backed by an SSE connection.

   The flow is COLD and lazy: nothing connects until a consumer runs
   it, each consumer is its own connection, and cancelling
   disconnects. In flowdom every ? cell is one consumer, so a query
   read in one rx block gets the mount/unmount = connect/disconnect
   lifecycle for free. To fan the answer out to several readers over
   ONE connection, read it through one rx — rx blocks are
   m/signal-shared:

       (let [msgs (rx (? (chat/messages)))]   ;; one connection
         ... (? msgs) ... (? msgs) ...)       ;; any number of readers

   (Create the query outside the rx body that reads it — an rx re-run
   rebuilds whatever it constructs, and a rebuilt query is a fresh
   connection.)

   Args may mix plain values and watchable refs (any IWatchable, e.g.
   a plain atom): the query FOLLOWS refs — when one changes, the
   connection is closed and reopened with the newly resolved args
   (equal values dedup with =, no reconnect). Unambiguous because
   plain args must be transit-serializable data, which a ref never is.

   Until the first result arrives the flow has emitted nothing, which
   an rx reading it surfaces as pending — render 'connecting…' with an
   enclosing :fallback."
  [fn-id & args]
  (assert (symbol? fn-id) "query requires a symbol")
  (stream/follow-args (vec args) #(stream/sse-flow fn-id %)))

(defn command
  "Sends a POST to /api/command. Returns a Promise resolving to the result."
  [fn-id & args]
  (let [fq (cond
             (symbol? fn-id) (if (namespace fn-id)
                               (str fn-id)
                               (throw (js/Error. "command requires a qualified symbol")))
             (string? fn-id) fn-id
             :else           (throw (js/Error. "command requires a symbol or string")))]
    (-> (js/fetch "/api/command"
                  (clj->js {:method  "POST"
                            :headers {"content-type" "application/transit+json"
                                      "accept"       "application/transit+json"}
                            :body    (transit/write {:fn-name fq :args (vec args)})}))
        (p/then (fn [^js resp]
                  (-> (.text resp)
                      (p/then transit/read)
                      (p/then (fn [data]
                                (if (:ok data)
                                  (:result data)
                                  (p/rejected (ex-info "command failed" data)))))))))))
