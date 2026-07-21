(ns frontend.examples.suspense
  (:require [flowdom.rx :refer [rx ?]]
            [missionary.core :as m]
            [flowdom.docs.ui :as ui]))

;; A "request" is a flow built from a task: sleep 1.2s, emit once.
;; Memoized, because ? subscribes by flow identity — the same id must
;; return the same flow object (the one rule of async).
(def fetch-email
  (memoize
   (fn [id]
     (m/ap (m/? (m/sleep 1200))
           (str "user-" id "@example.com")))))

(defn example []
  (let [user-id (atom 1)]
    [:div {:class "space-y-3"}
     ;; reading a flow with no value yet makes the rx PENDING; the
     ;; nearest :fallback renders in its place. A new id is a new
     ;; recipe — pending again, fallback again, then the value.
     [:div {:fallback [:p {:class "text-gray-400"} "Loading…"]}
      (rx [:p {:class "font-mono"} (? (fetch-email (? user-id)))])]
     [ui/button {:on-click #(swap! user-id inc)} "Load next user"]]))
