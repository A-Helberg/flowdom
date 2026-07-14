(ns solidrpc.request-handlers-test
  "Per-request transit handlers, supplied at the mount point. The
  consumer's router fn has the request in scope, so a read handler
  that reconstructs a value from a session is a plain closure over
  it — solidrpc never learns the session mechanism."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.stream :as s]
            [solidrpc.registry :as registry]
            [solidrpc.server :as server]
            [solidrpc.transit :as transit]))

;; a server-side value type for the write direction
(defrecord Box [v])

(defn whoami [user] {:hello (:name user)})
(defn boxed-stream []
  (let [st (s/stream 1)]
    @(s/put! st (->Box 7))
    (s/close! st)
    st))
(defn boxed-command [] (->Box 7))

(use-fixtures :each
  (fn [f]
    (with-redefs [registry/registry (atom {})]
      (f))))

(defn- token
  ;; the client role: a generic token, written by the built-in Token
  ;; handler — no client-side registration of any kind
  [sid]
  (transit/token "test/user" {:sid sid}))

(defn- query-req [fn-sym & args]
  {:query-params {"q" (transit/write {:fn-name fn-sym :args (vec args)})}})

(defn- command-req [fn-sym & args]
  {:body (java.io.StringReader.
          (transit/write {:fn-name fn-sym :args (vec args)}))})

(defn- first-event
  "Takes the first SSE frame off a response body and returns
  [event decoded-data]."
  [resp ropts]
  (let [frame @(s/take! (:body resp))]
    [(second (re-find #"event: (\S+)" frame))
     (transit/read (second (re-find #"data: (.*)\n" frame)) ropts)]))

;; ---------------------------------------------------------------------------
;; transit-level: merge semantics
;; ---------------------------------------------------------------------------

(deftest per-call-handlers-with-generic-ref-default
  (testing "a tag without a handler reads as a generic Ref"
    (let [wire (transit/write (transit/token transit/db-tag {:basis-t 42}))]
      (is (= (transit/token transit/db-tag {:basis-t 42}) (transit/read wire)))
      (testing "…and a per-call handler reconstructs the value instead"
        (is (= [:resolved 42]
               (transit/read wire {:handlers {transit/db-tag
                                              (fn [{:keys [basis-t]}] [:resolved basis-t])}}))))))
  (testing "per-call handlers leave other calls untouched"
    (let [wire (transit/write (transit/token transit/db-tag {:basis-t 42}))]
      (transit/read wire {:handlers {transit/db-tag (fn [_] :other)}})
      (is (= (transit/token transit/db-tag {:basis-t 42}) (transit/read wire))))))

;; ---------------------------------------------------------------------------
;; handle-query
;; ---------------------------------------------------------------------------

(deftest query-read-handler-closes-over-the-request
  (registry/register! #'whoami)
  (let [req  (assoc (query-req 'solidrpc.request-handlers-test/whoami (token "abc"))
                    :cookies {"session" {:value "cookie-77"}})
        ;; the mount point: the consumer's fn has req in scope, so the
        ;; decoder is a closure over it
        resp (server/handle-query
              req
              {:read-handlers
               {"test/user" (fn [{:keys [sid]}]
                              {:name (str sid "@" (get-in req [:cookies "session" :value]))})}})]
    (is (= 200 (:status resp)))
    (let [[event data] (first-event resp nil)]
      (is (= "full" event))
      (is (= {:hello "abc@cookie-77"} data)
          "the endpoint fn received a reconstructed user value"))))

(deftest query-write-handlers-ride-the-stream
  (registry/register! #'boxed-stream)
  (let [resp (server/handle-query
              {:query-params {"q" (transit/write {:fn-name 'solidrpc.request-handlers-test/boxed-stream
                                                  :args []})}}
              {:write-handlers {Box {:tag "test/box" :rep :v}}})
        [event data] (first-event resp {:handlers {"test/box" (fn [v] [:box v])}})]
    (is (= "full" event))
    (is (= [:box 7] data)
        "the per-request write handler encoded the emission")))

(deftest rejecting-read-handler-maps-to-its-status
  (registry/register! #'whoami)
  (let [resp (server/handle-query
              (query-req 'solidrpc.request-handlers-test/whoami (token "bad"))
              {:read-handlers
               {"test/user" (fn [_] (throw (ex-info "no session" {:solidrpc/status 401})))}})]
    (is (= 401 (:status resp))
        "a session rejection is distinguishable from a server error")))

;; ---------------------------------------------------------------------------
;; handle-command
;; ---------------------------------------------------------------------------

(deftest command-read-and-write-handlers
  (registry/register! #'whoami)
  (registry/register! #'boxed-command)
  (testing "decode side"
    (let [resp (server/handle-command
                (command-req 'solidrpc.request-handlers-test/whoami (token "abc"))
                {:read-handlers {"test/user" (fn [{:keys [sid]}] {:name sid})}})
          body (transit/read (:body resp))]
      (is (= 200 (:status resp)))
      (is (= {:hello "abc"} (:result body)))))
  (testing "encode side"
    (let [resp (server/handle-command
                (command-req 'solidrpc.request-handlers-test/boxed-command)
                {:write-handlers {Box {:tag "test/box" :rep :v}}})
          body (transit/read (:body resp) {:handlers {"test/box" (fn [v] [:box v])}})]
      (is (= [:box 7] (:result body))))))

(deftest command-rejection-maps-to-its-status
  (registry/register! #'whoami)
  (let [resp (server/handle-command
              (command-req 'solidrpc.request-handlers-test/whoami (token "bad"))
              {:read-handlers
               {"test/user" (fn [_] (throw (ex-info "expired" {:solidrpc/status 401})))}})]
    (is (= 401 (:status resp)))
    (is (false? (:ok (transit/read (:body resp)))))))
