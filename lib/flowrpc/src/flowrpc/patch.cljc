(ns flowrpc.patch
  (:require [editscript.core :as e]))

(defn diff
  "Returns a serializable edit vector representing the difference from a to b.
   Defaults to :algo :quick and :vec-timeout 10 (milliseconds per sequence,
   not a deadline for the whole diff). A timed-out sequence is replaced."
  ([a b] (diff a b {}))
  ([a b {:keys [algo vec-timeout] :or {algo :quick vec-timeout 10}}]
   (when-not (and (#{:quick :a-star} algo)
                 (number? vec-timeout) (pos? vec-timeout))
     (throw (ex-info "Invalid diff options" {:algo algo :vec-timeout vec-timeout})))
   (e/get-edits (e/diff a b {:algo algo :vec-timeout vec-timeout}))))

(defn apply-patch
  "Applies an edit vector produced by diff to a, returning the new value."
  [a edits]
  (e/patch a (e/edits->script edits)))
