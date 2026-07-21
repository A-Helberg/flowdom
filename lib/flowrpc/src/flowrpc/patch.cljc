(ns flowrpc.patch
  (:require [editscript.core :as e]))

(defn diff
  "Returns a serializable edit vector representing the difference from a to b."
  [a b]
  (e/get-edits (e/diff a b)))

(defn apply-patch
  "Applies an edit vector produced by diff to a, returning the new value."
  [a edits]
  (e/patch a (e/edits->script edits)))
