(ns solidclj.parity-test
  "CLJS half of the parity suite: runs the shared fixtures against
  REAL solid-js under happy-dom, asserting DOM textContent. The JVM
  half (parity_test.clj, same ns name) runs the SAME fixtures against
  the simulator."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [solidclj.hiccup.test-setup :refer [fresh-root]]
            [solidclj.api :as s]
            [solidclj.parity-fixtures :as fx]))

(deftest parity-fixtures
  (doseq [{:keys [id make script]} (fx/fixtures)]
    (testing (name id)
      (let [{:keys [hiccup counters actions]} (make)
            root    (fresh-root)
            dispose (s/render hiccup root)]
        (try
          (doseq [[op a b :as step] script]
            (case op
              :text (is (= a (.-textContent root))
                        (str id " " (pr-str step)))
              :runs (is (= b @(get counters a))
                        (str id " " (pr-str step)))
              :act  ((get actions a))))
          (finally (dispose)))))))
