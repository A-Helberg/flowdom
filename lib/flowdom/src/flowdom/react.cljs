(ns flowdom.react
  "Bridge for rendering React components inside a flowdom tree.

  Usage — React (leaf component)
  --------------------------------
      [react/component DatePicker {:value date-atom :onChange handler}]

  Usage — React (component with children)
  ----------------------------------------
  Use `react/el` to build the React sub-tree, then pass children as extra
  positional args to `react/component`:

      [react/component LineChart {:width 600 :height 300 :data data-atom}
       (react/el XAxis   {:dataKey \"name\"})
       (react/el YAxis   {})
       (react/el Tooltip {})
       (react/el Line    {:dataKey \"uv\" :stroke \"#2563eb\"})]

  `react/el` mirrors `React.createElement` but accepts a CLJS props map.
  Children passed to `react/el` are React elements (returned by other
  `react/el` calls), not hiccup.

  Props
  -----
  The props map follows the same rule as everywhere else in flowdom:
    - rx values and atoms are READ — the bridge wraps the whole props
      map in one rx, and every change re-renders the root
      (deduplicated with =).
    - Functions pass through as-is (event handlers, render props, …).
    - Everything else is a static value, captured at mount.

  Props passed to `react/el` are resolved once at call time (no
  reactivity — el produces a static React element description).

  Lifecycle
  ---------
  The bridge is ordinary hiccup: a host <div> whose :on-mount creates
  the React root and subscribes the props rx. Its teardown — run when
  the region unmounts — cancels the subscription and unmounts the
  root."
  (:require [flowdom.rx :as rx]
            [missionary.core :as m]
            ["react" :refer [createElement]]
            ["react-dom/client" :refer [createRoot]]))

(defn- reactive? [x]
  (or (rx/rx? x)
      (satisfies? IWatchable x)))

(defn- resolve-props
  "Runs inside the bridge's rx: read reactive prop values (rx, atom),
  pass everything else — including handler fns — through."
  [props]
  (reduce-kv (fn [m k v] (assoc m k (if (reactive? v) (rx/? v) v)))
             {} props))

(defn el
  "Creates a React element for use as a child of `component`.
  Props is a CLJS map (converted to JS); children are React elements.

      (react/el Line {:dataKey \"uv\" :stroke \"#2563eb\"})"
  [js-comp props & children]
  (apply createElement js-comp (clj->js props) children))

(defn mount-bridge
  "Shared mounting logic. `make-root` constructs the React/Reagent root
  from the host div; `make-element` receives resolved props per change
  and its result is root.render()'ed. Children are captured in
  make-element's closure and remain stable across re-renders. Returns
  flowdom hiccup."
  [props make-root make-element]
  (let [props< (rx/rx* #(resolve-props props))]
    [:div {:on-mount
           (fn [host]
             (let [root    (make-root host)
                   ;; failures after cancellation are teardown noise,
                   ;; not errors — the stopped flag swallows them
                   stopped (volatile! false)
                   render  (fn [_ p]
                             (cond
                               (rx/pending-value? p) nil
                               (rx/err? p) (js/console.error
                                            "flowdom.react: prop error" (:error p))
                               :else (.render ^js root (make-element p)))
                             nil)
                   cancel  ((m/reduce render nil props<)
                            (fn [_] nil)
                            (fn [e] (when-not @stopped
                                      (js/console.error
                                       "flowdom.react: props flow failed" e))))]
               (fn []
                 (vreset! stopped true)
                 (cancel)
                 (.unmount ^js root))))}]))

(defn component
  "Renders a React component into the flowdom tree. Children (if any)
  should be React elements produced by `react/el`.

      ;; No children
      [react/component MyComp {:value counter-atom}]

      ;; With children
      [react/component LineChart {:data data-atom :width 600 :height 300}
       (react/el XAxis {:dataKey \"name\"})
       (react/el Line  {:dataKey \"uv\" :stroke \"#2563eb\"})]"
  [js-comp props & children]
  (mount-bridge props
                createRoot
                (fn [resolved]
                  (apply createElement js-comp (clj->js resolved) children))))
