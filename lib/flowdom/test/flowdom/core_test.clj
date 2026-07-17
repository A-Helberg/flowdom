(ns flowdom.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [flowdom.core :as fd :refer [with-render snapshot for-by]]
            [flowdom.rx :as rx :refer [rx ?]]
            [missionary.core :as m]))

;; ---------------------------------------------------------------------------
;; static trees

(deftest static-tree
  (with-render [t [:div {:class "box"} [:span "hi"] 42]]
    (is (= [:div {:class "box"} [:span "hi"] 42] (snapshot t)))))

;; ---------------------------------------------------------------------------
;; the counter from the README

(defn counter [{:keys [start]}]
  (let [n (atom start)]
    [:div
     [:span (rx (? n))]
     [:button {:on-click (fn [_] (swap! n inc))} "+"]]))

(deftest counter-behaves
  (with-render [t [counter {:start 5}]]
    (let [snap (snapshot t)]
      (is (= [:span 5] (nth snap 1)))
      (is (= "+" (get-in snap [2 2])))
      (is (fn? (get-in snap [2 1 :on-click]))))
    ((get-in (snapshot t) [2 1 :on-click]) :click) ;; handlers are data
    (is (= [:span 6] (nth (snapshot t) 1)))))

;; ---------------------------------------------------------------------------
;; ? crosses function boundaries

(defn fmt-price [price] (str "$" (? price)))

(deftest helper-fn-reads
  (let [price (atom 9)]
    (with-render [t [:span (rx (fmt-price price))]]
      (is (= [:span "$9"] (snapshot t)))
      (swap! price inc)
      (is (= [:span "$10"] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; structural slots: an rx emitting different hiccup swaps the subtree

(deftest structural-switch
  (let [logged-in (atom false)]
    (with-render [t [:main (rx (if (? logged-in)
                                 [:section [:h1 "profile"]]
                                 [:form "login"]))]]
      (is (= [:main [:form "login"]] (snapshot t)))
      (reset! logged-in true)
      (is (= [:main [:section [:h1 "profile"]]] (snapshot t))))))

(deftest inner-tick-does-not-remount
  ;; a nested rx ticking must update in place, not rebuild the slot
  (let [n      (atom 0)
        mounts (atom 0)
        view   (fn []
                 (swap! mounts inc)
                 [:p (rx (? n))])]
    (with-render [t [:div (rx [:section [view]])]]
      (is (= [:div [:section [:p 0]]] (snapshot t)))
      (swap! n inc)
      (swap! n inc)
      (is (= [:div [:section [:p 2]]] (snapshot t)))
      (is (= 1 @mounts)))))

;; ---------------------------------------------------------------------------
;; dynamic props

(deftest dynamic-prop
  (let [dark (atom false)]
    (with-render [t [:div {:class (rx (if (? dark) "dark" "light"))} "x"]]
      (is (= [:div {:class "light"} "x"] (snapshot t)))
      (reset! dark true)
      (is (= [:div {:class "dark"} "x"] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; conditional dependencies unsubscribe

(deftest conditional-deps-gc
  (let [use-a (atom true)
        a     (atom 1)
        b     (atom 10)
        runs  (atom 0)]
    (with-render [t [:span (rx (swap! runs inc)
                               (if (? use-a) (? a) (? b)))]]
      (is (= [:span 1] (snapshot t)))
      (reset! use-a false)
      (is (= [:span 10] (snapshot t)))
      (let [before @runs]
        (swap! a inc) ;; no longer a dependency
        (is (= before @runs))
        (is (= [:span 10] (snapshot t)))))))

;; ---------------------------------------------------------------------------
;; for-by: keyed, fine-grained, state survives reorder

(defn todo-item [todo]
  (let [clicks (atom 0)]
    [:li {:on-bump (fn [] (swap! clicks inc))}
     (rx (str (:title (? todo)) "/" (? clicks)))]))

(deftest for-by-keyed
  (let [todos (atom [{:id 1 :title "a"} {:id 2 :title "b"}])]
    (with-render [t [:ul (for-by :id todos todo-item)]]
      (is (= ["a/0" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; bump the first item's local state
      ((get-in (snapshot t) [1 1 :on-bump]))
      (is (= ["a/1" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; item value update ticks in place
      (swap! todos assoc-in [0 :title] "A")
      (is (= ["A/1" "b/0"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; reorder: processes and local state survive
      (swap! todos (comp vec reverse))
      (is (= ["b/0" "A/1"] (mapv #(nth % 2) (rest (snapshot t)))))
      ;; removal unmounts
      (swap! todos (fn [ts] (vec (remove #(= 2 (:id %)) ts))))
      (is (= ["A/1"] (mapv #(nth % 2) (rest (snapshot t))))))))

;; ---------------------------------------------------------------------------
;; suspense: pending is a value; :fallback renders in place

(deftest suspense-fallback
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div {:fallback [:span "loading"]}
                     (rx [:p "hi " (? user<)])]]
      (is (= [:div [:span "loading"]] (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "hi " "ann"]] (snapshot t))))))

(deftest pending-propagates-without-fallback
  (let [dv    (m/dfv)
        user< (m/ap (m/? dv))]
    (with-render [t [:div (rx [:p (? user<)])]]
      (is (= rx/pending (snapshot t)))
      (dv "ann")
      (is (= [:div [:p "ann"]] (snapshot t))))))

;; ---------------------------------------------------------------------------
;; error boundary: errors travel as values, heal on dependency change,
;; remount on retry

(deftest error-boundary-heals
  (let [flag (atom false)]
    (with-render [t [:div
                     [:error-boundary
                      {:fallback (fn [e retry]
                                   [:button {:on-retry retry} (ex-message e)])}
                      (rx (if (? flag) (throw (ex-info "boom" {})) [:p "fine"]))]]]
      (is (= [:div [:p "fine"]] (snapshot t)))
      (reset! flag true)
      (is (= "boom" (get-in (snapshot t) [1 2])))
      (reset! flag false) ;; dependency change heals in place
      (is (= [:div [:p "fine"]] (snapshot t))))))

(deftest error-boundary-retry-remounts
  ;; fails on first mount, succeeds on remount — dep-stable, so only
  ;; retry (not healing) can recover it
  (let [mounts (atom 0)
        view   (fn []
                 (swap! mounts inc)
                 (let [attempt @mounts]
                   (rx (if (= 1 attempt) (throw (ex-info "boom" {})) [:p "ok"]))))]
    (with-render [t [:error-boundary
                     {:fallback (fn [e retry] [:button {:on-retry retry} "retry"])}
                     [view]]]
      (is (= "retry" (nth (snapshot t) 2)))
      (is (= 1 @mounts))
      ((get-in (snapshot t) [1 :on-retry]))
      (is (= [:p "ok"] (snapshot t)))
      (is (= 2 @mounts)))))

;; ---------------------------------------------------------------------------
;; async: emissions from other threads, awaited

(deftest await-async-tree
  (let [tick< (m/ap (m/? (m/sleep 40 "later")))]
    (with-render [t [:span {:fallback "..."} (rx (? tick<))]]
      (is (= [:span "..."] (snapshot t)))
      (is (= [:span "later"]
             (fd/await t #(= [:span "later"] %) :timeout 1000))))))
