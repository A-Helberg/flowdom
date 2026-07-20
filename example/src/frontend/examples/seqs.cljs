(ns frontend.examples.seqs)

(def langs ["Clojure" "ClojureScript" "flowdom"])

;; plain (for …) works too — it renders once, un-keyed, so it's right
;; for static lists; for changing collections reach for for-by
(defn example []
  [:ul {:class "list-disc pl-5"}
   (for [lang langs]
     [:li lang])])
