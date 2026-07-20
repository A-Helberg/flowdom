(ns frontend.flash
  "Chrome-inspector-style paint flashing: any element whose attributes,
  text, or children just changed gets a brief blue outline.

  A MutationObserver watches the whole page. Flashing works by toggling
  between two identical CSS animations (`flash-a` / `flash-b`, see
  tailwind.css) — swapping the class restarts the animation for
  back-to-back changes without reflow tricks or timers. Mutations caused
  by the flash classes themselves are filtered out, so the observer
  never feeds back into itself."
  (:require [flowdom.rx :refer [rx ?]]))

(defonce enabled? (atom true))

(def ^:private flash-tokens #{"flash-a" "flash-b"})

(defn- flash! [el]
  (when-let [cl (and el (.-classList el))]
    (if (.contains cl "flash-a")
      (do (.remove cl "flash-a") (.add cl "flash-b"))
      (do (.remove cl "flash-b") (.add cl "flash-a")))))

(defn- without-flash-tokens [class-str]
  (->> (.split (or class-str "") #"\s+")
       (remove #(or (= % "") (flash-tokens %)))
       sort))

(defn- flash-toggle-only?
  "True when an attribute mutation is just us adding/removing the flash
  classes — the class list is otherwise unchanged."
  [m]
  (and (= "class" (.-attributeName m))
       (= (without-flash-tokens (.-oldValue m))
          (without-flash-tokens (.getAttribute (.-target m) "class")))))

(defn- element? [n]
  (= 1 (.-nodeType n)))

(defn- on-mutations [records _obs]
  (when @enabled?
    (let [els    (js/Set.)
          gained (js/Set.)] ;; containers that had nodes added this batch
      (doseq [m (array-seq records)]
        (case (.-type m)
          "characterData"
          (some->> (.. m -target -parentElement) (.add els))

          "attributes"
          (when-not (flash-toggle-only? m)
            (.add els (.-target m)))

          "childList"
          (when (pos? (.. m -addedNodes -length))
            (.add gained (.-target m))
            (doseq [n (array-seq (.-addedNodes m))]
              (if (element? n)
                (.add els n)
                (some->> (.-parentElement n) (.add els)))))

          nil))
      ;; Removals: when a child was swapped (removed + added, possibly in
      ;; separate records of the same batch), the inserted node already
      ;; carries the flash — blaming the container would wrongly outline
      ;; every sibling too. Flash the container only for PURE removals,
      ;; where the vacated spot is all there is to point at.
      (doseq [m (array-seq records)]
        (when (and (= "childList" (.-type m))
                   (pos? (.. m -removedNodes -length))
                   (not (.has gained (.-target m))))
          (.add els (.-target m))))
      (.forEach els (fn [el _ _] (flash! el))))))

(defn install!
  "Start observing `root` (default: document.body). Returns the observer."
  ([] (install! js/document.body))
  ([root]
   (let [obs (js/MutationObserver. on-mutations)]
     (.observe obs root
               #js {:subtree           true
                    :childList         true
                    :attributes        true
                    :attributeOldValue true
                    :characterData     true})
     obs)))

(defn toggle
  "Sidebar checkbox component for toggling flash on/off."
  []
  [:div {:class "mt-8 pt-4 border-t border-gray-200"}
   [:label {:class "flex items-center gap-2 text-sm text-gray-600 cursor-pointer select-none"}
    [:input {:type      "checkbox"
             :checked   (rx (? enabled?))
             :on-change #(swap! enabled? not)}]
    "Flash DOM updates"]])
