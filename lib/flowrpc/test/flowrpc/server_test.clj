(ns flowrpc.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [manifold.stream :as s]
            [flowrpc.patch :as patch]
            [flowrpc.registry :as registry]
            [flowrpc.server :as server]
            [flowrpc.sse-test :refer [drain!]]
            [flowrpc.transit :as transit]))

(defn echo [x] x)
(defn stream-one [] (let [st (s/stream 1)] @(s/put! st :result) (s/close! st) st))

(defn stream-two []
  (let [st (s/stream 2)]
    @(s/put! st {:a 1})
    @(s/put! st {:a 2})
    (s/close! st)
    st))

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

(deftest query-can-disable-diffing
  (registry/register! #'stream-two)
  (let [resp (server/handle-query (query-req 'flowrpc.server-test/stream-two)
                                  {:max-diff-size 0})]
    (is (= 200 (:status resp)))
    (is (= (mapv #(str "event: full\ndata: " (transit/write %) "\n\n")
                 [{:a 1} {:a 2}])
           (drain! (:body resp))))))

(deftest query-forwards-algorithm-and-timeout
  (registry/register! #'stream-two)
  (let [diff patch/diff
        seen (atom [])]
    (with-redefs [patch/diff (fn [a b opts]
                              (swap! seen conj opts)
                              (diff a b opts))]
      (let [resp (server/handle-query (query-req 'flowrpc.server-test/stream-two)
                                      {:diff-algo :a-star :vec-timeout 5})]
        (is (= 2 (count (drain! (:body resp)))))
        (is (= [{:algo :a-star :vec-timeout 5}] @seen))))))

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
