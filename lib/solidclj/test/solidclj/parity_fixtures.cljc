(ns solidclj.parity-fixtures
  "Shared fixtures run through BOTH the JVM simulator (parity_test.clj
  via snapshot) and real solid-js under happy-dom (parity_test.cljs
  via textContent). Any drift between the two runtimes fails a test
  instead of lurking.

  Each fixture's `:make` builds fresh state and returns
  {:hiccup … :actions {kw 0-arg-fn} :counters {kw plain-atom}}.
  The `:script` is a sequence of steps:

    [:text \"expected\"]   root text content equals (textContent
                           semantics: adjacent text runs concatenate
                           with NO separator; numbers stringify)
    [:act :action-kw]      run the named action (a satom mutation)
    [:runs :counter-kw n]  a plain-atom side counter equals n

  Deliberately text/counter-based: those observations mean the same
  thing in a DOM and in a snapshot. Assertions on attributes, portals
  (mounted outside the root in the browser, inline markers on the
  JVM) and error boundaries are per-platform territory."
  (:require [solidclj.api :as s]))

(defn fixtures []
  [;; The one rule of solidclj: deref in the component body is a
   ;; snapshot, deref in a thunk is live. A simulator that got the
   ;; tracking boundary wrong dies here.
   {:id :thunk-boundaries
    :make (fn []
            (let [a (s/atom 1)]
              {:hiccup  [:div "static:" @a "|live:" (fn [] @a)]
               :actions {:bump #(swap! a inc)}}))
    :script [[:text "static:1|live:1"]
             [:act :bump]
             [:text "static:1|live:2"]
             [:act :bump]
             [:text "static:1|live:3"]]}

   ;; Un-deref'd satom in a child slot is auto-bridged and live.
   {:id :satom-auto-bridge
    :make (fn []
            (let [a (s/atom "x")]
              {:hiccup  [:p "v=" a]
               :actions {:set-y #(reset! a "y")}}))
    :script [[:text "v=x"]
             [:act :set-y]
             [:text "v=y"]]}

   ;; Component bodies run ONCE — reactive updates must not re-run them.
   {:id :component-runs-once
    :make (fn []
            (let [n    (s/atom 0)
                  runs (atom 0)
                  comp (fn []
                         (swap! runs inc)
                         [:div "n=" (fn [] @n)])]
              {:hiccup   [comp]
               :counters {:runs runs}
               :actions  {:bump #(swap! n inc)}}))
    :script [[:text "n=0"] [:runs :runs 1]
             [:act :bump]
             [:text "n=1"] [:runs :runs 1]]}

   ;; Show + JS truthiness: 0 and "" must be falsy on both platforms.
   {:id :show-truthiness
    :make (fn []
            (let [n (s/atom 0)]
              {:hiccup  [:div [:show {:when n :fallback "none"}
                               "count:" (fn [] @n)]]
               :actions {:three     #(reset! n 3)
                         :zero      #(reset! n 0)
                         :empty-str #(reset! n "")}}))
    :script [[:text "none"]
             [:act :three]     [:text "count:3"]
             [:act :zero]      [:text "none"]
             [:act :three]     [:text "count:3"]
             [:act :empty-str] [:text "none"]]}

   ;; Switch evaluates matches in order, falls through to :fallback.
   {:id :switch-order
    :make (fn []
            (let [x (s/atom 1)]
              {:hiccup  [:div [:switch {:fallback "other"}
                               [:match {:when (fn [] (= 1 @x))} "one"]
                               [:match {:when (fn [] (= 2 @x))} "two"]]]
               :actions {:two  #(reset! x 2)
                         :nine #(reset! x 9)
                         :one  #(reset! x 1)}}))
    :script [[:text "one"]
             [:act :two]  [:text "two"]
             [:act :nine] [:text "other"]
             [:act :one]  [:text "one"]]}

   ;; For keyed by identity: reorders reuse rows (render-fn does NOT
   ;; re-run), index accessors update, fresh identities render once.
   {:id :for-keyed-reorder
    :make (fn []
            (let [ia      {:k "a"}
                  ib      {:k "b"}
                  ic      {:k "c"}
                  items   (s/atom [ia ib])
                  renders (atom 0)]
              {:hiccup   [:ul [:for {:each items}
                               (fn [item index]
                                 (swap! renders inc)
                                 [:li (fn [] (index)) (:k item)])]]
               :counters {:renders renders}
               :actions  {:reorder    #(reset! items [ib ia])
                          :drop-first #(swap! items (comp vec rest))
                          :add-c      #(swap! items conj ic)}}))
    :script [[:text "0a1b"] [:runs :renders 2]
             [:act :reorder]
             [:text "0b1a"] [:runs :renders 2]
             [:act :drop-first]
             [:text "0a"]   [:runs :renders 2]
             [:act :add-c]
             [:text "0a1c"] [:runs :renders 3]]}

   ;; Index keyed by position: in-place mutations update item signals
   ;; without re-rendering; the list grows/shrinks at the tail.
   {:id :index-in-place
    :make (fn []
            (let [items   (s/atom ["a" "b"])
                  renders (atom 0)]
              {:hiccup   [:ol [:index {:each items}
                               (fn [item-getter i]
                                 (swap! renders inc)
                                 [:li i ":" (fn [] (item-getter))])]]
               :counters {:renders renders}
               :actions  {:mutate #(reset! items ["a2" "b2"])
                          :grow   #(reset! items ["a2" "b2" "c"])
                          :shrink #(reset! items ["a2"])}}))
    :script [[:text "0:a1:b"]      [:runs :renders 2]
             [:act :mutate]
             [:text "0:a21:b2"]    [:runs :renders 2]
             [:act :grow]
             [:text "0:a21:b22:c"] [:runs :renders 3]
             [:act :shrink]
             [:text "0:a2"]]}

   ;; on-cleanup fires when a Show hides its subtree, and again after
   ;; a re-show/re-hide round (children are re-created per toggle).
   {:id :cleanup-on-hide
    :make (fn []
            (let [on?     (s/atom true)
                  cleaned (atom 0)]
              {:hiccup   [:div [:show {:when on?}
                                (fn []
                                  (s/on-cleanup (fn [] (swap! cleaned inc)))
                                  "alive")]]
               :counters {:cleaned cleaned}
               :actions  {:hide #(reset! on? false)
                          :show #(reset! on? true)}}))
    :script [[:text "alive"] [:runs :cleaned 0]
             [:act :hide]
             [:text ""]      [:runs :cleaned 1]
             [:act :show]
             [:text "alive"] [:runs :cleaned 1]
             [:act :hide]
             [:text ""]      [:runs :cleaned 2]]}

   ;; Fragments and (for …) seqs flatten into the parent.
   {:id :fragment-flattening
    :make (fn []
            {:hiccup [:ul [:<> (for [i (range 3)] ^{:key i} [:li "i" i])]]})
    :script [[:text "i0i1i2"]]}

   ;; A conditional thunk swaps whole subtrees.
   {:id :conditional-subtree
    :make (fn []
            (let [flag (s/atom true)]
              {:hiccup  [:div (fn [] (if @flag [:b "yes"] [:i "no"]))]
               :actions {:flip #(swap! flag not)}}))
    :script [[:text "yes"]
             [:act :flip] [:text "no"]
             [:act :flip] [:text "yes"]]}])
