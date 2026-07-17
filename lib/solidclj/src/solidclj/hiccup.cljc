(ns solidclj.hiccup
  "Reagent-style runtime hiccup → SolidJS DOM.

  Components are plain CLJS functions that return hiccup vectors. The
  walker `as-element` converts that data into SolidJS `h` calls. There
  is no compile-time magic — what you write is what runs.

  Reagent vs SolidJS — the one rule the user has to remember
  ==========================================================
  In Reagent, the component function itself re-runs on state changes,
  and React diffs the new hiccup against the old. SolidJS does NOT
  re-run the component on signal changes; you have to mark the dynamic
  region of the tree by wrapping it in a `(fn [])` so SolidJS knows
  what to re-evaluate when a signal it read inside that fn changes:

      [:div {:class \"row\"}
       (fn []
         (if (time)
           [:span {:style \"color: blue\"} (time)]
           [:span {:style \"color: gray\"} \"connecting…\"]))]

  Anywhere you see a function in a child position, the walker will
  treat it as a SolidJS reactive thunk and walk whatever hiccup it
  returns each time SolidJS calls it.

  Reactive atoms and derefs
  =========================
  `solidclj.satom/atom` (exported as `solidclj.api/atom`) is a real
  atom whose deref also subscribes the current Solid tracking scope.
  Reactivity comes from WHERE the deref runs — and only from a deref:

      (def time (s/atom nil))

      [:div (fn [] @time)]  ;; live — deref runs inside a thunk
      [:div @time]          ;; static — snapshot; the deref ran in the
                            ;;          component body (untracked)
      [:div time]           ;; mistake — refs are not interpreted;
                            ;;           renders \"#<SAtom: …>\" and
                            ;;           warns in dev builds

  The walker never interprets refs. A bare atom — s/atom or plain
  `cljs.core/atom` alike — is just a value: in a child slot it renders
  as its printed representation (with a dev-build warning) so the
  mistake is visible, and anywhere else it passes through untouched.
  The `h` macro (`solidclj.hiccup-macros`) writes the thunks for you,
  so `(h [:div @time])` is live with no `(fn [])` in sight. See
  `docs/atoms-and-reactivity.md`.

  Same rule for prop values: `[:input {:value (fn [] @text)}]` is
  reactive (`{:value @text}` under `h` compiles to exactly that),
  `[:input {:value @text}]` outside `h` is a one-shot snapshot, and
  `[:input {:value text}]` passes the atom object itself — a bug you
  can see.

  Refs
  ====
  Solid uses ref callbacks. Pass `:ref` a function and Solid will call
  it with the underlying DOM element once it's been created:

      (defonce my-input (atom nil))

      [:input {:ref #(reset! my-input %)}]

  `:ref` accepts a function only. If you want an atom to track the
  element, wrap it as shown above.

  JS Solid components
  ===================
  To call a component imported from a JS Solid library (or any of
  Solid's own components like `<Show>`, `<Switch>`, `<Suspense>`,
  `<ErrorBoundary>`, …) use `[:>]`:

      [:> SomeJsComp {:foo 1 :onClick handler}
       [:span \"a child\"]]

  The component is invoked via SolidJS's `h`, props are converted to a
  JS object, and children pass through as varargs (Solid bundles them
  into `props.children`).

  Keyed lists with [:for]
  =======================
  Solid's `<For>` is exposed as a hiccup form:

      (def items (s/atom [{:id 1 :name \"a\"} {:id 2 :name \"b\"}]))

      [:for {:each (fn [] @items)}
       (fn [item index]
         [:li (:name item) \" — \" (index)])]

  `:each` may be a static collection or a Solid signal getter (any
  zero-arg fn, e.g. `(fn [] @items)`). The render fn receives
  `(item index)` per
  Solid's API; `index` is a Solid Accessor (a getter), so call it as
  `(index)` to read the current position. Whatever hiccup the render
  fn returns is walked.

  `<For>` keys by referential identity of items. `swap!`/`update`s
  that reuse existing item identities are diffed in place;
  `(reset! items new-coll)` with freshly-built items will rebuild the
  whole list. Provide stable identities (e.g. don't rebuild every map
  on every change) for the most efficient updates.

  Quick reference
  ---------------
    [:div {…} child …]              HTML element
    [:div.cls.cls2#id {…} …]        HTML with class/id shorthand
    [:> JsComp {…} child …]         JS Solid component (props → JS obj)
    [:<> child …]                   Fragment (siblings, no wrapper)
    [:for {:each xs} render-fn]     <For>; render-fn is (item index-getter)
    [:index {:each xs} render-fn]   <Index>; render-fn is (item-getter index)
    [:show {:when e :fallback fb} …]    <Show>
    [:switch {:fallback fb}             <Switch> + <Match>
      [:match {:when e} …] …]
    [:dynamic {:component c …} …]   <Dynamic>; :component is value or accessor fn
    [:portal {:mount el} …]         <Portal>
    [:suspense {:fallback fb} …]    <Suspense>
    [:error-boundary {:fallback     <ErrorBoundary>;
      (fn [err reset] hiccup)} …]    fallback receives (err reset)
    [my-component arg …]            CLJS component invocation
    (fn [] hiccup)                  Reactive thunk
    (fn [] \\@some-satom)           Live — deref subscribes the thunk
    \\@some-satom                   One-shot snapshot (outside thunks)
    some-satom                      Not interpreted — renders its printed
                                    form (dev builds warn)
    (for [x xs] [:li x])            Sequence flattened (no keying;
                                    add ^{:key id} on each item)
    {:ref (fn [el] …)}              DOM ref callback (fn only)"
  (:require [clojure.string]
            [solidclj.satom   :as satom]
            [solidclj.runtime :as rt]
            ;; Browser/SSR-only features — deliberately NOT part of the
            ;; solidclj.runtime seam; the JVM runtime does not offer them.
            #?(:cljs ["solid-js/web" :as solid-web :refer [renderToString hydrate isServer ssrElement]])))

(declare as-element)

(defn- watchable?
  "A watchable deref-able — s/atoms and plain atoms alike. The walker
  never interprets refs; this predicate exists only to make placing
  one bare in the tree loud (see `warn-bare-atom!`)."
  [x]
  (and #?(:cljs (satisfies? IDeref x)
          :clj  (instance? clojure.lang.IDeref x))
       #?(:cljs (satisfies? IWatchable x)
          :clj  (instance? clojure.lang.IRef x))))

(defn- warn-bare-atom! [where]
  (when rt/debug?
    (rt/warn!
     (str "[solidclj.hiccup] bare atom in " where " — the renderer does "
          "not interpret refs. Deref it inside a thunk for a live view "
          "((fn [] @a), or let the h macro write the thunk), or deref "
          "it in the component body for a snapshot."))))

(defn- parse-tag
  "Parses a hiccup keyword like :div, :div.foo, :div#main, :div.a.b#c
  into [base-tag classes id], where:
    - base-tag is a string (defaults to \"div\" if the keyword starts
      with \".\" or \"#\");
    - classes is a vector of class-name strings (may be empty);
    - id is a string or nil.

  Format rule: classes (.x.y) must come before the id (#z). Anything
  else is undefined."
  [tag]
  (let [s         (name tag)
        hash-idx  (clojure.string/index-of s "#")
        [base-classes id] (if (nil? hash-idx)
                            [s nil]
                            [(subs s 0 hash-idx)
                             (let [raw (subs s (inc hash-idx))]
                               (when-not (= "" raw) raw))])
        parts     (clojure.string/split base-classes #"\.")
        base      (let [b (first parts)]
                    (if (or (nil? b) (= b "")) "div" b))
        classes   (->> (rest parts)
                       (remove empty?)
                       vec)]
    [base classes id]))

(defn- kebab->camel
  "Converts \"background-color\" → \"backgroundColor\". Leaves strings
  without dashes alone. CSS custom properties (strings starting with
  \"--\") are preserved verbatim so they reach Solid's style prop in
  a shape it can actually apply."
  [s]
  (cond
    (clojure.string/starts-with? s "--")
    s

    :else
    (let [parts (clojure.string/split s #"-")]
      (if (= 1 (count parts))
        s
        (str (first parts)
             (apply str
                    (map (fn [p]
                           (if (= "" p)
                             ""
                             (str (clojure.string/upper-case (subs p 0 1))
                                  (subs p 1))))
                         (rest parts))))))))

(defn- style-key->css [k]
  (cond
    (keyword? k) (kebab->camel (name k))
    (string? k)  (kebab->camel k)
    :else        (kebab->camel (str k))))

(defn- normalize-style
  "Converts a :style value to something Solid's `style` prop accepts:
   - nil           → nil
   - string        → string (CSS text passes through)
   - map           → props object (via rt/->props) with camelCase keys;
                     use accessor fns for per-property reactivity
   - else          → returned unchanged (Solid will handle accessors)"
  [v]
  (cond
    (nil? v)       nil
    (string? v)    v
    (map? v)       (rt/->props
                    (reduce-kv
                     (fn [m k val]
                       (when (watchable? val)
                         (warn-bare-atom! (str "style value " k)))
                       (assoc m (style-key->css k) val))
                     {} v))
    :else          (do (when (watchable? v)
                         (warn-bare-atom! ":style"))
                       v)))

(defn- class-coll->str
  "Joins a collection of class names into a single space-separated
  string. Drops nil, false, AND true.

  Dropping true is deliberate: the common pattern
  `(when condition \"foo\")` yields `\"foo\"` or `nil` — but
  variations like `(and condition \"foo\")` yield `\"foo\"` or
  `false`/`true` depending on the predicate. Treating booleans as
  no-ops makes either pattern work, and matches the boolean-toggle
  semantics of the map form of :class.

  Keywords are unwrapped via `name`, empty strings filtered."
  [coll]
  (->> coll
       (keep (fn [c]
               (cond
                 (or (nil? c) (false? c) (true? c)) nil
                 (keyword? c)                       (name c)
                 (= "" c)                           nil
                 :else                              (str c))))
       (clojure.string/join " ")))

(defn- normalize-class
  "Returns [class-string-or-fn class-list-js-object-or-nil].

  Shapes accepted:
   - nil            → [nil nil]
   - string         → [str nil]
   - keyword        → [(name kw) nil]
   - vector / seq   → [(joined string) nil]
   - map            → [nil props object (via rt/->props) suitable for
                       Solid's :classList]
   - fn             → [fn nil] (assumed to be a Solid accessor)
   - other          → [(str v) nil]"
  [v]
  (cond
    (nil? v)         [nil nil]
    (string? v)      [v nil]
    (keyword? v)     [(name v) nil]
    (map? v)         [nil (rt/->props
                           (reduce-kv
                            (fn [m k val]
                              (when (watchable? val)
                                (warn-bare-atom! (str ":class map value " k)))
                              (assoc m (if (keyword? k) (name k) (str k))
                                     val))
                            {} v))]
    (fn? v)          [v nil]
    (sequential? v)  [(class-coll->str v) nil]
    (watchable? v)   (do (warn-bare-atom! ":class")
                         [(pr-str v) nil])
    :else            [(str v) nil]))

(defn atom->signal-getter
  "Mirror an atom's value through a Solid signal. Returns the getter,
  which subscribes in any Solid tracking scope that calls it. The
  watcher is removed via `onCleanup` of the current owner.

  The watch is registered BEFORE the initial read, so no update can
  slip between snapshot and subscription — and lazily-started refs
  whose add-watch begins their work (solidclj.missionary holds) are
  already running when first read.

  This is the EXPLICIT bridge behind `solidclj.api/?` — the hiccup
  walker itself never interprets refs.

  If called outside a Solid owner (no render / createRoot / component
  body on the stack), we cannot register cleanup, so we fall back to
  a one-shot snapshot getter and warn in dev."
  [a]
  (if (some? (rt/get-owner))
    (let [[get-v set-v] (rt/create-signal nil)
          k             (gensym "solidclj.hiccup/atom-watch-")]
      (add-watch a k (fn [_k _ref _old new] (set-v (fn [_] new))))
      (rt/on-cleanup (fn [] (remove-watch a k)))
      (set-v (fn [_] @a))
      get-v)
    (let [v @a]
      (when rt/debug?
        (rt/warn!
         (str "[solidclj.hiccup] atom bridged outside a reactive owner; "
              "taking a one-shot snapshot. Wrap in render/createRoot/"
              "component body for live updates.")))
      (fn [] v))))

(defn- merge-classes
  "Combines shorthand classes (vector of strings from parse-tag) with
  the explicit :class value's normalized form. Shorthand is prepended.
  Returns [class-string-or-fn class-list-or-nil]."
  [shorthand-classes explicit-class]
  (let [[cls cls-list] (normalize-class explicit-class)
        prefix         (class-coll->str shorthand-classes)]
    [(cond
       (and (seq prefix) (fn? cls))
       (fn []
         (let [s (cls)]
           (if (or (nil? s) (= "" s))
             prefix
             (str prefix " " s))))

       (and (seq prefix) (seq cls))
       (str prefix " " cls)

       (seq prefix)
       prefix

       :else
       cls)
     cls-list]))

(defn- normalize-props
  "Converts a hiccup props map to a JS object suitable for h.
   Applies:
    - :class string / vector / map (map → classList for reactivity)
    - :style string / map (kebab→camel, reactive values)
    - :id explicit value overrides shorthand-id
    - shorthand classes prepended to :class
    - refs are NOT interpreted — a bare atom passes through as the
      object itself (dev builds warn); use an accessor fn for a live
      prop value, e.g. [:input {:value (fn [] @text)}]."
  [props shorthand-classes shorthand-id]
  (let [props              (or props {})
        [cls cls-list]     (merge-classes shorthand-classes (:class props))
        id                 (or (:id props) shorthand-id)
        style              (normalize-style (:style props))
        rest-props         (dissoc props :class :style :id)
        clj                (reduce-kv
                            (fn [m k v]
                              (when (watchable? v)
                                (warn-bare-atom! (str k " prop")))
                              (assoc m k v))
                            {} rest-props)
        clj                (cond-> clj
                             cls      (assoc :class cls)
                             cls-list (assoc :classList cls-list)
                             id       (assoc :id id)
                             style    (assoc :style style))]
    (rt/->props clj)))

(defn- renderable?
  "Reagent semantics: nil / true / false are dropped from child
  positions. Numbers (including 0), strings, vectors, fns all render
  (bare atoms render too — as their printed representation)."
  [v]
  (not (or (nil? v) (true? v) (false? v))))

(defn- walk-children
  "Walks each child through as-element and drops nil/true/false.
  Returns a sequence (caller may turn it into a JS array if needed)."
  [children]
  (->> children
       (filter renderable?)
       (map as-element)))

(defn- ->getter
  "Wraps a value so it reads as a Solid accessor (a zero-arg fn).
   - nil           → (fn [] nil)
   - fn            → returned as-is (already an accessor)
   - other value   → constant accessor returning that value
  Refs are not interpreted: a bare atom becomes a constant accessor
  returning the atom object (always truthy) and dev builds warn —
  pass (fn [] @a) for a live read."
  [v]
  (cond
    (nil? v) (fn [] nil)
    (fn? v)  v
    :else    (do (when (watchable? v)
                   (warn-bare-atom! "a :when slot"))
                 (fn [] v))))

(defn- ->renderable
  "Walks a hiccup-or-value into something Solid can render in a
  child-or-fallback slot. Mirrors `as-element`'s rules but is the
  explicit name used for control-flow tags so the intent reads."
  [v]
  (cond
    (nil? v)       nil
    (vector? v)    (as-element v)
    (watchable? v) (do (warn-bare-atom! "a fallback slot")
                       (pr-str v))
    (fn? v)        (fn [] (as-element (v)))
    :else          v))

(defn- as-vec
  "Walk a hiccup vector. The first element decides the shape:
   - `:>`        (`[:> JsComp {…} child …]`) — JS Solid component
   - `:for`      (`[:for {:each xs} render-fn]`) — Solid `<For>`
   - `:<>`       (`[:<> child …]`) — Fragment (no wrapper element)
   - `:show`     (`[:show {:when … :fallback …} child …]`) — Solid `<Show>`
   - `:switch`   (`[:switch {:fallback …} [:match {:when …} …] …]`) — Solid `<Switch>` / `<Match>`
   - `:dynamic`  (`[:dynamic {:component c} child …]`) — Solid `<Dynamic>`
   - `:portal`   (`[:portal {:mount el} child …]`) — Solid `<Portal>`
   - `:suspense` (`[:suspense {:fallback fb} child …]`) — Solid `<Suspense>`
   - `:error-boundary` (`[:error-boundary {:fallback (fn [err reset] hiccup)} child …]`) — Solid `<ErrorBoundary>`
   - `:index`    (`[:index {:each xs} render-fn]`) — Solid `<Index>` (position-keyed)
   - keyword     (`[:div {…} …]`) — HTML element
   - function    (`[my-comp arg …]`) — CLJS component (positional args).
     The fn is invoked once with the args captured at walk time. To
     re-run the component with new args, ensure the parent hiccup is
     re-walked (i.e. enclose the invocation in a reactive `(fn [])`
     so SolidJS knows when to re-evaluate)."
  [hv]
  (let [[tag & more] hv]
    (cond
      ;; ---- :> — JS Solid component ----------------------------------------
      ;; The second element of the vector is the JS component fn. Props are
      ;; converted to a JS object via `normalize-props` (with no shorthand
      ;; classes/id since JS components don't get keyword shorthand);
      ;; children pass through as varargs (SolidJS `h` bundles them into
      ;; `props.children`).
      (= tag :>)
      (let [js-comp (first more)
            _       (when rt/debug?
                      (when-not (fn? js-comp)
                        (throw (ex-info "solidclj.hiccup: [:>] expects a JS component fn as the second element"
                                        {:received js-comp :hiccup hv}))))
            rest*            (next more)
            [props children] (if (map? (first rest*))
                               [(first rest*) (next rest*)]
                               [nil rest*])]
        (apply rt/h js-comp (normalize-props props [] nil)
               (walk-children children)))

      ;; ---- :for — Solid <For> --------------------------------------------
      ;; `:each` may be a static collection or a Solid signal getter
      ;; (any zero-arg fn, e.g. (fn [] @items)). Refs are not
      ;; interpreted: a bare atom warns in dev and renders nothing.
      ;;
      ;; SolidJS's `mapArray` iterates `:each` using `arr.length` and
      ;; `arr[i]`, which CLJS persistent vectors don't expose. Anything
      ;; that isn't already a JS array gets `to-array`'d so the values
      ;; arrive in a shape Solid can iterate. Item identities (the
      ;; refs Solid keys on) are preserved.
      ;;
      ;; `render-fn` receives `(item index-getter)` per Solid's API; its
      ;; return value is walked.
      (= tag :for)
      (let [{:keys [each]} (first more)
            render-fn      (second more)
            each-prop      (cond
                             (fn? each)
                             (fn [] (rt/->each (each)))

                             (watchable? each)
                             (do (warn-bare-atom! ":each")
                                 (rt/->each nil))

                             :else
                             (rt/->each each))]
        (rt/h rt/For
              (rt/->props {:each each-prop})
              (fn [item index]
                ;; CLJS: evaluate h's deferred template HERE, inside the
                ;; per-row root — the same dance as [:index] below. h
                ;; defers building the element + wiring its reactive
                ;; subtree until the returned thunk is called; left
                ;; uncalled until Solid's insert unwraps it, the row's
                ;; inner effects (e.g. a thunk reading `(index)`) wire
                ;; under the LIST's insert effect instead of the row's
                ;; root. Observable symptom: `(index)` never updates on
                ;; reorder (rows move, stale indexes). Evaluating here
                ;; also means row effects dispose with the row.
                ;; JVM: no deferred templates — leave fn results for the
                ;; runtime to slot.
                (let [v (as-element (render-fn item index))]
                  #?(:cljs (if (fn? v) (v) v)
                     :clj  v)))))

      ;; ---- :<> — Fragment ------------------------------------------------
      ;; Render children as siblings with no wrapper element. Solid's
      ;; Fragment ignores props, so we walk children directly.
      (= tag :<>)
      (let [children (if (map? (first more)) (next more) more)]
        (apply rt/h rt/fragment nil (walk-children children)))

      ;; ---- :show — Solid <Show> -----------------------------------------
      ;; [:show {:when expr :fallback fb} & children]
      (= tag :show)
      (let [props    (first more)
            _        (when rt/debug?
                       (when-not (map? props)
                         (throw (ex-info "solidclj.hiccup: [:show] requires a {:when ... :fallback ...} props map as the second element"
                                         {:received props :hiccup hv}))))
            {:keys [when fallback]} props
            children (next more)]
        (apply rt/h rt/Show
               (rt/->props {:when     (->getter when)
                            :fallback (->renderable fallback)})
               (walk-children children)))

      ;; ---- :switch / :match — Solid <Switch> + <Match> -------------------
      ;; [:switch {:fallback fb}
      ;;   [:match {:when expr} & body]
      ;;   [:match {:when expr} & body]
      ;;   ...]
      ;;
      ;; :match is ONLY valid as a direct child of :switch. We walk the
      ;; match vectors here directly and emit <Match> components.
      (= tag :switch)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:switch] requires a {:fallback ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [fallback]} props
            cases              (next more)]
        (when rt/debug?
          (doseq [c cases]
            (when-not (and (vector? c) (= :match (first c)))
              (throw (ex-info "solidclj.hiccup: children of [:switch] must be [:match {:when ...} ...] vectors"
                              {:received c :hiccup hv})))))
        (apply rt/h rt/Switch
               (rt/->props {:fallback (->renderable fallback)})
               (map (fn [match-vec]
                      (let [[_ {:keys [when]} & body] match-vec]
                        (apply rt/h rt/Match
                               (rt/->props {:when (->getter when)})
                               (walk-children body))))
                    cases)))

      ;; ---- :match — only valid inside :switch ----------------------------
      ;; The :switch branch handles :match inline; reaching this branch
      ;; means :match was placed at top level or under some non-:switch
      ;; parent, which is never meaningful. Dev-gated to match the
      ;; rest of the structural checks (the plan's Key Conventions
      ;; locks this in as a dev-time error). In a release build, a stray
      ;; :match falls through to (keyword? tag) below and renders as an
      ;; (invalid but inert) <match> HTML element rather than crashing.
      (and rt/debug? (= tag :match))
      (throw (ex-info "solidclj.hiccup: [:match] is only valid as a direct child of [:switch]"
                      {:hiccup hv}))

      ;; ---- :dynamic — Solid <Dynamic> ------------------------------------
      ;; [:dynamic {:component c & extra-props} & children]
      ;;
      ;; :component accepts a value (an HTML tag string) or a
      ;; fn-accessor returning the current component — (fn [] @tag),
      ;; which is also what `h` compiles {:component @tag} into. A fn
      ;; is ALWAYS read as an accessor, so a static component fn must
      ;; be wrapped: {:component (fn [] my-comp)}. Other map keys flow
      ;; through normalize-props like a normal element.
      ;;
      ;; We can't put `component` on the plain props map because Solid's
      ;; <Dynamic> reads it inside a tracking memo, so we install a JS
      ;; getter that resolves the latest value on every read.
      (= tag :dynamic)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:dynamic] requires a {:component ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [component]} props
            children   (next more)
            comp-fn    (cond
                         (fn? component) component
                         :else           (do (when (watchable? component)
                                               (warn-bare-atom! ":component"))
                                             (fn [] component)))
            js-props   (-> (normalize-props (dissoc props :component) [] nil)
                           (rt/lazy-prop! :component comp-fn))]
        (apply rt/h rt/Dynamic js-props (walk-children children)))

      ;; ---- :portal — Solid <Portal> --------------------------------------
      ;; [:portal {:mount el :use-shadow? false :is-svg? false} & children]
      ;;
      ;; :mount is the DOM node children render into (outside the host
      ;; tree). It accepts a static DOM node or a fn-accessor returning
      ;; one. Like :dynamic's :component, we install it as a JS getter
      ;; so Solid reads the live value inside its tracking memo.
      (= tag :portal)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:portal] requires a {:mount ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [mount use-shadow? is-svg?]} props
            children   (next more)
            mount-fn   (cond
                         (fn? mount) mount
                         :else       (do (when (watchable? mount)
                                           (warn-bare-atom! ":mount"))
                                         (fn [] mount)))
            js-props   (-> (rt/->props
                            (cond-> {}
                              (some? use-shadow?) (assoc :useShadow use-shadow?)
                              (some? is-svg?)     (assoc :isSVG is-svg?)))
                           (rt/lazy-prop! :mount mount-fn))]
        (apply rt/h rt/Portal js-props (walk-children children)))

      ;; ---- :suspense — Solid <Suspense> ----------------------------------
      ;; [:suspense {:fallback fb} & children]
      ;;
      ;; Children render when async resources (createResource etc.) are
      ;; ready. :fallback renders while resources are pending.
      (= tag :suspense)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:suspense] requires a {:fallback ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [fallback]} props
            children (next more)]
        (apply rt/h rt/Suspense
               (rt/->props {:fallback (->renderable fallback)})
               (walk-children children)))

      ;; ---- :error-boundary — Solid <ErrorBoundary> -----------------------
      ;; [:error-boundary {:fallback (fn [err reset] hiccup)} & children]
      ;;
      ;; :fallback is normally a 2-arg fn that receives the thrown error
      ;; and a reset callback; its return value is walked through
      ;; as-element. For convenience we also accept a static hiccup value
      ;; (or atom holding one) — useful when the fallback doesn't need
      ;; access to the error/reset.
      (= tag :error-boundary)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:error-boundary] requires a {:fallback ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [fallback]} props
            children (next more)
            fb-fn    (cond
                       (fn? fallback)
                       (fn [err reset] (as-element (fallback err reset)))
                       :else
                       (->renderable fallback))]
        ;; CLJS: children walk lazily into h and Solid catches throws
        ;; from deferred component evaluation. JVM: walking is eager
        ;; (apply + chunked seqs realize children BEFORE h runs), so
        ;; the boundary receives a builder thunk instead and walks the
        ;; children inside its own try/catch.
        #?(:cljs (apply rt/h rt/ErrorBoundary
                        (rt/->props {:fallback fb-fn})
                        (walk-children children))
           :clj  (rt/h rt/ErrorBoundary
                       (rt/->props {:fallback fb-fn})
                       (fn [] (vec (walk-children children))))))

      ;; ---- :index — Solid <Index> ----------------------------------------
      ;; [:index {:each xs} render-fn]
      ;;
      ;; Like [:for] but keyed by POSITION rather than item identity.
      ;; The render-fn receives (item-getter, index-number) — the
      ;; opposite shape from [:for]'s (item, index-getter).
      ;;
      ;; Use :index when item identity isn't stable across updates
      ;; (e.g. text rows that mutate in place) and you want Solid to
      ;; reuse the existing DOM nodes per position; use :for when item
      ;; identity IS stable and you want positional reordering to be
      ;; cheap.
      (= tag :index)
      (let [props (first more)
            _     (when rt/debug?
                    (when-not (map? props)
                      (throw (ex-info "solidclj.hiccup: [:index] requires a {:each ...} props map as the second element"
                                      {:received props :hiccup hv}))))
            {:keys [each]} props
            render-fn      (second more)
            each-prop      (cond
                             (fn? each)
                             (fn [] (rt/->each (each)))

                             (watchable? each)
                             (do (warn-bare-atom! ":each")
                                 (rt/->each nil))

                             :else
                             (rt/->each each))]
        (rt/h rt/Index
              (rt/->props {:each each-prop})
              (fn [item-getter index]
                ;; CLJS: h sometimes returns a thunk that defers
                ;; building the actual element + wiring its reactive
                ;; subtree until it's called. <Index> calls our
                ;; render-fn once per position (with a stable
                ;; `item-getter`), so the per-position owner only
                ;; gets a chance to set up the inner render effects
                ;; (i.e. the (fn [] (item-getter)) child thunks) if
                ;; we evaluate the deferred template HERE — inside
                ;; the per-position scope. Skipping this leaves the
                ;; thunk uninvoked, items mutate but DOM doesn't
                ;; update. <For> doesn't need the same dance: it
                ;; calls render-fn again on every keyed update, so
                ;; the deferred-template path naturally re-runs.
                ;; JVM: no deferred templates — leave a fn result
                ;; as-is so the runtime slots it (calling it here
                ;; would freeze it into a static value instead).
                (let [v (as-element (render-fn item-getter index))]
                  #?(:cljs (if (fn? v) (v) v)
                     :clj  v)))))

      (keyword? tag)
      (let [[base shorthand-classes shorthand-id] (parse-tag tag)
            [props children] (if (map? (first more))
                               [(first more) (next more)]
                               [nil more])
            js-props (normalize-props props shorthand-classes shorthand-id)
            walked   (walk-children children)]
        #?(:cljs (if isServer
                   (ssrElement base js-props (into-array walked) true)
                   (apply rt/h base js-props walked))
           :clj  (apply rt/h base js-props walked)))

      (fn? tag)
      ;; Run the user component under SolidJS's component machinery so
      ;; we get a proper owner scope (cleanups, error boundaries, …).
      ;; The component fn is called once with positional args; whatever
      ;; hiccup it returns is walked into DOM.
      #?(:cljs (if isServer
                 (as-element (apply tag more))
                 (rt/h (fn [_props] (as-element (apply tag more))) nil))
         :clj  (rt/h (fn [_props] (as-element (apply tag more))) nil))

      :else
      (throw (ex-info "solidclj.hiccup: invalid hiccup tag"
                      {:tag tag :hiccup hv})))))

;; NOTE: warned-keyless* grows unbounded. That's acceptable because the
;; whole warning machinery is rt/debug?-gated and the set's only purpose
;; is to dedupe dev-time console output during a single session.
(defonce ^:private warned-keyless* (atom #{}))

(defn- warn-keyless-once!
  "Emits a console.warn once per equal first-vector identity. Dedup is
  by value equality so the same hiccup at the same call-site doesn't
  spam across renders. Fires when ANY vector in the seq lacks :key,
  matching React's stricter behaviour. Guarded by rt/debug?."
  [first-vec]
  (when rt/debug?
    (when (and first-vec
               (not (contains? @warned-keyless* first-vec)))
      (swap! warned-keyless* conj first-vec)
      (rt/warn!
       (str "[solidclj.hiccup] sequence rendered without :key metadata. "
            "Add ^{:key id} to each [:tag ...] in the seq, or use [:for] "
            "for keyed diffing.")))))

(defn as-element
  "Walk a hiccup form, returning something SolidJS can render:
   - vectors → `h` calls (or recursive component invocations);
   - sequences → walked element-wise and returned as a JS array, so
     `(for …)` flattens into the parent's children list;
   - atoms/refs → NOT interpreted: rendered as their printed
     representation, and dev builds warn — reactivity requires a
     deref inside a thunk;
   - functions → wrapped so SolidJS treats them as reactive thunks;
     whatever hiccup the fn returns is walked too;
   - everything else (strings, numbers, nil, …) → returned unchanged."
  [form]
  (cond
    (vector? form)    (as-vec form)

    (seq? form)
    (let [items     (filter renderable? form)
          vec-items (filter vector? items)]
      (when (and rt/debug?
                 (< 1 (count vec-items))
                 (not (every? #(get (meta %) :key) vec-items)))
        (warn-keyless-once! (first vec-items)))
      (rt/->children (map as-element items)))

    (watchable? form) (do (warn-bare-atom! "a child slot") (pr-str form))
    (fn? form)            #?(:cljs (if isServer (as-element (form)) (fn [] (as-element (form))))
                             :clj  (fn [] (as-element (form))))
    :else                 form))

#?(:cljs
   (defn render
     "Reagent-style mount: takes a hiccup form and a DOM root element,
  and returns SolidJS's dispose fn."
     [hiccup root]
     (solid-web/render #(as-element hiccup) root))

   :clj
   (defn render
     "JVM render: builds a LIVE reactive tree from `hiccup` — swap! a
  satom and the tree updates fine-grained, exactly like the browser.
  Returns {:tree node :dispose fn}. Read the current state at any
  point with `snapshot`; call :dispose (or use `with-render`) to tear
  down every effect, watcher and cleanup."
     [hiccup]
     (let [[tree dispose] (rt/create-root #(rt/insert (as-element hiccup)))]
       {:tree tree :dispose dispose})))

#?(:clj
   (do
     (defn- snap-props
       "Props for a snapshot: :classList's truthy entries merge into
  :class (that's what the DOM shows), nil-valued props drop (Solid
  removes those attributes), everything else — including handler
  fns — passes through as data."
       [props]
       (let [cl     (:classList props)
             extras (when (map? cl)
                      (keep (fn [[k v]] (when (rt/js-truthy? v) (str k))) cl))
             base-c (:class props)
             cls    (cond-> []
                      (and (some? base-c) (not= "" base-c)) (conj (str base-c))
                      (seq extras) (into extras))
             m      (dissoc props :classList :class)
             m      (if (seq cls)
                      (assoc m :class (clojure.string/join " " cls))
                      m)]
         (reduce-kv (fn [acc k v] (if (nil? v) acc (assoc acc k v))) {} m)))

     (declare snap-element)

     (defn- snap-splice
       "Serialize a tree value into a SEQ of hiccup forms: slots
  resolve to their content, node-list vectors flatten (that's what
  the DOM does), nil/booleans drop."
       [x]
       (cond
         (nil? x)             []
         (boolean? x)         []
         (rt/slot-node? x)    (snap-splice (:content @x))
         (rt/element-node? x) [(snap-element x)]
         (rt/portal-node? x)  [(into [:portal] (mapcat snap-splice (:children @x)))]
         (sequential? x)      (mapcat snap-splice x)
         :else                [x]))

     (defn- snap-element [el]
       (let [{:keys [tag props children]} @el
             p (snap-props props)]
         (into (if (seq p) [(keyword tag) p] [(keyword tag)])
               (mapcat snap-splice children))))

     (defn snapshot
       "Serialize a live tree (or the {:tree …} handle `render`
  returns) to plain hiccup at this point in time: control flow
  collapsed to what is rendered, thunks materialized,
  handler fns preserved in props (call them from tests, then snapshot
  again). Portal content appears under a [:portal …] marker.

  The output is standard hiccup — query it with anything that eats
  data (get-in, tree-seq, hiccup-find, matcher-combinators) or feed
  it to a hiccup→HTML library for static SSR.

  A root that renders multiple forms (fragment / [:for] at top level)
  returns a vector OF forms; a root that renders nothing returns nil."
       [x]
       (let [root  (if (and (map? x) (contains? x :tree)) (:tree x) x)
             forms (vec (snap-splice root))]
         (case (count forms)
           0 nil
           1 (nth forms 0)
           forms)))

     (defmacro with-render
       "Render `hiccup`, bind the handle, run `body`, always dispose:

      (with-render [t [counter {:start 5}]]
        (is (= [:div \"5\"] (snapshot t))))"
       [[sym hiccup] & body]
       `(let [~sym (render ~hiccup)]
          (try ~@body (finally ((:dispose ~sym))))))))

#?(:cljs
   (defn render-to-string
     "Server-side render: returns an HTML string for `hiccup`. CLJS-only
  (node SSR with hydration markers)."
     [hiccup]
     (renderToString #(as-element hiccup))))

#?(:cljs
   (defn hydrate-app
     "Client-side hydration: attach to SSR markup in `root`."
     [hiccup root]
     (hydrate #(as-element hiccup) root)))
