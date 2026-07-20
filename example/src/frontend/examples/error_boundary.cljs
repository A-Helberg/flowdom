(ns frontend.examples.error-boundary
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

;; A throwing rx travels upward as a value until an :error-boundary
;; catches it. Recovery here is a HEAL: clearing the atom re-runs the
;; same rx and the original subtree returns, state intact. (The second
;; fallback argument, `retry`, remounts from scratch instead — for
;; failures whose cause isn't a dependency you can clear.)
(defn example []
  (let [boom (atom false)]
    [:div {:class "space-y-3"}
     [ui/button {:on-click #(reset! boom true)} "Throw"]

     [:error-boundary
      {:fallback (fn [err _retry]
                   [:div {:class "space-y-2"}
                    [:p {:class "text-red-600"} "Caught: " (ex-message err)]
                    [ui/button {:on-click (fn [_] (reset! boom false))}
                     "Recover"]])}
      (rx (if (? boom)
            (throw (ex-info "kaboom" {}))
            [:p {:class "text-green-600"} "All good."]))]]))
