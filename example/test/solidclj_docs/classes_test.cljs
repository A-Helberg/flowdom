(ns solidclj-docs.classes-test
  "Guards lib/solidclj-docs' Tailwind class manifest
  (resources/solidclj/docs/classes.txt), which consumers outside this
  monorepo point @source at so their Tailwind build generates the
  shell's internal classes.

  Same extraction as the lib's gen-classes: every whitespace-separated
  token of every string literal, over-inclusive by design (see the
  generator's docstring). Sources are inlined at compile time, so this
  cannot drift from what actually ships — it fails the moment a class
  is added to the lib without regenerating the manifest."
  (:require [cljs.test :refer [deftest is]]
            [clojure.set :as set]
            [clojure.string :as str]
            [shadow.resource :as rc]))

;; every .cljs source in lib/solidclj-docs — extend when the lib grows
;; a new file (a forgotten file with new classes shows up as a "stale
;; manifest" failure here, since the generator scans src/ globally).
(def ^:private sources
  [(rc/inline "solidclj/docs.cljs")
   (rc/inline "solidclj/docs/ui.cljs")])

(def ^:private manifest (rc/inline "solidclj/docs/classes.txt"))

(defn- tokens [source]
  (->> (re-seq #"\"((?:[^\"\\]|\\.)*)\"" source)
       (mapcat (fn [[_ lit]] (str/split lit #"\s+")))
       (remove str/blank?)
       set))

(deftest manifest-in-sync
  (let [used    (apply set/union (map tokens sources))
        listed  (->> (str/split manifest #"\s+") (remove str/blank?) set)
        missing (set/difference used listed)
        stale   (set/difference listed used)]
    (is (empty? missing)
        (str "tokens in lib source but not in the manifest — run "
             "`clojure -M:gen` in lib/solidclj-docs: " (pr-str (sort missing))))
    (is (empty? stale)
        (str "manifest tokens not found in any inlined source — regenerate "
             "with `clojure -M:gen`, or add the new source file to `sources` "
             "above: " (pr-str (sort stale))))))
