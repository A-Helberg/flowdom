(ns api.server-info-test
  "The startup-scoped ref type, driven through the same real mount
  handler as the viewer — the contrast is the closure's lifetime, not
  the mechanism."
  (:require [clojure.test :refer [deftest is]]
            [manifold.stream :as s]
            [missionary.core :as m]
            [api.server-info :as info]
            [server.core :as core]
            [solidrpc.transit :as transit]))

(defn- first-data [resp]
  (let [frame @(s/take! (:body resp))]
    (transit/read (second (re-find #"data: (.*)\n" frame)))))

(deftest server-info-reconstructs-from-startup-state
  (let [req  {:query-params {"q" (transit/write {:fn-name 'api.server-info/server-info<
                                                 :args    [(info/server-info-ref)]})}}
        resp (core/query-handler req)
        data (first-data resp)]
    (is (= 200 (:status resp)))
    (is (pos? (:started-at data)))
    (is (<= 0 (:uptime-ms data))
        "reconstructed at request time from a startup-time closure")))

(deftest in-process-callers-pass-the-value-directly
  (let [out (atom nil)]
    ((m/reduce (fn [_ v] (reset! out v) nil) nil
               (info/server-info< {:started-at 1 :uptime-ms 2}))
     identity identity)
    (is (= {:started-at 1 :uptime-ms 2} @out))))
