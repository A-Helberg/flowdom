(ns flowdom.guide-test
  "End-to-end: mount the whole guide under happy-dom and click through
  every ported section — every example mounts, every source block
  renders, teardown empties the container."
  (:require ["happy-dom" :refer [Window]]
            [cljs.test :refer [deftest is]]
            [flowdom.dom :as dom]
            [frontend.app :as app]
            [frontend.pages :as pages]))

(deftest whole-guide-mounts-and-navigates
  (let [win (Window. #js {:url "http://localhost/"})
        doc (.-document win)
        el  (.createElement doc "div")]
    (set! js/location (.-location win))
    (set! js/window win)
    (.appendChild (.-body doc) el)
    (let [pages   (mapcat :pages pages/sections)
          dispose (dom/mount [app/app] el)]
      ;; sidebar lists every page
      (is (= (count pages)
             (.-length (.querySelectorAll el "aside li button"))))
      ;; home shows by default
      (is (= "Why flowdom?" (.-textContent (.querySelector el "main h1"))))
      ;; visit every page: title renders, examples and sources mount
      (doseq [[i page] (map-indexed vector pages)]
        (.click (aget (.querySelectorAll el "aside li button") i))
        (is (= (:title page)
               (.-textContent (.querySelector el "main h1")))
            (str "page " (:id page)))
        (when (seq (:examples page))
          (is (= (count (:examples page))
                 (.-length (.querySelectorAll el "main section pre code")))
              (str "example sources for " (:id page)))))
      (dispose)
      (is (= "" (.-textContent el))))))
