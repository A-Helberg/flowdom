(ns frontend.pages
  "The guide's content. Each page is prose plus example blocks whose
  source is inlined at compile time with shadow.resource/inline — the
  code you read IS the code that runs, they cannot drift apart."
  (:require [shadow.resource :as rc]
            [solidclj.docs.ui :as ui]
            [frontend.examples.perf :as perf]
            [frontend.examples.hello :as hello]
            [frontend.examples.elements :as elements]
            [frontend.examples.thunks :as thunks]
            [frontend.examples.satom :as satom]
            [frontend.examples.satom-h :as satom-h]
            [frontend.examples.atom-props :as atom-props]
            [frontend.examples.h-macro :as h-macro]
            [frontend.examples.show :as show]
            [frontend.examples.switch :as switch]
            [frontend.examples.dynamic :as dynamic]
            [frontend.examples.for-list :as for-list]
            [frontend.examples.index-list :as index-list]
            [frontend.examples.seqs :as seqs]
            [frontend.examples.fragments :as fragments]
            [frontend.examples.refs :as refs]
            [frontend.examples.form-uncontrolled :as form-uncontrolled]
            [frontend.examples.form-controlled :as form-controlled]
            [frontend.examples.portal :as portal]
            [frontend.examples.suspense :as suspense]
            [frontend.examples.error-boundary :as error-boundary]
            [frontend.examples.js-components :as js-components]
            [frontend.examples.react-basic :as react-basic]
            [frontend.examples.react-chart :as react-chart]
            [frontend.examples.reagent-counter :as reagent-counter]
            [frontend.examples.missionary-hold :as missionary-hold]
            [frontend.examples.missionary-resource :as missionary-resource]
            [frontend.examples.missionary-spawn :as missionary-spawn]
            [frontend.examples.missionary-tracked :as missionary-tracked]
            [frontend.examples.rpc-chat :as rpc-chat]
            [frontend.examples.rpc-rooms :as rpc-rooms]
            [frontend.examples.datomic-txes :as datomic-txes]
            [frontend.examples.live-by-hand :as live-by-hand]
            [frontend.examples.live-notes :as live-notes]
            ;; compiled into the app (not just inlined as source) so
            ;; the server-values page's try-it-from-the-console
            ;; instruction works when running full-stack
            [api.server-info]
            [api.viewer]))

(def sections
  [{:title "Introduction"
    :pages
    [{:id    :home
      :title "Why solidclj?"
      :body
      [:div
       [:div {:class "prose prose-gray max-w-none"}
        [:p "solidclj is Reagent-style hiccup on top of SolidJS. You write plain "
         "ClojureScript functions that return hiccup vectors — but instead of "
         "re-rendering components and diffing a virtual DOM, SolidJS wires "
         "fine-grained subscriptions directly to the DOM nodes that depend on "
         "each piece of state. When state changes, only those nodes update."]
        [:p "The grid below makes the difference visible. It is 2500 dots; 8 random "
         "dots change color every second. Every DOM node that changes gets a "
         "brief blue flash (that's global on this site — toggle it in the "
         "sidebar). On the Solid side, a tick touches exactly one dot. On the "
         "React side the state lives at the top, so every tick re-runs the whole "
         "grid — each dot is re-stamped with the render pass that produced it, "
         "which is the same thing React DevTools' “highlight updates” shows."]]
       [perf/demo]
       [:details {:class "mt-6 border border-gray-200 rounded-lg overflow-hidden"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "Solid grid source"]
        [ui/code-block (rc/inline "frontend/examples/perf_solid.cljs")]]
       [:details {:class "mt-3 border border-gray-200 rounded-lg overflow-hidden"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "React grid source"]
        [ui/code-block (rc/inline "frontend/examples/perf_react.cljs")]]]}]}

   {:title "Basics"
    :pages
    [{:id    :components
      :title "Components"
      :prose
      [:<>
       [:p "A component is a plain function that returns hiccup. There are no "
        "macros, no registration, no lifecycle protocol — putting the function "
        "in the first slot of a vector invokes it with the remaining elements "
        "as positional arguments."]
       [:p "Unlike Reagent, the component function runs " [:strong "once"] ". "
        "It builds the DOM; afterwards only the reactive regions inside it "
        "(thunks, signals, atoms — covered in the Reactivity section) ever "
        "run again."]]
      :examples
      [{:source    (rc/inline "frontend/examples/hello.cljs")
        :component hello/example}]}

     {:id    :elements
      :title "Elements & props"
      :prose
      [:<>
       [:p "HTML elements are keywords. Classes and ids can ride on the keyword "
        "itself (" [:code ":p.font-bold#title"] "), and the props map accepts "
        "several shapes for " [:code ":class"] " and " [:code ":style"] "."]
       [:p "Keep Tailwind variants like " [:code "hover:underline"] " and "
        "classes containing dots (" [:code "py-1.5"] ") in the "
        [:code ":class"] " string — the keyword shorthand splits on "
        [:code "."] " and can't represent them."]]
      :examples
      [{:source    (rc/inline "frontend/examples/elements.cljs")
        :component elements/example}]}]}

   {:title "Reactivity"
    :pages
    [{:id    :thunks
      :title "Signals & thunks"
      :prose
      [:<>
       [:p "The one rule to remember: SolidJS does " [:strong "not"] " re-run "
        "your component when state changes. You mark the dynamic region of the "
        "tree by wrapping it in a " [:code "(fn [] …)"] " — that thunk is what "
        "re-runs when a signal read inside it changes."]
       [:p "Solid's own primitives work directly: " [:code "createSignal"]
        " returns a getter/setter pair, and a getter placed in a child slot "
        "is live on its own."]
       [:p "Watch the flashes as you increment: the count paragraph only "
        "updates its text, and the even/odd thunk swaps in a fresh "
        [:code "<p>"] " because it returns a new element each run. The "
        "button never flashes — the component function ran once, and "
        "nothing outside a thunk is ever touched again."]]
      :examples
      [{:source    (rc/inline "frontend/examples/thunks.cljs")
        :component thunks/example}]}

     {:id    :satom
      :title "Reactive atoms — s/atom"
      :prose
      [:<>
       [:p [:code "s/atom"] " is the Reagent idiom brought over: a real atom ("
        [:code "swap!"] ", " [:code "reset!"] ", " [:code "add-watch"]
        " and validators all behave exactly like " [:code "cljs.core/atom"]
        ") that " [:strong "also"] " subscribes any reactive scope that derefs "
        "it. " [:code "(fn [] @my-atom)"] " is a live view of the atom. It is "
        "the " [:em "only"] " atom the renderer knows about — plain "
        [:code "cljs.core/atom"] "s are ignored (with a dev-time warning)."]
       [:p "Resetting to an " [:code "="] " value notifies watchers (normal "
        "atom semantics) but does not re-run reactive scopes, so no-op updates "
        "are free. One difference from Reagent: the component body is not a "
        "reactive scope here, so a bare " [:code "@a"] " at the top level of a "
        "component is a one-shot snapshot — the deref must run inside a "
        "thunk. Writing those thunks by hand gets old; the next page "
        "introduces the macro that writes them for you."]]
      :examples
      [{:source    (rc/inline "frontend/examples/satom.cljs")
        :component satom/example}]}

     {:id    :h-macro
      :title "The h macro"
      :prose
      [:<>
       [:p "The " [:code "h"] " macro walks a hiccup literal at compile "
        "time and wraps list-form expressions in child positions — like the "
        [:code "(if …)"] " below — into reactive thunks for you. Explicit "
        [:code "(fn [] …)"] " forms are left alone, so you can mix both "
        "styles and keep fine-grained control where you want it."]
       [:p "A bare " [:code "@temp"] " reads as " [:code "(deref temp)"]
        " — also a list form — so derefs are wrapped too, each into its own "
        "thunk. The second example is the previous page's thermometer with "
        "the thunks gone: note how the temperature line now updates a "
        "single text node instead of swapping the paragraph, while the "
        [:code "(if …)"] " is wrapped as a whole — the deref inside it "
        "feeds a comparison, and the re-runnable unit is the branch."]]
      :examples
      [{:title     "Auto-wrapped control flow"
        :source    (rc/inline "frontend/examples/h_macro.cljs")
        :component h-macro/example}
       {:title     "Bare derefs"
        :source    (rc/inline "frontend/examples/satom_h.cljs")
        :component satom-h/example}]}

     {:id    :atom-props
      :title "Atoms in props"
      :prose
      [:<>
       [:p "Why can't " [:code "{:value @text}"] " just work on its own? "
        "Because " [:code "@text"] " evaluates while your component body "
        "runs — the renderer receives the string, with no way to know an "
        "atom was involved. Under " [:code "h"] " the deref is moved into "
        "an accessor at compile time (" [:code "{:value (fn [] @text)}"]
        "), which SolidJS wires to the DOM property."]
       [:p "Alternatively, pass the s/atom itself un-deref'd — "
        [:code "[:input {:value text}]"] " — and the walker bridges it. "
        "Handler props (" [:code ":on*"] ", " [:code ":ref"] ") are never "
        "rewritten by " [:code "h"] "; their values are callbacks, not "
        "reactive reads."]]
      :examples
      [{:source    (rc/inline "frontend/examples/atom_props.cljs")
        :component atom-props/example}]}]}

   {:title "Control flow"
    :pages
    [{:id    :show
      :title "Show"
      :prose
      [:<>
       [:p [:code "[:show {:when … :fallback …}]"] " wraps Solid's "
        [:code "<Show>"] ": children render while " [:code ":when"] " is "
        "truthy, the fallback otherwise. " [:code ":when"] " accepts an "
        "s/atom, a signal getter, or a plain value."]]
      :examples
      [{:source    (rc/inline "frontend/examples/show.cljs")
        :component show/example}]}

     {:id    :switch
      :title "Switch & Match"
      :prose
      [:<>
       [:p "For more than two branches, " [:code "[:switch]"] " renders the "
        "first " [:code "[:match {:when …}]"] " whose condition is truthy, "
        "or the fallback when none is. " [:code ":match"] " is only valid "
        "directly inside " [:code ":switch"] "."]]
      :examples
      [{:source    (rc/inline "frontend/examples/switch.cljs")
        :component switch/example}]}

     {:id    :dynamic
      :title "Dynamic"
      :prose
      [:<>
       [:p [:code "[:dynamic {:component …}]"] " renders a component chosen "
        "at runtime — a tag string, a component fn, or an s/atom holding "
        "either. When the atom changes, the element is swapped while its "
        "children stay put."]]
      :examples
      [{:source    (rc/inline "frontend/examples/dynamic.cljs")
        :component dynamic/example}]}]}

   {:title "Lists"
    :pages
    [{:id    :for
      :title "Keyed lists — :for"
      :prose
      [:<>
       [:p [:code "[:for {:each xs} render-fn]"] " wraps Solid's "
        [:code "<For>"] ", which keys rows by item identity: swap!s that "
        "reuse existing items are diffed in place, and reordering moves DOM "
        "nodes instead of rebuilding them."]
       [:p "The render fn receives " [:code "(item index)"] " where "
        [:code "index"] " is a getter — call it as " [:code "(index)"]
        " inside a thunk to keep the position live. Watch the flashes when "
        "you reverse: only the numbers update, the row text doesn't."]]
      :examples
      [{:source    (rc/inline "frontend/examples/for_list.cljs")
        :component for-list/example}]}

     {:id    :index
      :title "Position-keyed lists — :index"
      :prose
      [:<>
       [:p [:code "[:index]"] " is " [:code ":for"] "'s sibling, keyed by "
        [:em "position"] " instead of identity: each row's DOM node is "
        "reused and only its content updates. Use it when items mutate in "
        "place; use " [:code ":for"] " when identities are stable and may "
        "move around."]
       [:p "Mind the flipped signature: the render fn gets "
        [:code "(item-getter index-number)"] " — the opposite of "
        [:code ":for"] "."]]
      :examples
      [{:source    (rc/inline "frontend/examples/index_list.cljs")
        :component index-list/example}]}

     {:id    :seqs
      :title "Plain sequences"
      :prose
      [:<>
       [:p "Any seq — like a " [:code "(for …)"] " comprehension — flattens "
        "into the parent's children. This renders once and is not keyed, so "
        "it's right for static lists; for changing collections reach for "
        [:code ":for"] " / " [:code ":index"] ". Add " [:code "^{:key …}"]
        " metadata to each vector to satisfy the dev-time checker."]]
      :examples
      [{:source    (rc/inline "frontend/examples/seqs.cljs")
        :component seqs/example}]}]}

   {:title "Advanced"
    :pages
    [{:id    :fragments
      :title "Fragments"
      :prose
      [:<>
       [:p [:code "[:<> …]"] " renders children as siblings with no wrapper "
        "element — essential where the parent dictates its children's tags, "
        "like " [:code "<dl>"] ", " [:code "<tr>"] " or CSS grid."]]
      :examples
      [{:source    (rc/inline "frontend/examples/fragments.cljs")
        :component fragments/example}]}

     {:id    :refs
      :title "Refs"
      :prose
      [:<>
       [:p [:code ":ref"] " takes a function that Solid calls with the DOM "
        "element once it exists. The usual pattern is stashing it in an atom "
        "— note this atom holds a DOM node, not app state, so nothing here "
        "is reactive."]]
      :examples
      [{:source    (rc/inline "frontend/examples/refs.cljs")
        :component refs/example}]}

     {:id    :forms
      :title "Forms"
      :prose
      [:<>
       [:p "Because SolidJS never re-runs your component, uncontrolled "
        "inputs aren't the second-class citizen they are in React. Let "
        "the browser own the input state and read it all at once on "
        "submit with " [:code "FormData"] " — no atom per field, and "
        "nothing updates while the user types (watch for the absence "
        "of flashes)."]
       [:p "Reach for a controlled input when the UI must react "
        [:em "while"] " the user types — live validation, previews, "
        "filtering. Then " [:code ":value"] " comes from an s/atom and "
        "every keystroke writes it back: exactly the pattern from the "
        "Atoms in props page."]]
      :examples
      [{:title     "Uncontrolled — FormData on submit"
        :source    (rc/inline "frontend/examples/form_uncontrolled.cljs")
        :component form-uncontrolled/example}
       {:title     "Controlled — react per keystroke"
        :source    (rc/inline "frontend/examples/form_controlled.cljs")
        :component form-controlled/example}]}

     {:id    :portal
      :title "Portal"
      :prose
      [:<>
       [:p [:code "[:portal {:mount el}]"] " renders children into another "
        "DOM node — toasts, modals, tooltips — while they keep their place "
        "in the reactive graph: cleanup and reactivity behave as if they "
        "were still inside the component."]]
      :examples
      [{:source    (rc/inline "frontend/examples/portal.cljs")
        :component portal/example}]}

     {:id    :suspense
      :title "Suspense"
      :prose
      [:<>
       [:p [:code "createResource"] " turns an async fetch into a signal, and "
        [:code "[:suspense {:fallback …}]"] " shows the fallback until "
        "resources under it resolve. Re-fetches keep showing the stale value "
        "instead of falling back — watch the flash when the new value lands."]]
      :examples
      [{:source    (rc/inline "frontend/examples/suspense.cljs")
        :component suspense/example}]}

     {:id    :error-boundary
      :title "Error boundary"
      :prose
      [:<>
       [:p [:code "[:error-boundary]"] " catches errors thrown while "
        "rendering its children. The fallback fn receives the error and a "
        [:code "reset"] " callback that re-renders the children — clear the "
        "cause before calling it or you'll be right back."]]
      :examples
      [{:source    (rc/inline "frontend/examples/error_boundary.cljs")
        :component error-boundary/example}]}

     {:id    :js-components
      :title "JS components"
      :prose
      [:<>
       [:p [:code "[:> SomeJsComp {…} children]"] " invokes a JS Solid "
        "component: the props map becomes a JS object, fn-valued props pass "
        "as Solid accessors, and children go through as usual. Solid's own "
        "components are already exposed as keywords (" [:code ":show"] ", "
        [:code ":for"] ", …), so this is for npm libraries — the example "
        "defines its 'library' component inline against the raw hyperscript "
        "API."]]
      :examples
      [{:source    (rc/inline "frontend/examples/js_components.cljs")
        :component js-components/example}]}]}

   {:title "React interop"
    :pages
    [{:id    :react-components
      :title "React components"
      :prose
      [:<>
       [:p [:code "[react/component Comp props & children]"] " mounts a real "
        "React root inside the Solid tree. Props: static values are captured "
        "at mount; s/atoms are watched and re-render the root on every "
        "change; functions pass through as-is (event handlers, render "
        "props). The root unmounts when the surrounding Solid scope "
        "disposes."]
       [:p "Children are React elements built with " [:code "react/el"]
        " — a thin " [:code "React.createElement"] " wrapper that takes a "
        "CLJS props map — so component libraries like recharts compose "
        "naturally. Note the flashing when the chart updates: React "
        "re-renders its whole root, exactly the behaviour the home page "
        "compares against."]]
      :examples
      [{:title     "A React component with an s/atom prop"
        :source    (rc/inline "frontend/examples/react_basic.cljs")
        :component react-basic/example}
       {:title     "recharts — React children via react/el"
        :source    (rc/inline "frontend/examples/react_chart.cljs")
        :component react-chart/example}]}

     {:id    :reagent
      :title "Reagent components"
      :prose
      [:<>
       [:p [:code "react.reagent/component"] " does the same for Reagent: "
        "the component renders through Reagent's own pipeline, so its "
        "internal " [:code "r/atom"] " state and re-rendering work exactly "
        "as in a Reagent app."]
       [:p "Props crossing the bridge follow the same rule as everywhere "
        "else: s/atoms are watched, plain atoms (including "
        [:code "r/atom"] "s used " [:em "as props"] ") are not — keep "
        "Reagent state inside the Reagent component and use s/atoms to "
        "feed it from outside."]]
      :examples
      [{:source    (rc/inline "frontend/examples/reagent_counter.cljs")
        :component reagent-counter/example}]}]}

   {:title "Missionary"
    :pages
    [{:id    :missionary-hold
      :title "Flows & hold"
      :prose
      [:<>
       [:p "Missionary is a functional effect system: a "
        [:strong "flow"] " is a value describing a stream — building "
        "one runs nothing. " [:code "sm/hold"] " bridges it into the "
        "UI as a read-only reactive ref that derefs exactly like an "
        [:code "s/atom"] ", so everything from the previous pages (bare "
        "derefs under " [:code "h"] ", thunks, un-deref'd refs in "
        "child slots) applies unchanged."]
       [:p [:code "hold"] " is lazy and refcounted, like a Reagent "
        "reaction: the flow starts on the first reactive deref and is "
        "cancelled when the last subscriber unmounts. Try it — visit "
        "another page and come back, and the counter restarts from "
        "zero even though the hold is a " [:code "defonce"] ". A hold "
        "nobody renders costs nothing."]
       [:p "One naming rule keeps the two libraries composable: "
        [:code "solidclj.missionary"] " never reuses a "
        [:code "missionary.core"] " name — " [:code "m/watch"]
        " still means atom → flow, so flow → ref needed a different "
        "word, and " [:em "hold"] " is FRP's."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_hold.cljs")
        :component missionary-hold/example}]}

     {:id    :missionary-resource
      :title "Tasks & suspense"
      :prose
      [:<>
       [:p "A missionary " [:strong "task"] " is a recipe for one "
        "asynchronous value. " [:code "sm/resource"] " runs it and "
        "returns a reactive ref built on Solid's "
        [:code "createResource"] " — so a deref while the task is in "
        "flight suspends to the nearest " [:code "[:suspense]"]
        " fallback, and " [:code "sm/reload!"] " cancels the current "
        "run and re-executes the recipe."]
       [:p "Prefer branching by hand? " [:code "(sm/pending? r)"]
        " and " [:code "(sm/error r)"] " are reactive and never "
        "suspend. A failed resource re-throws its error on deref, "
        "which is what " [:code "[:error-boundary]"] " is for. Note "
        "that resources are eager — the fetch starts when the "
        "component body runs, so create them inside components, not "
        "at the top level."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_resource.cljs")
        :component missionary-resource/example}]}

     {:id    :missionary-spawn
      :title "Effects — spawn!"
      :prose
      [:<>
       [:p "Not every task produces a value for the DOM. "
        [:code "sm/spawn!"] " runs a task purely for its effects and "
        "ties it to the component's lifetime: mounting starts it, "
        "unmounting cancels it. It deliberately takes a task, not a "
        "flow — missionary already knows how to turn one into the "
        "other (" [:code "m/reduce"] " below), and the bridge stays "
        "one primitive."]
       [:p "Toggle the heart: while mounted, the loop beats twice a "
        "second into an s/atom; unmount and the count freezes — the "
        "task was cancelled, not orphaned. Remounting spawns a fresh "
        "run. Calling " [:code "spawn!"] " outside a component throws "
        "in dev builds: an effect nobody can cancel is a leak."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_spawn.cljs")
        :component missionary-spawn/example}]}

     {:id    :missionary-tracked
      :title "Solid → missionary"
      :prose
      [:<>
       [:p "The bridge runs both ways. For a single atom, missionary "
        "already has the operator: s/atoms are watchable, so "
        [:code "(m/watch my-atom)"] " gives a live flow of its values "
        "with no adapter. For a whole " [:em "computation"] ", "
        [:code "sm/tracked"] " runs a thunk in a Solid tracking scope "
        "and emits every re-run — any s/atom deref'd inside re-fires "
        "it, deduplicated with " [:code "="] "."]
       [:p "From there the missionary toolbox applies: below, "
        [:code "m/latest"] " derives Fahrenheit and "
        [:code "m/reductions"] " accumulates a history — something a "
        "ref can't remember on its own — and " [:code "hold"] " brings "
        "each result back into hiccup. The whole chain is lazy end to "
        "end: it spins up when this page renders and tears down when "
        "you leave (which is why the history resets)."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_tracked.cljs")
        :component missionary-tracked/example}]}]}

   {:title "solidrpc"
    :pages
    [{:id    :rpc-chat
      :title "Queries & commands"
      :prose
      [:<>
       [:p "solidrpc streams query results from the server over SSE: "
        [:code "(rpc/query 'chat/messages)"] " returns a missionary "
        [:strong "flow"] ". On this page the server's state is just "
        "an atom, and a query is what you'd write yourself: "
        [:code "m/watch"] " the atom, map a function over it, "
        [:code "dedupe"] " — it re-runs when the state changes and "
        "pushes only changed answers, a full value first, diffs "
        "after. None of that is visible from here: a flow is a flow, "
        "so " [:code "sm/hold"] " bridges it into hiccup and "
        "everything from the Missionary section applies unchanged. "
        "The connection opens when a page first derefs the hold and "
        "closes when the last subscriber leaves. Writes go the other "
        "way as plain POSTs — " [:code "rpc/command"] " returns a "
        "promise, and on the server a command is a "
        [:code "swap!"] "."]
       [:p "Components never write " [:code "rpc/query"] " at call "
        "sites, though. The shape is a " [:code ".cljc"]
        " api namespace per domain — the " [:code ":clj"] " branch is "
        "the real implementation, the " [:code ":cljs"] " branch "
        "delegates to solidrpc, and the var is registered under its "
        "own symbol, so both sides call " [:code "(chat/messages)"]
        " and the rpc plumbing lives in one file:"]
       [ui/code-block
        "(ns api.chat
  (:require #?(:clj  [server.chat :as impl]
               :cljs [solidrpc.call.solidjs :as rpc])))

(defn messages []
  #?(:clj  (impl/messages-flow)
     :cljs (rpc/query `messages)))

(defn send! [text]
  #?(:clj  (impl/send! text)
     :cljs (rpc/command `send! text)))

;; server startup:
;; (solidrpc.registry/register! #'api.chat/messages)
;; (solidrpc.registry/register! #'api.chat/send!)"]
       [:p "This site is static — there is no server. The demos run "
        "on browser stand-ins behind the real names (on later pages "
        "even " [:code "datomic.api"] " resolves to one), and the "
        "server sources in collapsed blocks are the real code."]]
      :examples
      [{:title     "A query and a command"
        :source    (rc/inline "frontend/examples/rpc_chat.cljs")
        :component rpc-chat/example}
       {:title     "Reactive arguments — switching rooms"
        :source    (rc/inline "frontend/examples/rpc_rooms.cljs")
        :component rpc-rooms/example}]}

     {:id    :datomic-txes
      :title "A Datomic tx-listener"
      :prose
      [:<>
       [:p "The previous page's server state was an atom, and "
        [:code "m/watch"] " made change notification free. Swap the "
        "atom for a real database and that's the piece you have to "
        "replace: a feed of changes. Datomic's is "
        [:code "d/tx-report-queue"] ", a blocking queue of tx-reports, "
        "and pulling is exactly missionary's model: the whole listener "
        "is one " [:code "m/ap"] " — attach the queue, " [:code ".take"]
        " forever on the blocking executor, detach on cancel. No "
        "adapter thread, no core.async, no callbacks."]
       [:p "One thing the code insists on: Datomic keeps " [:em "one"]
        " report queue per connection and concurrent takers steal from "
        "each other, so the listener runs once and is shared with "
        [:code "m/stream"] " — lazy and refcounted, the JVM twin of "
        [:code "sm/hold"] "'s lifecycle: the queue attaches when the "
        "first subscriber arrives and detaches when the last one "
        "leaves. The demo below is just the feed: press the buttons "
        "and watch reports land."]
       [:p "Run the example app full-stack and "
        [:code "(server.notes/add-note! \"hi\")"] " from a REPL "
        "pushes to every connected browser; the listener and its "
        "solidrpc wiring are the two files below."]
       [:details {:class "mt-4 border border-gray-200 rounded-lg overflow-hidden not-prose"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "The listener (server.tx-listener)"]
        [ui/code-block (rc/inline "server/tx_listener.clj")]]
       [:details {:class "mt-3 border border-gray-200 rounded-lg overflow-hidden not-prose"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "Wired into solidrpc (server.notes)"]
        [ui/code-block (rc/inline "server/notes.clj")]]]
      :examples
      [{:source    (rc/inline "frontend/examples/datomic_txes.cljs")
        :component datomic-txes/example}]}

     {:id    :live-by-hand
      :title "Live queries by hand"
      :prose
      [:<>
       [:p "A live query is two pieces, and only one of them is new. "
        "The old piece: a pure function of a database value — "
        [:code "(all-notes db)"] " — testable by calling it with any "
        "db in hand, no flows, no server. The new piece: which "
        "databases to run it against. The previous page's feed "
        "answers that — the db as of now, then " [:code ":db-after"]
        " of every report."]
       [:p "Read the pipeline in the demo inside out: " [:code "db<"]
        " emits database values; " [:code "(map all-notes)"] " turns "
        "each into an answer; " [:code "dedupe"] " drops answers "
        "equal to the last — so a transaction that can't change the "
        "result re-runs the query and emits " [:em "nothing"] " (the "
        "irrelevant-tx button; watch for the absence of a flash). "
        "Hold the flow and the list is live."]
       [:p "Notice what stayed pure. " [:code "all-notes"] " never "
        "learns about reports or flows. And because database values "
        "are immutable, 'the answer at t' needs no flow at all — "
        [:code "(all-notes (d/as-of db t))"] " is a plain function "
        "call, frozen forever. This composition is the standard "
        "shape for every read endpoint — which is exactly why you "
        "shouldn't have to write it by hand every time."]]
      :examples
      [{:source    (rc/inline "frontend/examples/live_by_hand.cljs")
        :component live-by-hand/example}]}

     {:id    :live-queries
      :title "Live queries"
      :prose
      [:<>
       [:p "The composition you just wrote is the shape of every "
        "read endpoint, so " [:code "solidrpc.live"] " packages it: "
        [:code "(live/live env db f)"] " is the previous page's "
        "pipeline — start from " [:code "db"] ", re-run " [:code "f"]
        " per report, dedupe — where " [:code "env"] " is your store "
        "as two keys, " [:code "{:db (fn [] current) :reports flow}"]
        ". One thing is added on top: an optional "
        [:code ":relevant?"] " predicate on the tx-report, checked "
        [:em "before"] " the query re-runs. Dedupe already keeps "
        "unchanged answers off the wire; " [:code ":relevant?"]
        " skips the re-query itself — \"did this transaction touch a "
        [:code ":note/*"] " attribute\" — which matters once queries "
        "are expensive."]
       [:p "The convention that goes with it: the api namespace "
        "colocates the pure fn with its " [:code "<"] "-suffixed "
        "facade, registered under its " [:em "own"] " symbol — the "
        "client's " [:code "(call/query `all-notes< db)"] " resolves "
        "to the same var whose " [:code ":clj"] " branch produces the "
        "flow, so the two sides cannot drift apart. Facades return "
        "flows; views hold them at point of use. Calling a read runs "
        "nothing — a flow is a recipe, and work starts when a "
        "component renders the hold. The same component runs live in "
        "the browser and renders on the JVM without mocks (see the "
        "Testing section)."]
       [:p "The demo below runs the " [:strong "real"] " combinator — "
        [:code "solidrpc.live"] " is cljc, this is the same code the "
        "server runs — through " [:code "frontend.notes"] ", "
        [:code "api.notes"] "'s stand-in twin. Both panels are pure "
        "components — "
        [:code "[live-panel db]"] " holds the facade's flow, and "
        [:code "[pinned-panel db]"] " is the previous page's "
        "function-call-against-a-value, no flow at all; only the "
        "demo shell with its buttons performs effects."]
       [:p "Now notice the awkward part. " [:code "all-notes<"]
        " takes a database — and a browser can't have one. The demo "
        "only pins as-of views because the fake store lives in the "
        "browser, values at hand; against a real server the client "
        "can pass nothing but " [:code "nil"] " (this app's facades "
        "treat that as 'now') and take whatever arrives. Queries on "
        "one page get no common starting point, a command can't hand "
        "back the database that contains its write, and there is no "
        "way to tell a test or an SSR render 'this exact state'."]
       [:p "The pattern adds one file to your app:"]
       [:details {:class "mt-4 border border-gray-200 rounded-lg overflow-hidden not-prose"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "The api namespace (api.notes)"]
        [ui/code-block (rc/inline "api/notes.cljc")]]]
      :examples
      [{:source    (rc/inline "frontend/examples/live_notes.cljs")
        :component live-notes/example}]}

     {:id    :server-values
      :title "Server values"
      :prose
      [:<>
       [:p "The previous page ended on the wall: "
        [:code "all-notes<"] " takes a database, and a browser can't "
        "have one. It isn't only the db — a query that depends on "
        "who is asking wants the same shape, "
        [:code "(visible-notes db user)"] ", a function of the user, "
        "not of a session lurking somewhere — because arguments are "
        "what made these fns testable: hand them values, no "
        "machinery. But the db and the user are both values that "
        "only exist " [:em "on the server"] ". So what does the "
        "client pass?"]
       [:p "The two obvious answers both give something up. Shipping "
        "the value doesn't work: the db is too big, and a "
        "client-supplied user is an authentication bypass — the "
        "client must not be able to assert who it is. Making it "
        "ambient — endpoints reaching into a session, a dynamic var, "
        "a global — works, and is what most apps do, but it puts a "
        "hidden input back into every fn you just made pure: tests "
        "need the machinery again, and the fn's signature stops "
        "telling the truth."]
       [:p "The way out is the move the db already made on the "
        "previous page, generalized: the client doesn't need the "
        "value — components never look inside it, they only pass it "
        "to reads. So the client passes a " [:em "name"] " for it — "
        "a ref, plain data — and at the serialization boundary the "
        "server exchanges the name for the real value. The endpoint "
        "fn receives an ordinary argument and stays pure: the same "
        "fn is called with a real value in-process (JVM tests "
        "construct a user map, no auth machinery anywhere) and "
        "reconstructs one at the wire. And because reconstruction "
        "happens server-side — from your session store, a JWT, a db "
        "read; solidrpc never knows — the client can't forge what it "
        "never builds. A marker ref carries nothing at all."]
       [:p "For the db, the name is a basis-t: a database value "
        "leaves the server as " [:code "#solid/db {:basis-t 1010}"]
        " and comes back as an " [:em "actual database value"]
        " via " [:code "d/as-of"] ". That closes every gap the "
        "previous page listed. The client passes the ref as "
        [:code "all-notes<"] "'s db argument and the flow starts "
        "from " [:em "that"] " database — a floor it immediately "
        "catches up from, so you never see anything older than what "
        "you hold. Queries handed the same ref share a starting "
        "point; a command that returns the post-transaction db "
        "(below) hands the client read-your-writes with no cache to "
        "patch; and a test or SSR render given a ref is given an "
        "exact, reproducible state."]
       [:p "Concretely, a value type is a tag, a marker, a facade, "
        "and one handler where you mount the rpc handlers. The "
        "handler contract: " [:code ":read-handlers"] " maps a tag "
        "to " [:code "(fn [on-the-wire-value] value)"] " — it runs "
        "while the incoming args decode, receives what the ref "
        "carried over the wire (transit calls this the rep; a bare "
        "marker carries " [:code "{}"] ", which is why the fns below "
        "ignore it), and whatever it returns "
        [:em "becomes the argument"]
        " the endpoint fn receives. The canonical value type here is "
        "the current user, but solidrpc ships no auth system, so the "
        "example uses two stand-ins chosen to show the two closure "
        "lifetimes: server-info reconstructs from a closure made at "
        [:em "startup"] ", the viewer from a closure over the "
        [:em "request"] " — which is exactly where your session "
        "lookup goes, with nothing about the shape changing."]
       [ui/code-block
        ";; the value type: a tag, a marker, a facade — one cljc file
(def tag \"app/server-info\")

(defn server-info-ref [] (transit/ref tag))

(defn server-info< [info]
  #?(:clj  (m/ap info)                       ;; in-process: info IS the value
     :cljs (call/query `server-info< info))) ;; wire: info is the marker ref

;; the mount point: your router fn has the request in scope
(def started-at (System/currentTimeMillis))

(defn query-handler [req]
  (rpc/handle-query req
    {:read-handlers
     {tag                                     ;; closes over startup state
      (fn [_wire-value] {:started-at started-at
                         :uptime-ms  (- (System/currentTimeMillis) started-at)})

      api.viewer/tag                          ;; closes over THIS request
      (fn [_wire-value] {:remote-addr (:remote-addr req)
                         :user-agent  (get-in req [:headers \"user-agent\"])})}}))

;; in a view (cljs), a read like any other — hold it at point of use
(let [info< (sm/hold (server-info< (server-info-ref)) :initial nil)]
  [:p \"up \" (fn [] (some-> @info< :uptime-ms)) \" ms\"])

;; the round trip:
;;   view      (server-info-ref)          a marker, plain data
;;   wire out  [\"~#app/server-info\",[\"^ \"]]
;;   decode    the read handler for the tag runs; its return value
;;             becomes the argument server-info< receives
;;   endpoint  (server-info< {:started-at … :uptime-ms …})
;;   wire in   plain data — the hold updates, the thunk re-runs"]
       [:p "That covers values coming in. Values also " [:em "leave"]
        ": " [:code ":write-handlers"] " is the outgoing contract, "
        "mapping a " [:em "type"] " — dispatch is by the value's "
        "type, since the server holds real values — to "
        [:code "{:tag … :rep (fn [value] on-the-wire-value)}"] ". "
        "The worked case is the db: let a command return the "
        "post-transaction database, and the client receives a ref it "
        "can anchor its next read with — the read-your-writes the "
        "previous page promised, with no cache to patch."]
       [ui/code-block
        ";; the write direction: a command returns the post-tx db
(defn add-note! [text]
  (:db-after @(d/transact conn [{:note/text text}])))

(defn command-handler [req]
  (rpc/handle-command req
    {:write-handlers
     {datomic.db.Db {:tag \"solid/db\"
                     :rep (fn [db] {:basis-t (d/basis-t db)})}}}))

;; the client gets #solid/db {:basis-t t} — a ref like any other —
;; and anchors its next read with it
(-> (add-note! \"buy milk\")
    (.then (fn [db] (reset! current-db db))))"]
       [:p "Two conventions complete the picture. A handler that "
        "rejects — no session, expired token — throws "
        [:code "(ex-info \"no session\" {:solidrpc/status 401})"]
        " and the response carries that status, so clients can tell "
        "an invalid session from a server error. And because decode "
        "runs once per request while an SSE connection can live for "
        "minutes, reconstruct " [:em "identity"] " at the edge and "
        "derive " [:em "authorization"] " from the db inside the "
        "query fn — then open streams tighten on the transaction "
        "that revokes, not at reconnect."]
       [:p "One security note, because as-of is a time machine: the "
        "server does not restrict which t a client may name, and "
        "doesn't need to. The wire still only carries "
        "endpoint-shaped results; the trust boundary is the query fn "
        "(authorize against the present, read domain data at t); a "
        "ref is usually a re-observation of answers the client was "
        "already served; and data that must not be readable at "
        [:em "any"] " t is excision's job."]
       [:p "Both value types are real in the example app "
        "(api.server-info, api.viewer, mounted in server.core) with "
        "JVM tests that drive the mount handler with fake requests — "
        "run " [:code "task test-jvm"] ", or run the app full-stack "
        "and call " [:code "(whoami< (viewer-ref))"] " or "
        [:code "(server-info< (server-info-ref))"] " from the browser."]]}]}

   {:title "Testing"
    :pages
    [{:id    :jvm-testing
      :title "Rendering on the JVM"
      :prose
      [:<>
       [:p "The hiccup walker is cljc, and underneath it "
        [:code "solidclj.runtime"] " has two implementations: real "
        "solid-js in the browser, and a simulator on the JVM — "
        "signals, effects, owners, cleanup, and all the control-flow "
        "components, with the same semantics (a parity suite runs "
        "identical fixtures against both and fails on drift). So "
        "components render and " [:em "react"] " in plain Clojure: "
        [:code "swap!"] " a satom and the tree updates fine-grained, "
        "exactly like the browser."]
       [:p "The API is three functions. " [:code "render"]
        " builds a live tree, " [:code "snapshot"] " serializes it "
        "back to plain hiccup at a point in time — control flow "
        "collapsed to what's rendered, handler fns preserved in "
        "props as data — and " [:code "with-render"] " scopes "
        "disposal. There is no test library beyond that: snapshots "
        "are standard hiccup, so " [:code "get-in"] ", "
        [:code "tree-seq"] ", hiccup-find and matcher-combinators "
        "are the query language, and you fire an event by calling "
        "the handler you pulled out of a snapshot."]
       [ui/code-block
        "(deftest counter-behaves
  (with-render [t [counter {:start 5}]]
    (is (match? [:div [:span 5] [:button {:onClick fn?} \"+\"]]
                (snapshot t)))
    ((get-in (snapshot t) [2 1 :onClick]) :click)   ;; handlers are data
    (is (= [:span 6] (nth (snapshot t) 1)))))"]
       [:p "Because the missionary bridge and solidrpc.live are cljc "
        "too, the whole stack runs in-process: the test below "
        "renders the real notes component against the real facade, "
        "the real live combinator and an in-memory Datomic — no "
        "HTTP, no mocks. It types into the input by calling the "
        "snapshot's " [:code ":onInput"] ", clicks Add, and asserts "
        "the note comes back through the tx-report stream. Run it "
        "with " [:code "task test-jvm"] "."]
       [:details {:class "mt-4 border border-gray-200 rounded-lg overflow-hidden not-prose"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "The full-stack test (frontend.notes-view-test)"]
        [ui/code-block (rc/inline "frontend/notes_view_test.clj")]]
       [:p "And because dbs are values, 'the answer at t' is a plain "
        "function call — " [:code "(all-notes (d/as-of db t))"]
        " — no flow, no render lifecycle, no awaiting. A reproducible "
        "fixture is a value you keep."]]}]}])
