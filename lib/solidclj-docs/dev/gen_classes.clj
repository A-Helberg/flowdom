(ns gen-classes
  "Regenerates resources/solidclj/docs/classes.txt — the Tailwind class
  manifest consumers point @source at (see README.md).

  The manifest is every whitespace-separated token of every string
  literal in src/**/*.cljs. Deliberately over-inclusive: docstring
  prose costs nothing (Tailwind ignores tokens that aren't utilities),
  while trying to extract only :class positions would miss classes
  composed with (str …) — and an under-inclusive manifest is exactly
  the broken-client-styling bug this exists to prevent.

  Run from lib/solidclj-docs:  clojure -M:gen
  Kept in sync by the example app's solidclj-docs.classes-test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private manifest-path "resources/solidclj/docs/classes.txt")

(defn tokens
  "All whitespace-separated tokens of all string literals in `source`."
  [source]
  (->> (re-seq #"\"((?:[^\"\\]|\\.)*)\"" source)
       (mapcat (fn [[_ lit]] (str/split lit #"\s+")))
       (remove str/blank?)))

(defn -main [& _]
  (let [toks (->> (file-seq (io/file "src"))
                  (filter #(and (.isFile ^java.io.File %)
                                (str/ends-with? (.getName ^java.io.File %) ".cljs")))
                  (mapcat (comp tokens slurp))
                  distinct
                  sort)]
    (io/make-parents manifest-path)
    (spit manifest-path (str (str/join "\n" toks) "\n"))
    (println "wrote" (count toks) "tokens to" manifest-path)))
