(ns flowrpc.server-test
  "The CSRF content-type gate on /api/command, exercised through the
  example's real registered endpoints (server.core requires and
  registers them)."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api]
            [api.notes]
            [server.core :as core]
            [server.notes]
            [flowrpc.transit :as transit]))

(defn- command-req [content-type note]
  {:headers (when content-type {"content-type" content-type})
   :body    (java.io.StringReader.
             (transit/write {:fn-name 'api.notes/add-note! :args [note]}))})

(deftest command-requires-the-transit-content-type
  (testing "the correct content-type is accepted"
    (let [resp (core/command-handler
                (command-req "application/transit+json" (str "ct-ok-" (gensym))))]
      (is (= 200 (:status resp)))
      (is (:ok (transit/read (:body resp))))))

  (testing "charset params are allowed"
    (let [resp (core/command-handler
                (command-req "application/transit+json; charset=utf-8"
                             (str "ct-charset-" (gensym))))]
      (is (= 200 (:status resp)))))

  (testing "a CORS-simple content-type is rejected with 415 — the CSRF gate"
    (doseq [ct ["text/plain" "application/x-www-form-urlencoded" "multipart/form-data"]]
      (let [resp (core/command-handler (command-req ct "should-not-run"))]
        (is (= 415 (:status resp)) (str ct " must be rejected"))
        (is (not (:ok (transit/read (:body resp))))))))

  (testing "a missing content-type is rejected"
    (is (= 415 (:status (core/command-handler (command-req nil "nope")))))))

(deftest rejected-commands-do-not-execute
  ;; the whole point: a forged request never reaches the endpoint fn
  (let [note (str "forged-" (gensym))]
    (core/command-handler (command-req "text/plain" note))
    (is (not (some #{note} (api.notes/all-notes (datomic.api/db server.notes/conn))))
        "the write was refused before the var was called")))
