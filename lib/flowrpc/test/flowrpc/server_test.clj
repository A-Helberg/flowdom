(ns flowrpc.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.stream :as s]
            [flowrpc.registry :as registry]
            [flowrpc.server :as server]
            [flowrpc.transit :as transit]))

(defn echo [x] x)
(defn stream-one [] (let [st (s/stream 1)] @(s/put! st :result) (s/close! st) st))

(use-fixtures :each
  (fn [f]
    (with-redefs [registry/registry (atom {})]
      (f))))

(defn query-req [fn-sym & args]
  {:query-params {"q" (transit/write {:fn-name fn-sym :args (vec args)})}})

(defn command-req [fn-sym & args]
  {:headers {"content-type" "application/transit+json"} ;; CSRF check 415s others
   :body    (java.io.StringReader. (transit/write {:fn-name fn-sym :args (vec args)}))})

;; ---------------------------------------------------------------------------
;; handle-query
;; ---------------------------------------------------------------------------

(deftest query-missing-q-param
  (let [resp (server/handle-query {:query-params {}})]
    (is (= 400 (:status resp)))))

(deftest query-fn-not-in-registry
  (let [resp (server/handle-query (query-req 'flowrpc.server-test/echo "hi"))]
    (is (= 404 (:status resp)))))

(deftest query-happy-path
  (registry/register! #'stream-one)
  (let [resp (server/handle-query (query-req 'flowrpc.server-test/stream-one))]
    (is (= 200 (:status resp)))
    (is (s/stream? (:body resp)))))

;; ---------------------------------------------------------------------------
;; handle-command
;; ---------------------------------------------------------------------------

(deftest command-fn-not-in-registry
  (let [resp (server/handle-command (command-req 'flowrpc.server-test/echo "hi"))]
    (is (= 404 (:status resp)))))

(deftest command-happy-path
  (registry/register! #'echo)
  (let [resp   (server/handle-command (command-req 'flowrpc.server-test/echo "hello"))
        result (transit/read (:body resp))]
    (is (= 200 (:status resp)))
    (is (true? (:ok result)))
    (is (= "hello" (:result result)))))
