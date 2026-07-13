(ns solidclj.control-flow-test
  "JVM control-flow components: Show, Switch/Match, For, Index,
  Dynamic, ErrorBoundary, Portal, Suspense — semantics that must match
  what real Solid does in the browser (the parity fixtures are the
  cross-check)."
  (:require [clojure.test :refer [deftest is testing]]
            [solidclj.api :as s]
            [solidclj.hiccup :as hic]
            [solidclj.runtime :as rt]))

(defn- sc [slot] (:content @slot))

(deftest show-toggles-children-and-fallback
  (let [on? (s/atom true)
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div [:show {:when on? :fallback [:p "off"]}
                                 [:b "on"]]]))
        slot (first (:children @tree))]
    (is (= "b" (:tag @(first (sc slot)))))
    (reset! on? false)
    (is (= "p" (:tag @(sc slot))))
    (is (= ["off"] (:children @(sc slot))))
    (reset! on? true)
    (is (= "b" (:tag @(first (sc slot)))))
    (dispose)))

(deftest js-truthiness-matches-the-browser
  (testing "0 and \"\" are falsy for :when, exactly like real Solid"
    (let [n (s/atom 0)
          [tree dispose] (rt/create-root
                          #(hic/as-element
                            [:div [:show {:when n :fallback [:i "none"]}
                                   [:b "some"]]]))
          slot (first (:children @tree))]
      (is (= "i" (:tag @(sc slot))) "0 is falsy")
      (reset! n 3)
      (is (= "b" (:tag @(first (sc slot)))))
      (reset! n "")
      (is (= "i" (:tag @(sc slot))) "\"\" is falsy")
      (dispose))))

(deftest switch-evaluates-matches-in-order
  (let [x (s/atom 1)
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div
                           [:switch {:fallback [:p "other"]}
                            [:match {:when (fn [] (= 1 @x))} [:b "one"]]
                            [:match {:when (fn [] (= 2 @x))} [:i "two"]]]]))
        slot (first (:children @tree))]
    (is (= "b" (:tag @(first (sc slot)))))
    (reset! x 2)
    (is (= "i" (:tag @(first (sc slot)))))
    (reset! x 99)
    (is (= "p" (:tag @(sc slot))))
    (dispose)))

(deftest for-keyed-reconciliation
  (let [item-a {:id :a}
        item-b {:id :b}
        item-c {:id :c}
        render-count (atom 0)
        items (s/atom [item-a item-b])
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:ul [:for {:each items}
                                (fn [item index]
                                  (swap! render-count inc)
                                  (let [local (s/atom (str "local-" (name (:id item))))]
                                    [:li {:data-id (:id item)}
                                     (fn [] (str (index) "-" @local))]))]]))
        for-slot (first (:children @tree))
        row-view (fn [i] (sc (first (:children @(nth (sc for-slot) i)))))]
    (is (= 2 @render-count) "one render per row")
    (is (= "0-local-a" (row-view 0)))
    (is (= "1-local-b" (row-view 1)))

    (testing "reorder: same identities → rows reused, indexes update"
      (reset! items [item-b item-a])
      (is (= 2 @render-count) "render-fn NOT re-run on reorder")
      (is (= "0-local-b" (row-view 0)) "b moved to 0, kept row-local state")
      (is (= "1-local-a" (row-view 1))))

    (testing "removal disposes; a fresh identity renders once"
      (reset! items [item-b])
      (is (= 1 (count (sc for-slot))))
      (reset! items [item-c item-b])
      (is (= 3 @render-count))
      (is (= "0-local-c" (row-view 0)))
      (is (= "1-local-b" (row-view 1)) "b kept state through the removal round"))
    (dispose)))

(deftest for-row-cleanup
  (let [row-cleanups (atom [])
        i1 {:id 1} i2 {:id 2}
        items (s/atom [i1 i2])
        [_ dispose] (rt/create-root
                     #(hic/as-element
                       [:ul [:for {:each items}
                             (fn [item _]
                               [:li (fn []
                                      (rt/on-cleanup
                                       (fn [] (swap! row-cleanups conj (:id item))))
                                      (:id item))])]]))]
    (reset! items [i1])
    (is (= [2] @row-cleanups) "departed row's cleanup ran")
    (dispose)
    (is (= [2 1] @row-cleanups) "remaining row cleaned on unmount")))

(deftest for-static-collection-and-duplicates
  (let [[tree dispose] (rt/create-root
                        #(hic/as-element
                          [:ul [:for {:each [{:n 1} {:n 2} {:n 3}]}
                                (fn [item _] [:li (:n item)])]]))
        slot (first (:children @tree))]
    (is (= [["li" [1]] ["li" [2]] ["li" [3]]]
           (map (fn [el] [(:tag @el) (:children @el)]) (sc slot))))
    (dispose))
  (let [dup {:id :dup}
        items (s/atom [dup dup])
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:ul [:for {:each items}
                                (fn [_ index] [:li (fn [] (index))])]]))
        slot (first (:children @tree))]
    (is (= 2 (count (sc slot))) "duplicate identities each get a row")
    (reset! items [dup])
    (is (= 1 (count (sc slot))))
    (dispose)))

(deftest index-position-reuse
  (let [idx-renders (atom 0)
        items (s/atom ["a" "b"])
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:ol [:index {:each items}
                                (fn [item-getter i]
                                  (swap! idx-renders inc)
                                  [:li (fn [] (str i "-" (item-getter)))])]]))
        islot (first (:children @tree))
        pos-view (fn [i] (sc (first (:children @(nth (sc islot) i)))))]
    (is (= 2 @idx-renders))
    (is (= "0-a" (pos-view 0)))
    (is (= "1-b" (pos-view 1)))

    (testing "in-place mutation: positions reused, item signals update"
      (reset! items ["a2" "b2"])
      (is (= 2 @idx-renders) "no re-render")
      (is (= "0-a2" (pos-view 0)))
      (is (= "1-b2" (pos-view 1))))

    (testing "grow and shrink at the tail"
      (reset! items ["a2" "b2" "c"])
      (is (= 3 @idx-renders))
      (is (= "2-c" (pos-view 2)))
      (reset! items ["a2"])
      (is (= 1 (count (sc islot)))))
    (dispose)))

(deftest dynamic-swaps-tag-and-component
  (let [comp-a (fn [_props] (hic/as-element [:article "A"]))
        c (s/atom "section")
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div [:dynamic {:component c :class "dyn"} "child"]]))
        slot (first (:children @tree))]
    (is (= "section" (:tag @(sc slot))))
    (is (= "dyn" (get-in @(sc slot) [:props :class])))
    (is (= ["child"] (:children @(sc slot))))
    (reset! c "aside")
    (is (= "aside" (:tag @(sc slot))))
    (reset! c comp-a)
    (is (= "article" (:tag @(sc slot))))
    (dispose)))

(deftest error-boundary-catches-build-errors-and-resets
  (let [should-throw (atom true)
        bomb (fn []
               (when @should-throw (throw (ex-info "boom" {})))
               [:p "recovered"])
        [tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div [:error-boundary
                                 {:fallback (fn [err reset]
                                              [:button {:onClick (fn [_] (reset))}
                                               (ex-message err)])}
                                 [bomb]]]))
        slot (first (:children @tree))]
    (is (= "button" (:tag @(sc slot))))
    (is (= ["boom"] (:children @(sc slot))))
    (reset! should-throw false)
    ((get-in @(sc slot) [:props :onClick]) :fake-event)
    (is (= "p" (:tag @(first (sc slot)))) "reset re-attempted the build")
    (is (= ["recovered"] (:children @(first (sc slot)))))
    (dispose)))

(deftest portal-renders-inline-under-marker
  (let [[tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div [:portal {:mount :ignored-on-jvm} [:p "teleported"]]]))
        portal (first (:children @tree))]
    (is (rt/portal-node? portal))
    (is (= "p" (:tag @(first (:children @portal)))))
    (dispose)))

(deftest suspense-is-a-transparent-stub
  (let [[tree dispose] (rt/create-root
                        #(hic/as-element
                          [:div [:suspense {:fallback [:p "loading"]}
                                 [:b "content"]]]))
        content (first (:children @tree))]
    (is (vector? content))
    (is (= "b" (:tag @(first content))))
    (dispose)))
