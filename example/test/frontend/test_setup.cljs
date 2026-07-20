(ns frontend.test-setup
  "Installs happy-dom globals (document, MutationObserver, rAF, …) for
  tests that touch them at load time — e.g. frontend.flash's observer."
  (:require ["happy-dom" :refer [Window]]))

(defonce ^:private window
  (let [w   (Window.)
        doc (.-document w)]
    (set! js/window           w)
    (set! js/document         doc)
    (set! js/location         (.-location w))
    (set! js/HTMLDocument     (.-HTMLDocument w))
    (set! js/HTMLElement      (.-HTMLElement w))
    (set! js/HTMLHeadElement  (.-HTMLHeadElement w))
    (set! js/SVGElement       (.-SVGElement w))
    (set! js/Element          (.-Element w))
    (set! js/Node             (.-Node w))
    (set! js/Text             (.-Text w))
    (set! js/Comment          (.-Comment w))
    (set! js/Event            (.-Event w))
    (set! js/CustomEvent      (.-CustomEvent w))
    (set! js/DocumentFragment (.-DocumentFragment w))
    (set! js/MutationObserver (.-MutationObserver w))
    ;; recharts measures its container via ResizeObserver; stub it if
    ;; happy-dom doesn't provide one.
    (when-not (exists? js/ResizeObserver)
      (set! js/ResizeObserver
            (if-let [ro (.-ResizeObserver w)]
              ro
              (fn [_cb]
                (this-as this
                  (set! (.-observe this) (fn [_el]))
                  (set! (.-unobserve this) (fn [_el]))
                  (set! (.-disconnect this) (fn []))
                  this)))))
    (if-let [raf (.-requestAnimationFrame w)]
      (set! js/requestAnimationFrame raf)
      (set! js/requestAnimationFrame (fn [cb] (js/setTimeout cb 16))))
    (if-let [caf (.-cancelAnimationFrame w)]
      (set! js/cancelAnimationFrame caf)
      (set! js/cancelAnimationFrame js/clearTimeout))
    w))

(defn fresh-root
  "Returns a fresh detached <div> to mount into."
  []
  (.createElement js/document "div"))
