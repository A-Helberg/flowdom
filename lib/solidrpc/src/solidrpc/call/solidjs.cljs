(ns solidrpc.call.solidjs
  "SolidJS adapter: query returns a Missionary flow; command returns a Promise."
  (:require
   [promesa.core :as p]
   [solidrpc.stream :as stream]
   [solidrpc.transit :as transit]))

(defn query
  "Returns a Missionary flow backed by an SSE connection.

   Args may mix plain values and watchable refs (s/atom, hold, plain
   atom): the query FOLLOWS refs — when one changes, the connection is
   closed and reopened with the newly resolved args (equal values
   dedup with =, no reconnect). Unambiguous because plain args must be
   transit-serializable data, which a ref never is.

   Can be composed with m/eduction, m/reduce, etc. before rendering;
   bridge into hiccup with solidclj.missionary/hold."
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
