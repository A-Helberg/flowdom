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

(defn- deep-transit
  "A transit-json string nested `n` deep: [[[...]]]. Built as a raw
  string so the guard can be exercised without first parsing it."
  [n]
  (str (apply str (repeat n "[")) (apply str (repeat n "]"))))

(deftest oversized-and-overdeep-payloads-are-rejected-with-413
  (testing "a payload nested past the default depth is rejected BEFORE parsing"
    (let [resp (core/command-handler
                {:headers {"content-type" "application/transit+json"}
                 :body    (java.io.StringReader. (deep-transit 5000))})]
      (is (= 413 (:status resp)) "deep nesting rejected, no stack overflow")))

  (testing "an oversized payload is rejected"
    (let [big  (str "[\"^ \",\"~:fn-name\",\"api.notes/add-note!\",\"~:args\",[\""
                    (apply str (repeat (* 2 1024 1024) \x)) "\"]]")
          resp (core/command-handler
                {:headers {"content-type" "application/transit+json"}
                 :body    (java.io.StringReader. big)})]
      (is (= 413 (:status resp)))))

  (testing "an ordinary command is unaffected"
    (let [resp (core/command-handler
                (command-req "application/transit+json" (str "ok-" (gensym))))]
      (is (= 200 (:status resp))))))

(deftest depth-guard-is-purely-lexical
  ;; a legitimately nested-but-reasonable arg still decodes; the guard
  ;; bounds depth, it doesn't reject nesting per se
  (let [note {:a {:b {:c "deep enough"}}}
        wire (transit/write {:fn-name 'api.notes/ping! :args [note]})]
    (is (map? (transit/read wire {:max-depth 64}))
        "well within the limit → decodes normally")
    (is (thrown? clojure.lang.ExceptionInfo
                 (transit/read wire {:max-depth 2}))
        "past a tight limit → rejected")))
