(ns solidclj.snapshot-test
  "render / snapshot / with-render — the JVM test experience — plus
  dogfooding of the recommended assertion stack: matcher-combinators
  (partial + predicate matching) and hiccup-find (CSS-ish queries over
  snapshots)."
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup-find.core :as hf]
            [matcher-combinators.test]        ;; extends `is` with match?
            [solidclj.api :as s]
            [solidclj.hiccup :as hic]))

(deftest static-snapshot
  (hic/with-render [t [:div.box#main {:title "hello"}
                       [:span "text"]
                       [:ul (for [i (range 2)] ^{:key i} [:li i])]]]
    (is (= [:div {:class "box" :id "main" :title "hello"}
            [:span "text"]
            [:ul [:li 0] [:li 1]]]
           (s/snapshot t)))))

(deftest reactive-snapshot
  (let [n (s/atom 1)]
    (hic/with-render [t [:p "count: " (fn [] @n)]]
      (is (= [:p "count: " 1] (s/snapshot t)))
      (swap! n inc)
      (is (= [:p "count: " 2] (s/snapshot t))))))

(deftest control-flow-collapses-and-splices
  (let [on?   (s/atom false)
        items (s/atom [{:id 1} {:id 2}])]
    (hic/with-render [t [:div
                         [:show {:when on? :fallback [:i "off"]} [:b "on"]]
                         [:ul [:for {:each items}
                               (fn [item _] [:li (:id item)])]]]]
      (is (= [:div [:i "off"] [:ul [:li 1] [:li 2]]] (s/snapshot t)))
      (reset! on? true)
      (swap! items conj {:id 3})
      (is (= [:div [:b "on"] [:ul [:li 1] [:li 2] [:li 3]]] (s/snapshot t))))))

(deftest classlist-merges-and-nil-props-drop
  (let [active? (s/atom true)]
    (hic/with-render [t [:a.base {:class {:active active? :hidden false}
                                  :href nil}]]
      (is (= [:a {:class "base active"}] (s/snapshot t)))
      (reset! active? false)
      (is (= [:a {:class "base"}] (s/snapshot t))))))

(deftest handlers-are-data
  (let [counter (fn [start]
                  (let [n (s/atom start)]
                    [:div
                     [:button {:onClick (fn [_] (swap! n inc))} "+"]
                     [:span (fn [] @n)]]))]
    (hic/with-render [t [counter 5]]
      (let [snap (s/snapshot t)]
        (is (= [:span 5] (nth snap 2)))
        (let [on-click (get-in snap [1 1 :onClick])]
          (is (fn? on-click))
          (on-click :fake-event)
          (on-click :fake-event))
        (is (= [:span 7] (nth (s/snapshot t) 2)))))))

(deftest with-render-disposes-on-throw
  (let [leaked (s/atom 0)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (hic/with-render [t [:div (fn [] @leaked)]]
                   (s/snapshot t)
                   (throw (ex-info "test-throw" {})))))
    (is (empty? (.getWatches leaked)) "bridge watch removed despite the throw")))

(deftest multi-form-and-empty-roots
  (hic/with-render [t [:<> [:p "a"] [:p "b"]]]
    (is (= [[:p "a"] [:p "b"]] (s/snapshot t)) "fragment root → vector OF forms"))
  (hic/with-render [t [:show {:when false} [:p "never"]]]
    (is (nil? (s/snapshot t)) "nothing rendered → nil")))

(deftest portal-marker-in-snapshot
  (hic/with-render [t [:div [:portal {:mount :nowhere} [:p "tp"]]]]
    (is (= [:div [:portal [:p "tp"]]] (s/snapshot t)))))

(deftest style-snapshot
  (let [c (s/atom "red")]
    (hic/with-render [t [:p {:style {:font-size "12px" :color c}}]]
      (is (= [:p {:style {"fontSize" "12px" "color" "red"}}] (s/snapshot t)))
      (reset! c "blue")
      (is (= "blue" (get-in (s/snapshot t) [1 :style "color"]))))))

;; ---- Dogfood: the recommended assertion stack --------------------------------

(defn- todo-app []
  (let [todos (s/atom [])
        text  (s/atom "")]
    [:div
     [:input {:value text :onChange (fn [v] (reset! text v))}]
     [:button.add {:onClick (fn [_] (swap! todos conj {:label @text}) (reset! text ""))} "add"]
     [:show {:when (fn [] (seq @todos)) :fallback [:p.empty "empty"]}
      [:ul [:for {:each todos} (fn [todo _] [:li.todo (:label todo)])]]]]))

(deftest matcher-combinators-over-snapshots
  (hic/with-render [t [todo-app]]
    (testing "maps match partially; fns are predicates"
      (is (match? [:div
                   [:input {:onChange fn?}]           ;; ignores :value
                   [:button {:onClick fn?} "add"]     ;; ignores :class
                   [:p {:class "empty"} "empty"]]
                  (s/snapshot t))))))

(deftest hiccup-find-over-snapshots
  (hic/with-render [t [todo-app]]
    (let [snap   (s/snapshot t)
          typing (get-in snap [1 1 :onChange])
          add    (get-in snap [2 1 :onClick])]
      (typing "buy milk") (add :c)
      (typing "walk dog") (add :c))
    (let [snap (s/snapshot t)]
      (testing "tag + class queries work against snapshot :class strings"
        (is (= 2 (count (hf/hiccup-find [:li.todo] snap))))
        (is (= 1 (count (hf/hiccup-find [:button.add] snap))))
        (is (empty? (hf/hiccup-find [:p.empty] snap)) "empty-state gone"))
      (testing "hiccup-text extracts text content"
        (is (= "buy milk walk dog"
               (clojure.string/join " " (map hf/hiccup-text (hf/hiccup-find [:li] snap)))))))))
