(ns frontend.flash-test
  "Drives flowdom.docs.flash's mutation handler with hand-built records —
  regression tests for what gets blamed for a change."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [frontend.test-setup]
            [frontend.flash :as flash]))

(defn- el [tag]
  (.createElement js/document tag))

(defn- flashed? [node]
  (or (.. node -classList (contains "flash-a"))
      (.. node -classList (contains "flash-b"))))

(defn- childlist-record [target added removed]
  #js {:type         "childList"
       :target       target
       :addedNodes   (to-array added)
       :removedNodes (to-array removed)})

(deftest swapping-a-child-flashes-the-new-node-not-the-container
  ;; what a reactive thunk does when it returns a fresh element:
  ;; the old <p> goes out, a new <p> comes in, siblings are untouched
  (let [container (el "div")
        old-p     (el "p")
        new-p     (el "p")
        button    (el "button")]
    (.append container new-p button)
    (#'flash/on-mutations
     #js [(childlist-record container [] [old-p])
          (childlist-record container [new-p] [])]
     nil)
    (is (flashed? new-p) "the inserted element flashes")
    (is (not (flashed? container)) "the container is not blamed for a swap")
    (is (not (flashed? button)) "siblings don't flash")))

(deftest pure-removal-flashes-the-container
  (let [container (el "div")
        old-p     (el "p")]
    (#'flash/on-mutations
     #js [(childlist-record container [] [old-p])]
     nil)
    (is (flashed? container)
        "with nothing inserted, the vacated container is all there is to show")))

(deftest text-change-flashes-the-enclosing-element
  (let [p    (el "p")
        text (.createTextNode js/document "hi")]
    (.append p text)
    (#'flash/on-mutations
     #js [#js {:type "characterData" :target text}]
     nil)
    (is (flashed? p))))

(deftest own-class-toggles-are-ignored
  (let [d (el "div")]
    (.setAttribute d "class" "foo flash-a")
    (#'flash/on-mutations
     #js [#js {:type          "attributes"
               :attributeName "class"
               :oldValue      "foo flash-b"
               :target        d}]
     nil)
    ;; flash! would have toggled flash-a → flash-b; ignoring the record
    ;; means the class list is untouched
    (is (.. d -classList (contains "flash-a"))
        "flash-class churn must not re-flash (feedback loop)")
    (is (not (.. d -classList (contains "flash-b"))))))
