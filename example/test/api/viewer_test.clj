(ns api.viewer-test
  "The request-scoped value slice, driven through the REAL mount
  handler in server.core: a fake ring request goes in, the read
  handler closes over it, and the endpoint receives a reconstructed
  viewer value."
  (:require [clojure.test :refer [deftest is testing]]
            [manifold.stream :as s]
            [missionary.core :as m]
            [api.viewer :as viewer]
            [server.core :as core]
            [solidrpc.transit :as transit]))

(def ^:private ref-out
  ;; the client role: on cljs the registry writes ViewerRef; here the
  ;; test supplies the same handler per call
  {api.viewer.ViewerRef {:tag viewer/tag :rep (fn [_] {})}})

(defn- first-data [resp]
  (let [frame @(s/take! (:body resp))]
    (transit/read (second (re-find #"data: (.*)\n" frame)))))

(deftest whoami-reconstructs-the-viewer-from-the-request
  (let [req  {:remote-addr "10.1.2.3"
              :headers     {"user-agent" "kaocha"}
              :query-params {"q" (transit/write {:fn-name 'api.viewer/whoami<
                                                 :args    [(viewer/->ViewerRef)]}
                                                {:handlers ref-out})}}
        resp (core/query-handler req)]
    (is (= 200 (:status resp)))
    (is (= {:remote-addr "10.1.2.3" :user-agent "kaocha"} (first-data resp))
        "the marker ref became a value only the request could supply")))

(deftest in-process-callers-pass-the-value-directly
  ;; same convention as db anchors: no wire, no handlers — the JVM
  ;; side hands the facade a viewer value
  (let [out (atom nil)]
    ((m/reduce (fn [_ v] (reset! out v) nil) nil
               (viewer/whoami< {:remote-addr "repl" :user-agent "test"}))
     identity identity)
    (is (= {:remote-addr "repl" :user-agent "test"} @out))))
