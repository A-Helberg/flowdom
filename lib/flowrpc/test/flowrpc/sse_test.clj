(ns flowrpc.sse-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.stream :as s]
            [missionary.core :as m]
            [flowrpc.server :as server]
            [flowrpc.sse :as sse]
            [flowrpc.transit :as transit]))

;; Test diff-stream behaviour via the private fn using the public manifold->sse
;; output — we check the raw SSE text lines emitted.

(defn drain!
  "Collects all values from a stream into a vector, with a 2s timeout per take."
  [stream]
  (loop [acc []]
    (let [v @(s/try-take! stream ::closed 2000 ::timeout)]
      (cond
        (= v ::timeout) (throw (ex-info "drain! timed out" {}))
        (= v ::closed)  acc
        :else           (recur (conj acc v))))))

(deftest nil-values-roundtrip
  (let [values [nil {:a 1} nil nil {:a 2} {:a 3}]
        source (s/stream (count values))]
    (doseq [v values] @(s/put! source v))
    (s/close! source)
    (let [msgs (drain! (#'sse/diff-stream source))]
      (is (= [:full :patch :patch :patch :patch :patch] (mapv :rpc/type msgs)))
      (is (= values
             (vec (rest (reductions
                          (fn [base {:rpc/keys [type data]}]
                            (if (= type :full) data (flowrpc.patch/apply-patch base data)))
                          nil msgs))))))))

(deftest query-failures-reach-the-wire
  (doseq [initial-values [[] [{:a 1}]]]
    (let [flow (m/ap (m/amb (m/?> (m/seed initial-values))
                             (throw (ex-info "boom" {}))))
          frames (drain! (sse/manifold->sse (server/flow->stream flow)))
          expected (concat
                     (map #(str "event: full\ndata: " (transit/write %) "\n\n") initial-values)
                     [(str "event: exception\ndata: " (transit/write {:message "boom"}) "\n\n")])]
      (is (= (vec expected) frames)))))

(deftest cancellation-is-not-an-exception-event
  (let [flow (m/ap (throw (missionary.Cancelled.)))
        frames (drain! (sse/manifold->sse (server/flow->stream flow)))]
    (is (= [] frames))))

(deftest first-emission-is-full
  (let [source (s/stream 1)]
    @(s/put! source {:a 1})
    (s/close! source)
    (let [out  (#'flowrpc.sse/diff-stream source)
          msgs (drain! out)]
      (is (= 1 (count msgs)))
      (is (= :full (-> msgs first :rpc/type)))
      (is (= {:a 1} (-> msgs first :rpc/data))))))

(deftest second-emission-is-patch
  (let [source (s/stream 2)]
    @(s/put! source {:a 1})
    @(s/put! source {:a 2})
    (s/close! source)
    (let [out  (#'flowrpc.sse/diff-stream source)
          msgs (drain! out)]
      (is (= 2 (count msgs)))
      (is (= :full  (-> msgs first :rpc/type)))
      (is (= :patch (-> msgs second :rpc/type))))))

(deftest patch-reconstructs-value
  (let [source (s/stream 3)
        values [{:items [1 2 3]} {:items [1 2 4]} {:items [1 2 4] :done true}]]
    (doseq [v values] @(s/put! source v))
    (s/close! source)
    (let [out  (#'flowrpc.sse/diff-stream source)
          msgs (drain! out)]
      ;; Replay: apply each patch to reconstruct the final value.
      (let [reconstructed
            (reduce (fn [acc msg]
                      (condp = (:rpc/type msg)
                        :full  (:rpc/data msg)
                        :patch (flowrpc.patch/apply-patch acc (:rpc/data msg))))
                    nil
                    msgs)]
        (is (= (last values) reconstructed))))))
