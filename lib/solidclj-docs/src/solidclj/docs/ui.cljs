(ns solidclj.docs.ui
  "Small styled building blocks so guide examples can focus on the
  concept being taught instead of on Tailwind classes."
  (:require ["highlight.js/lib/core" :as hljs-module]
            ["highlight.js/lib/languages/clojure" :as clojure-lang-module]))

;; ESM (browser bundle) exposes these under .default; node's require()
;; returns them directly — unwrap whichever shape we got.
(def ^:private hljs         (or (.-default hljs-module) hljs-module))
(def ^:private clojure-lang (or (.-default clojure-lang-module) clojure-lang-module))

(defonce ^:private register-clojure!
  (.registerLanguage hljs "clojure" clojure-lang))

(defn- highlight
  "Clojure source → highlight.js HTML markup (entities pre-escaped)."
  [source]
  (.-value (.highlight hljs source #js {:language "clojure"})))

(defn button
  "[ui/button {:on-click f} \"label\"] — a small primary button."
  [{:keys [on-click]} & children]
  [:button {:class   "px-3 py-1.5 rounded-md bg-blue-600 text-white text-sm font-medium hover:bg-blue-500 active:bg-blue-700 cursor-pointer select-none"
            :onClick on-click}
   children])

(defn input
  "[ui/input {:value a :on-input f :placeholder s :ref f :name s :type s}]
  — a text input. `:value` may be an atom (live) or a plain value;
  `:name`/`:type` pass through for uncontrolled forms."
  [{:keys [value on-input placeholder ref name type]}]
  [:input (cond-> {:class "border border-gray-300 rounded-md px-2 py-1 text-sm w-56 focus:outline-2 focus:outline-blue-500"}
            (some? value)       (assoc :value value)
            (some? on-input)    (assoc :onInput on-input)
            (some? placeholder) (assoc :placeholder placeholder)
            (some? ref)         (assoc :ref ref)
            (some? name)        (assoc :name name)
            (some? type)        (assoc :type type))])

(defn code-block
  "A dark code panel showing `source` with Clojure syntax highlighting."
  [source]
  [:pre {:class "m-0 p-4 bg-gray-950 text-gray-100 text-xs leading-relaxed overflow-x-auto"}
   [:code {:class     "hljs language-clojure"
           :innerHTML (highlight source)}]])

(defn example-block
  "Code on the left, live result on the right (stacked on small screens).

     [ui/example-block {:title     \"Counter\"
                        :source    (rc/inline \"frontend/examples/counter.cljs\")
                        :component counter/example}]"
  [{:keys [title source component]}]
  [:section {:class "mt-8"}
   (when title
     [:h2 {:class "text-lg font-semibold mb-3 text-gray-900"} title])
   [:div {:class "grid grid-cols-1 xl:grid-cols-2 border border-gray-200 rounded-lg overflow-hidden shadow-sm"}
    [:div {:class "bg-gray-950 min-w-0"}
     [code-block source]]
    [:div {:class "p-5 bg-white min-w-0 border-t border-gray-200 xl:border-t-0 xl:border-l"}
     [:div {:class "text-[11px] font-semibold uppercase tracking-wider text-gray-400 mb-3"} "result"]
     [component]]]])
