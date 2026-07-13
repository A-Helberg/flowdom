(ns solidclj.parity-test
  "JVM half of the parity suite: runs the shared fixtures against the
  simulator, asserting text derived from snapshots. The CLJS half
  (parity_test.cljs, same ns name) runs the SAME fixtures against
  real solid-js under happy-dom."
  (:require [clojure.test :refer [deftest is testing]]
            [solidclj.api :as s]
            [solidclj.parity-fixtures :as fx]))

(defn- text-of
  "textContent semantics over a snapshot: concatenate text leaves with
  no separator, numbers stringify, props maps skipped."
  [form]
  (cond
    (nil? form)    ""
    (string? form) form
    (number? form) (str form)
    (vector? form)
    (if (keyword? (first form))
      (let [body (if (map? (second form)) (nnext form) (next form))]
        (apply str (map text-of body)))
      (apply str (map text-of form)))   ;; vector OF forms (multi-root)
    :else ""))

(deftest parity-fixtures
  (doseq [{:keys [id make script]} (fx/fixtures)]
    (testing (name id)
      (let [{:keys [hiccup counters actions]} (make)
            t (s/render hiccup)]
        (try
          (doseq [[op a b :as step] script]
            (case op
              :text (is (= a (text-of (s/snapshot t)))
                        (str id " " (pr-str step)))
              :runs (is (= b @(get counters a))
                        (str id " " (pr-str step)))
              :act  ((get actions a))))
          (finally ((:dispose t))))))))
