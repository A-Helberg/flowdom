(ns flowrpc.sse-test
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.stream :as s]
   [missionary.core :as m]
   [flowrpc.patch :as patch]
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

(defn messages [values opts]
  (let [source (s/stream (count values))]
    (doseq [v values] @(s/put! source v))
    (s/close! source)
    (drain! (#'sse/diff-stream source opts))))

(deftest full-fallback-resets-the-patch-baseline
  (let [large {:items (vec (range 100))}
        values [nil {:a 1} large {:a 2} {:a 3} nil]
        calls (atom [])
        diff patch/diff
        msgs (with-redefs [patch/diff (fn [a b opts]
                                      (swap! calls conj [a b opts])
                                      (diff a b opts))]
               (messages values {:max-diff-size 4 :vec-timeout 5}))]
    (is (= [:full :patch :full :full :patch :patch] (mapv :rpc/type msgs)))
    (is (= [[nil {:a 1} {:algo :quick :vec-timeout 5}]
            [{:a 2} {:a 3} {:algo :quick :vec-timeout 5}]
            [{:a 3} nil {:algo :quick :vec-timeout 5}]] @calls))
    (is (= values
           (rest (reductions (fn [base {:rpc/keys [type data]}]
                               (if (= :full type) data (patch/apply-patch base data)))
                             nil msgs))))))

(deftest oversized-or-deep-values-never-call-diff
  (with-redefs [patch/diff (fn [& _] (throw (ex-info "Diff must be skipped" {})))]
    (doseq [[values opts] [[[(vec (range 10000)) nil] {}]
                          [[nil (nth (iterate vector nil) 65) nil] {}]
                          [[nil {:a 1} nil] {:max-diff-size 0}]]]
      (let [msgs (messages values opts)]
        (is (= values (mapv :rpc/data msgs)))
        (is (every? #(= :full (:rpc/type %)) msgs))))))

(deftest budget-walk-is-bounded-and-counts-nested-data
  (is (#'sse/diffable? {:a 1} 4))
  (is (not (#'sse/diffable? {:a 1} 3)))
  (is (#'sse/diffable? (nth (iterate vector nil) 64) 100))
  (is (not (#'sse/diffable? (nth (iterate vector nil) 65) 100)))
  (let [visited (atom 0)
        values (map (fn [x] (swap! visited inc) x) (iterate inc 0))]
    (is (not (#'sse/diffable? {:items values} 10)))
    (is (<= @visited 10) "The guard must stop without realizing the whole sequence")))

(deftest explicit-a-star-remains-supported
  (let [msgs (messages [{:a 1} {:a 2}] {:diff-algo :a-star})]
    (is (= :patch (:rpc/type (second msgs))))
    (is (= {:a 2} (patch/apply-patch {:a 1} (:rpc/data (second msgs)))))))

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
