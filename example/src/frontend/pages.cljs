(ns frontend.pages
  "The guide's content. Each page is prose plus example blocks whose
  source is inlined at compile time with shadow.resource/inline — the
  code you read IS the code that runs, they cannot drift apart."
  (:require [shadow.resource :as rc]
            [flowdom.docs.ui :as ui]
            [frontend.examples.perf :as perf]
            [frontend.examples.hello :as hello]
            [frontend.examples.elements :as elements]
            [frontend.examples.thunks :as thunks]
            [frontend.examples.satom :as satom]
            [frontend.examples.helpers :as helpers]
            [frontend.examples.atom-props :as atom-props]
            [frontend.examples.show :as show]
            [frontend.examples.switch :as switch]
            [frontend.examples.dynamic :as dynamic]
            [frontend.examples.for-list :as for-list]
            [frontend.examples.index-list :as index-list]
            [frontend.examples.seqs :as seqs]
            [frontend.examples.fragments :as fragments]
            [frontend.examples.form-uncontrolled :as form-uncontrolled]
            [frontend.examples.form-controlled :as form-controlled]
            [frontend.examples.suspense :as suspense]
            [frontend.examples.error-boundary :as error-boundary]
            [frontend.examples.missionary-hold :as missionary-hold]
            [frontend.examples.missionary-resource :as missionary-resource]
            [frontend.examples.missionary-tracked :as missionary-tracked]
            [frontend.examples.portal :as portal]
            [frontend.examples.on-mount :as on-mount]
            [frontend.examples.react-basic :as react-basic]
            [frontend.examples.react-chart :as react-chart]
            [frontend.examples.reagent-counter :as reagent-counter]
            [frontend.examples.missionary-spawn :as missionary-spawn]
            [frontend.examples.rpc-chat :as rpc-chat]
            [frontend.examples.rpc-rooms :as rpc-rooms]
            [frontend.examples.rpc-hold :as rpc-hold]
            [frontend.examples.rpc-states :as rpc-states]
            [frontend.examples.datomic-txes :as datomic-txes]
            [frontend.examples.live-by-hand :as live-by-hand]
            [frontend.examples.live-notes :as live-notes]))

(def sections
  [{:title "Introduction"
    :pages
    [{:id    :home
      :title "Why flowdom?"
      :body
      [:div
       [:div {:class "prose prose-gray max-w-none"}
        [:p "flowdom is Reagent-style hiccup over fine-grained "
         "reactivity, built directly on "
         [:a {:href "https://github.com/leonoel/missionary"} "missionary"]
         ". You write plain ClojureScript functions that return hiccup "
         "vectors — but instead of re-rendering components and diffing a "
         "virtual DOM, flowdom wires small processes directly to the DOM "
         "nodes that depend on each piece of state. When state changes, "
         "only those nodes update."]
        [:p "The grid below makes the granularity visible. It is 2500 "
         "dots; 8 random dots change color every second. Every DOM node "
         "that changes gets a brief blue flash (that's global on this "
         "site — toggle it in the sidebar). A tick touches exactly one "
         "dot: no component re-runs, no diff, no other node is visited. "
         "Flip to the React tab for the contrast — the same 2500 dots "
         "with state at the top, so every tick re-stamps the whole grid "
         "(its source is below)."]]
       [perf/demo]
       [:details {:class "mt-6 border border-gray-200 rounded-lg overflow-hidden"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "flowdom grid source"]
        [ui/code-block (rc/inline "frontend/examples/perf_flowdom.cljs")]]
       [:details {:class "mt-3 border border-gray-200 rounded-lg overflow-hidden"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "React grid source (for contrast)"]
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
        "It builds the tree; afterwards only the reactive regions inside it "
        "(the " [:code "rx"] " blocks, covered in the Reactivity section) "
        "ever run again."]]
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
        [:code "."] " and can't represent them."]
       [:p "Most props are HTML attributes, but the ones the DOM owns as "
        "live properties — " [:code ":value"] ", " [:code ":checked"] ", "
        [:code ":indeterminate"] ", " [:code ":selected"] " — are set as "
        "properties so they keep reflecting after the user interacts. "
        "Custom elements receive object-valued props as properties too, "
        "unstringified. Handlers are " [:code ":on*"] " keys; a bare fn is "
        "the listener, or pass " [:code "{:handler f :once true}"]
        " (also " [:code ":capture"] ", " [:code ":passive"]
        ") for addEventListener options."]]
      :examples
      [{:source    (rc/inline "frontend/examples/elements.cljs")
        :component elements/example}]}]}

   {:title "Reactivity"
    :pages
    [{:id    :thunks
      :title "rx & ?"
      :prose
      [:<>
       [:p "The one rule to remember: flowdom does " [:strong "not"] " re-run "
        "your component when state changes. You mark the dynamic region of "
        "the tree with " [:code "(rx …)"] " — a restartable block. Inside "
        "it, " [:code "(? src)"] " reads a reactive source (an atom, a "
        "missionary flow, or another rx) " [:em "and"] " subscribes the "
        "block; when any recorded dependency changes, the block re-runs "
        "from scratch and its region of the DOM is patched."]
       [:p "Granularity follows from where you put the blocks. An rx whose "
        "value is a scalar updates a single text node; an rx that returns "
        "different hiccup swaps its subtree, cancelling everything under "
        "the old one. Re-runs are deduplicated with " [:code "="] " — a "
        "body that computes an equal value emits nothing."]
       [:p "Propagation is synchronous: by the time " [:code "swap!"]
        " returns, the DOM is updated. Watch the flashes as you "
        "increment: the count line touches one text node, the even/odd rx "
        "swaps in a fresh " [:code "<p>"] " because it returns a new "
        "element each time, and the button never flashes — the component "
        "function ran once."]]
      :examples
      [{:source    (rc/inline "frontend/examples/thunks.cljs")
        :component thunks/example}]}

     {:id    :satom
      :title "Plain atoms"
      :prose
      [:<>
       [:p "State lives in ordinary " [:code "cljs.core/atom"] "s — no "
        "special reactive atom type. " [:code "swap!"] ", " [:code "reset!"]
        ", " [:code "add-watch"] ", validators: everything behaves exactly "
        "as it always does, because it " [:em "is"] " the ordinary atom. "
        "What makes one reactive is where it's read: " [:code "(? a)"]
        " inside an rx subscribes the block; a bare " [:code "@a"]
        " anywhere is a plain one-shot read."]
       [:p "Dependencies are re-recorded on every run, so a branch that "
        "stops reading an atom unsubscribes from it automatically — and a "
        [:code "reset!"] " to an equal value notifies watchers (normal "
        "atom semantics) but re-runs nothing."]]
      :examples
      [{:source    (rc/inline "frontend/examples/satom.cljs")
        :component satom/example}]}

     {:id    :helpers
      :title "Reads cross functions"
      :prose
      [:<>
       [:p "Tracking rides dynamic scope, not syntax. A helper called from "
        "an rx block reads sources with " [:code "?"] " like any other "
        "value — at any call depth, through ordinary function signatures. "
        "There is no macro rewriting your code, no lifting values into a "
        "reactive wrapper type, and no split between \"reactive functions\" "
        "and plain ones."]
       [:p "This is the property that keeps reactive code looking like "
        "ordinary Clojure: extract a helper, it still works; inline it "
        "back, nothing changes. The only boundary that matters is the "
        [:code "rx"] " block itself — it delimits what re-runs."]
       [:p "It is also why flowdom has no macro that writes the rx "
        "blocks for you by rewriting hiccup literals. Such a macro can "
        "only see the literal — helpers would need different rules, "
        "splitting the language in two — and the trap it would guard "
        "against can't happen here: a " [:code "?"] " outside an rx "
        "throws immediately instead of going silently stale. And the "
        "block is worth seeing: where you place " [:code "rx"] " is the "
        "update granularity, and each block is a missionary flow. One "
        "rule, no exceptions."]]
      :examples
      [{:source    (rc/inline "frontend/examples/helpers.cljs")
        :component helpers/example}]}

     {:id    :atom-props
      :title "Reactive props"
      :prose
      [:<>
       [:p "Why can't " [:code "{:value @text}"] " just work on its own? "
        "Because " [:code "@text"] " evaluates while your component body "
        "runs — the renderer receives the string, with no way to know an "
        "atom was involved. An rx in the prop value is the live version: "
        [:code "{:value (rx (? text))}"] " writes only that DOM property "
        "when the atom changes; the element is never rebuilt."]
       [:p "Handler props (" [:code ":on*"] ") are never treated as "
        "reactive — their values are callbacks. Everything else accepts "
        "an rx."]]
      :examples
      [{:source    (rc/inline "frontend/examples/atom_props.cljs")
        :component atom-props/example}]}]}

   {:title "Control flow"
    :pages
    [{:id    :show
      :title "Conditionals"
      :prose
      [:<>
       [:p "There are no control-flow components. An rx that emits "
        "different hiccup " [:em "is"] " the conditional: when the branch "
        "flips, the old subtree's processes are cancelled (that is the "
        "entire unmount story — nothing leaks) and the new content "
        "mounts."]]
      :examples
      [{:source    (rc/inline "frontend/examples/show.cljs")
        :component show/example}]}

     {:id    :switch
      :title "Branches"
      :prose
      [:<>
       [:p "More than two branches is ordinary Clojure — " [:code "case"]
        ", " [:code "cond"] ", whatever fits. The rx re-runs when "
        [:code "(? status)"] " changes and the renderer swaps to whichever "
        "tree came back. Nothing new to learn is the feature."]]
      :examples
      [{:source    (rc/inline "frontend/examples/switch.cljs")
        :component switch/example}]}

     {:id    :dynamic
      :title "Dynamic tags"
      :prose
      [:<>
       [:p "Tags are data — keywords in the first slot of a vector — so a "
        "runtime-chosen element is an atom holding a keyword and an rx "
        "using it in tag position. When it changes, the element is "
        "rebuilt with the same children."]]
      :examples
      [{:source    (rc/inline "frontend/examples/dynamic.cljs")
        :component dynamic/example}]}]}

   {:title "Lists"
    :pages
    [{:id    :for
      :title "Keyed lists — for-by"
      :prose
      [:<>
       [:p [:code "(for-by key-fn items body)"] " renders a keyed "
        "collection: " [:code "items"] " is an atom, flow, rx, or plain "
        "vector; " [:code "body"] " runs " [:em "once per key"] " and "
        "receives an atom-like holding that item's latest value — read it "
        "with " [:code "?"] " inside an rx."]
       [:p "An item whose value changed ticks only its own slots; "
        "reordering moves DOM nodes, and each row's processes and local "
        "state move with it; removed keys are cancelled, new keys mount. "
        "Watch the flashes when you reverse: rows move, but no row "
        "content re-renders."]]
      :examples
      [{:source    (rc/inline "frontend/examples/for_list.cljs")
        :component for-list/example}]}

     {:id    :index
      :title "Position-keyed lists"
      :prose
      [:<>
       [:p "Position-keying is " [:code "for-by"] " with the index as the "
        "key: each row's DOM node is reused and only its content updates "
        "when a new value lands in that slot. Use it when the position is "
        "the identity (sensor readouts, spreadsheet cells); key by id "
        "when items keep their identity while moving around."]]
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
        [:code "for-by"] "."]]
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

     {:id    :dom-node
      :title "DOM nodes"
      :prose
      [:<>
       [:p [:code ":on-mount"] " takes a function that flowdom calls with the "
        "element once it's created and inserted into the document — the escape "
        "hatch to the raw DOM for focus, measurement, or a non-flowdom library. "
        "The usual pattern stashes the node in an atom; note this atom holds a "
        "DOM node, not app state, so nothing here is reactive."]
       [:p "Return a function and it becomes the node's teardown, cancelled "
        "with the element's other processes when it unmounts — symmetric setup "
        "and cleanup for things like observers:"]
       [ui/code-block
        "[:div {:on-mount (fn [el]
                   (let [ro (js/ResizeObserver. on-resize)]
                     (.observe ro el)
                     #(.disconnect ro)))}]"]]
      :examples
      [{:source    (rc/inline "frontend/examples/on_mount.cljs")
        :component on-mount/example}]}

     {:id    :forms
      :title "Forms"
      :prose
      [:<>
       [:p "Because flowdom never re-runs your component, uncontrolled "
        "inputs aren't the second-class citizen they are in React. Let "
        "the browser own the input state and read it all at once on "
        "submit with " [:code "FormData"] " — no atom per field, and "
        "nothing updates while the user types (watch for the absence "
        "of flashes)."]
       [:p "Reach for a controlled input when the UI must react "
        [:em "while"] " the user types — live validation, previews, "
        "filtering. Then " [:code ":value"] " is an rx over an atom and "
        "every keystroke writes it back; propagation is synchronous, so "
        "there's no caret jumping or echo lag to work around."]
       [:p "A controlled " [:code ":value"] " is IME-aware: while an "
        "input method is composing — pinyin, kana, dead keys — flowdom "
        "holds off writing the value back, then applies the latest when "
        "composition ends, so the candidate window is never cut off "
        "mid-word."]]
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
        "in the process tree: reactivity crosses the portal, the nearest "
        [:code ":fallback"] " and " [:code ":error-boundary"] " still "
        "apply, and unmounting the portal's position cancels and removes "
        "the ported content. " [:code ":mount"] " defaults to "
        [:code "document.body"] "."]
       [:p "On the JVM there is no foreign DOM to escape into, so "
        "snapshots keep portal content in place under a "
        [:code "[:portal …]"] " marker — tests can assert on it like any "
        "other node."]]
      :examples
      [{:source    (rc/inline "frontend/examples/portal.cljs")
        :component portal/example}]}

     {:id    :suspense
      :title "Async & fallback"
      :prose
      [:<>
       [:p "Reading a flow that hasn't produced a value yet doesn't block "
        "and doesn't need a loading flag: the rx is " [:em "pending"]
        ", and the nearest enclosing element with a " [:code ":fallback"]
        " prop renders the fallback in that position until the value "
        "arrives. Pending is a state of the flow, not a cached flag — "
        "reading a " [:em "new"] " flow (next user, reload) goes through "
        "the fallback again."]
       [:p [:strong "The one rule of async:"] " create flows outside rx "
        "bodies, or memoize their construction as the example does. "
        [:code "?"] " subscribes by flow identity, so a fresh flow object "
        "built inside a re-running body would resubscribe forever and "
        "never settle."]]
      :examples
      [{:source    (rc/inline "frontend/examples/suspense.cljs")
        :component suspense/example}]}

     {:id    :error-boundary
      :title "Error boundary"
      :prose
      [:<>
       [:p "A throwing rx doesn't take the app down: the error travels "
        "upward as a value until an " [:code ":error-boundary"] " catches "
        "it and renders its fallback — " [:code "(fn [err retry] …)"] ". "
        "Recovery is two-fold: if the failing " [:em "dependency"]
        " changes, the same subtree heals in place, state intact (the "
        "example's Recover button); " [:code "retry"] " remounts the "
        "subtree from scratch, for failures whose cause isn't a "
        "dependency you can clear."]
       [:p "An error that reaches the top with no boundary above it is "
        "the root's to handle: pass " [:code "dom/mount"] " an "
        [:code ":on-error (fn [e remount!])"] " and every such error "
        "lands there instead of the console, with " [:code "remount!"]
        " — the root-level " [:code "retry"] " — to tear the whole app "
        "down and rebuild it. Without the hook, an uncaught error logs "
        "and that region goes dark; with it, you decide (a full "
        "remount, an error screen, a report)."]]
      :examples
      [{:source    (rc/inline "frontend/examples/error_boundary.cljs")
        :component error-boundary/example}]}]}

   {:title "React interop"
    :pages
    [{:id    :react-components
      :title "React components"
      :prose
      [:<>
       [:p [:code "[react/component Comp props & children]"] " mounts a real "
        "React root inside the flowdom tree — a host element owns the root, "
        "and the region's lifetime is the root's lifetime: it unmounts when "
        "the region unmounts. Props follow the same rule as everywhere else: "
        "static values are captured at mount; atoms and rx values are read, "
        "and every change re-renders the root (deduplicated with "
        [:code "="] "); functions pass through as-is (event handlers, render "
        "props)."]
       [:p "Children are React elements built with " [:code "react/el"]
        " — a thin " [:code "React.createElement"] " wrapper that takes a "
        "CLJS props map — so component libraries like recharts compose "
        "naturally. Note the flashing when the chart updates: React "
        "re-renders its whole root, exactly the behaviour the home page "
        "compares against."]]
      :examples
      [{:title     "A React component with an atom prop"
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
       [:p "Props cross the bridge by the same rule as the rest of the "
        "app — atoms and rx values are read, everything else is static — "
        "so there is no special contract to remember. One boundary to "
        "keep: an " [:code "r/atom"] " is Reagent-internal reactivity. "
        "Use it inside the component, and feed the component from "
        "outside with flowdom state — the atom prop below."]]
      :examples
      [{:source    (rc/inline "frontend/examples/reagent_counter.cljs")
        :component reagent-counter/example}]}]}

   {:title "Missionary"
    :pages
    [{:id    :missionary-hold
      :title "Flows in the UI"
      :prose
      [:<>
       [:p "Every piece of state so far has been a value in an atom. A UI "
        "also deals in things that are not: timers, async requests, "
        "streams of server results. Missionary is the effect system "
        "underneath flowdom, and a " [:strong "flow"] " is a value "
        "describing a stream — building one runs nothing. There is no "
        "bridge to cross: " [:code "(? my-flow)"] " reads a flow the same "
        "way it reads an atom."]
       [:p "Lifecycle is subscription. The flow starts when the rx "
        "reading it first mounts and is cancelled when it unmounts — try "
        "it: visit another page and come back, and the counter restarts "
        "from zero even though the flow is a " [:code "def"] ". A flow "
        "nobody renders costs nothing, and a flow nobody watches anymore "
        "is cancelled, not leaked."]
       [:p "One consequence to keep in mind: each " [:code "?"] " cell "
        "is its own subscription, so two rx blocks reading this flow "
        "directly would be two independent timers. When many readers "
        "should share one running flow, wrap it once in "
        [:code "flowdom.rx/hold"] " and read the hold everywhere — the "
        "flowrpc section makes the difference visible with a "
        "connection counter."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_hold.cljs")
        :component missionary-hold/example}]}

     {:id    :missionary-resource
      :title "Tasks & reload"
      :prose
      [:<>
       [:p "A missionary " [:strong "task"] " is a recipe for one "
        "asynchronous value; " [:code "m/ap"] " turns it into a "
        "one-emission flow the UI can read. While it's in flight the rx "
        "is pending and the " [:code ":fallback"] " renders — the same "
        "mechanics as the Async page, because a request isn't a special "
        "thing here, just another flow."]
       [:p "Re-running a recipe is reading a " [:em "new"] " flow: the "
        "example memoizes construction by run number, so \"reload\" is "
        "bumping an atom. The old run is cancelled with the subtree that "
        "read it — an in-flight request you navigated away from doesn't "
        "linger."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_resource.cljs")
        :component missionary-resource/example}]}

     {:id    :missionary-spawn
      :title "Effects"
      :prose
      [:<>
       [:p "Not every flow produces a value for the DOM. "
        [:code "(effect flow)"] " runs one purely for its side "
        "effects: it is an rx that reads the flow and renders "
        "nothing, so the tree position it occupies is its lifetime — "
        "mounting subscribes (the flow starts), unmounting cancels "
        "it. No new machinery: lifetime is subscription, like "
        "everything else in flowdom; " [:code "effect"] " just names "
        "the run-for-effect case."]
       [:p "Toggle the heart: while mounted, the loop beats twice a "
        "second into an atom; unmount and the count freezes — the "
        "flow was cancelled, not orphaned. Remounting is a fresh "
        "run. And an effect built but never placed in the tree runs "
        "nothing — a flow is a recipe, so there is nothing to leak."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_spawn.cljs")
        :component missionary-spawn/example}]}

     {:id    :missionary-tracked
      :title "rx is a flow"
      :prose
      [:<>
       [:p "The integration runs both ways because there is no adapter in "
        "either direction. Atoms are already flows via " [:code "m/watch"]
        ". And an " [:code "rx"] " " [:em "is"] " a missionary continuous "
        "flow — deduplicated with " [:code "="] " — so the whole "
        "missionary toolbox applies to it directly: below, "
        [:code "m/reductions"] " accumulates a history of every "
        "temperature seen, something a ref can't remember on its own, and "
        "the UI reads the result back with " [:code "?"] "."]
       [:p "The chain is lazy end to end: it spins up when this page "
        "renders and tears down when you leave (which is why the history "
        "resets). This composability is the reason flowdom is built on "
        "missionary rather than next to it."]]
      :examples
      [{:source    (rc/inline "frontend/examples/missionary_tracked.cljs")
        :component missionary-tracked/example}]}]}

   {:title "flowrpc"
    :pages
    [{:id    :rpc-chat
      :title "Queries & commands"
      :prose
      [:<>
       [:p "flowrpc is CQRS-shaped: reads and writes are different "
        "problems, so they are different operations. A "
        [:strong "query"] " is a read — it wants to stay correct as "
        "server state changes. A " [:strong "command"] " is a write "
        "— it happens once and either works or doesn't. Splitting "
        "them lets each side get the right tool: queries subscribe, "
        "commands post. Both are plain functions on the server, "
        "registered under a symbol the client calls them by."]
       [:h2 "Queries"]
       [:p "A query is a subscription, not a fetch. "
        [:code "(rpc/query 'chat/messages)"] " returns a missionary "
        [:strong "flow"] " that streams results over SSE: a full "
        "value first, then every changed answer for as long as "
        "anyone is subscribed. That is the reason to use one — the "
        "component never refetches, polls, or invalidates a cache, "
        "because the answer keeps itself current."]
       [:p "On the server, a query is any missionary flow. This "
        "page's server state is an atom, so the flow is one you'd "
        "write yourself: " [:code "m/watch"] " the atom, map a "
        "function over it, " [:code "dedupe"] " — it re-runs when "
        "the state changes and pushes only changed answers. From "
        "the client none of that is visible: a flow is a flow, so "
        [:code "?"] " reads it and everything "
        "from the Missionary section applies unchanged, including "
        "laziness — the connection opens when a page first reads "
        "the query and closes when the last subscriber leaves."]
       [:h2 "Commands"]
       [:p "A command is one round trip. "
        [:code "(rpc/command 'chat/send! text)"] " is a plain POST "
        "that returns a promise; on the server it's a function that "
        "performs the write — on this page, a " [:code "swap!"]
        " on the same atom the query watches."]
       [:p "Note what the command doesn't do: it doesn't return the "
        "new message list, and nothing on the client asks for it. "
        "The write changes the atom, the query's flow re-runs, and "
        "the update arrives over the stream that's already open. "
        "That loop is the payoff of the split: writes don't need to "
        "describe their effects, because every query they affect "
        "pushes the new answer."]
       [:h2 "Why SSE"]
       [:p "The obvious tool for push is a WebSocket, but the split "
        "means neither direction needs one: queries push from "
        "server to client, commands are one-shot requests from "
        "client to server, so each can be ordinary HTTP. An SSE "
        "response is just a long-lived HTTP stream and a command is "
        "a plain POST — both route through any load balancer to any "
        "node, get retried and traced like any other request, and "
        "under HTTP/2 and HTTP/3 every open query multiplexes over "
        "a single connection."]
       [:p "The bigger reason is that SSE carries no server-side "
        "session. A query stream is derived from current state — "
        "re-run the function, get the answer — so when a connection "
        "drops, the client reconnects with backoff, any node picks "
        "it up, and the query re-runs against the state of now. "
        "That makes rolling deploys transparent: an instance dies, "
        "its streams drop, clients land on a new instance, and the "
        "UI blinks once and catches up. A WebSocket is the opposite "
        "— a stateful pipe to one specific server, which is what "
        "sticky sessions and message brokers exist to work around."]
       [:p "The tradeoff is that SSE only pushes. If you need "
        "low-latency messaging from client to server — multiplayer "
        "cursors, collaborative editing — WebSockets are the right "
        "tool for now; when WebTransport (bidirectional streams "
        "over QUIC) matures, that choice is worth revisiting. For "
        "streamed reads and one-shot writes, SSE is strictly "
        "simpler; the flowrpc README has the longer version of "
        "this argument."]
       [:h2 "The api namespace"]
       [:p "Components never write " [:code "rpc/query"] " at call "
        "sites. The shape is a " [:code ".cljc"]
        " api namespace per domain — the " [:code ":clj"] " branch is "
        "the real implementation, the " [:code ":cljs"] " branch "
        "delegates to flowrpc, and the var is registered under its "
        "own symbol, so both sides call " [:code "(chat/messages)"]
        " and the rpc plumbing lives in one file:"]
       [ui/code-block
        "(ns api.chat
  (:require [missionary.core :as m]
            #?(:cljs [flowrpc.client :as rpc])))

;; the server's state — an atom is all this page needs
#?(:clj (defonce state (atom {:messages []})))

(defn messages []
  #?(:clj  (m/eduction (map :messages) (dedupe) (m/watch state))
     :cljs (rpc/query `messages)))

(defn send! [text]
  #?(:clj  (do (swap! state update :messages conj text) true)
     :cljs (rpc/command `send! text)))

;; server startup:
;; (flowrpc.registry/register! #'api.chat/messages)
;; (flowrpc.registry/register! #'api.chat/send!)"]
       [:p "The reason for the shape is referential transparency: "
        [:code "(chat/messages)"] " returns a flow of the messages "
        "on either platform. The " [:code ":clj"] " caller gets it "
        "in-process; the " [:code ":cljs"] " caller gets the same "
        "flow, its values arriving over a network. Because the call "
        "means one thing, callers don't care where they run — "
        "server code, tests and the REPL use the " [:code ":clj"]
        " branch directly, components compose the flow exactly as "
        "they would a local one, and swapping what's behind the "
        "function changes no call site."]
       [:p "The naming holds together mechanically, too: "
        [:code "register!"] " keys the var by its own symbol, and "
        "the syntax-quoted " [:code "`messages"] " in the "
        [:code ":cljs"] " branch resolves in the same namespace — "
        "both sides derive " [:code "api.chat/messages"] " from "
        "where the code sits. There is no wire-name string to keep "
        "in sync, and renaming the function renames the endpoint "
        "with it."]
       [:h2 "Reactive arguments"]
       [:p "Query args may be watchable refs (any atom-like): the "
        "query " [:strong "follows"] " them — a changed value closes "
        "the running connection and opens one for the new args, equal "
        "values dedup. And a selection that isn't known yet — because "
        "it derives from another query's answer — starts as "
        [:code "rpc/unresolved"] ", distinct from nil (resolved to "
        "nothing): while a followed ref holds the sentinel the query "
        "emits " [:em "nothing"] ", so loading states hold and the "
        "query is never asked at the ref's initial value. That is "
        "what kills the loading → wrong-answer flash. The rooms demo "
        "below starts unresolved: nothing connects until you pick."]
       [:p "This site is static — there is no server. The demos run "
        "on browser stand-ins behind the real names (on later pages "
        "even " [:code "datomic.api"] " resolves to one), and the "
        "server sources in collapsed blocks are the real code."]]
      :examples
      [{:title     "A query and a command"
        :source    (rc/inline "frontend/examples/rpc_chat.cljs")
        :component rpc-chat/example}
       {:title     "Reactive arguments — unresolved, then switching rooms"
        :source    (rc/inline "frontend/examples/rpc_rooms.cljs")
        :component rpc-rooms/example}]}

     {:id    :rpc-hold
      :title "Sharing a query — hold"
      :prose
      [:<>
       [:p "A query flow is cold: every " [:code "?"] " cell that "
        "reads it opens its own connection. Usually that is exactly "
        "right — mount is connect, unmount is disconnect — but when "
        "several parts of the UI want the same answer, per-reader "
        "connections are waste."]
       [:p [:code "flowdom.rx/hold"] " wraps the flow once. An rx is "
        [:code "m/signal"] "-shared, so any number of readers "
        [:code "?"] " the hold over " [:em "one"] " subscription — one "
        "connection — and the last reader leaving closes it. A hold "
        "is " [:em "only"] " the shared subscription: readers see "
        "whatever the source emits, so it is pending until the "
        "query's first answer — the nearest " [:code ":fallback"]
        " renders, and definitive states (\"no messages yet\") only "
        "appear once the server has actually answered. Wanting a "
        "placeholder instead is the " [:em "query's"] " business, "
        "not the hold's — " [:code "loading-value"] ", on the next "
        "page."]
       [:p "Build the hold once — at the ns level or in a component "
        "body — never inside an rx body: an rx re-run rebuilds "
        "whatever it constructs, and a rebuilt hold is a fresh "
        "connection (dev mode warns about this churn)."]
       [:p "The demo's counter is real: the fake backend counts "
        "running query flows the way a server counts open SSE "
        "streams. Flip between the two wirings — both panels show "
        "the same messages either way; what changes is how many "
        "connections carry them."]]
      :examples
      [{:source    (rc/inline "frontend/examples/rpc_hold.cljs")
        :component rpc-hold/example}]}

     {:id    :rpc-states
      :title "Query states — loading?< and friends"
      :prose
      [:<>
       [:p "A flow encodes its states positionally in missionary's "
        "protocol: subscribed-but-silent, emitting, failed. flowdom "
        "reads two of them out-of-band — pending renders the nearest "
        [:code ":fallback"] ", an error reaches the nearest "
        [:code ":error-boundary"] " — and usually that declarative "
        "pair is all you need. Sometimes you want the state "
        [:em "inline"] " instead: a spinner beside content that stays "
        "up, a submit button disabled until the data is in, an error "
        "message rendered in place."]
       [:p "Three combinators in " [:code "flowdom.rx"] " lift the "
        "states into ordinary flows — real missionary flows, built "
        "once like any other and read with " [:code "?"] ": "
        [:code "loading?<"] " is booleans, true while the source has "
        "no value yet; " [:code "error?<"] " is booleans, true while "
        "it is failed; " [:code "error<"] " carries the error itself, "
        "nil while healthy. They subscribe their source, so derive "
        "them from a hold to share its one connection. Check the "
        "error " [:em "before"] " reading the value — a failed "
        "source's value read still re-throws to the boundary."]
       [:h2 "Making the refetch visible"]
       [:p "A followed query reconnects when its ref changes, but "
        "readers can't see that: the flow is simply silent until the "
        "new answer arrives, and silence between emissions is the "
        "normal state of every flow. So the default is stale-while-"
        "refetching — the rooms demo relied on it. When the stale "
        "answer would read as the answer to the " [:em "new"]
        " question, opt the query in: the " [:code "loading-visible"]
        " sentinel, anywhere among its args, makes each (re)connect "
        "emit the pending marker before the answer — the switch and "
        "the signal are the same event, nothing to keep in sync. "
        [:code "unresolved"] " refs still emit nothing: 'not asked "
        "yet' is not 'loading'."]
       [:p "Its sibling " [:code "(loading-value x)"] " governs a "
        [:em "different"] " moment: what precedes the query's "
        [:em "first answer ever"] ". The placeholder arrives "
        [:em "wrapped"] " — " [:code "[:flowdom.rx/loading x]"]
        " — so it stays distinguishable from answers: " [:code "?"]
        " hands the wrapper through (a value, so no fallback), "
        [:code "(rx/value v)"] " unwraps it for rendering, "
        [:code "(rx/loading? v)"] " tests it, and " [:code "loading?<"]
        " stays true until the real answer lands. First answer only: "
        "a refetch never re-emits the placeholder (a live canvas "
        "seeds once; it doesn't flash blank every reconnect). The "
        "two options compose — placeholder initially, loading marker "
        "on refetches — and independently: neither, either, or both."]
       [:p "The marker itself — the keyword "
        [:code ":flowdom.rx/pending"] ", public as "
        [:code "flowdom.rx/pending"] " — is a protocol, not flowrpc "
        "magic: " [:em "any"] " flow may emit it to say 'no value "
        "right now', and every reader treats it like initial "
        "silence — " [:code "?"] " propagates pending, the nearest "
        [:code ":fallback"] " renders, " [:code "loading?<"] " reads "
        "true — until the flow's next emission. A hand-rolled "
        "websocket flow gets refetch visibility the same way."]]
      :examples
      [{:source    (rc/inline "frontend/examples/rpc_states.cljs")
        :component rpc-states/example}]}

     {:id    :datomic-txes
      :title "A Datomic tx-listener"
      :prose
      [:<>
       [:p "The queries page had live results because change "
        "notification was free: the server's state was an atom, and "
        [:code "m/watch"] " emits every new value. A real database "
        "doesn't hand you that — what it hands you is a feed of "
        "transactions, and turning that feed into a flow is the "
        "piece to build. Datomic's feed is "
        [:code "d/tx-report-queue"] ": a blocking queue onto which "
        "the peer library delivers every transaction as a "
        [:strong "tx-report"] " — a map of " [:code ":db-before"]
        ", " [:code ":db-after"] " and " [:code ":tx-data"] ", the "
        "datoms that changed."]
       [:h2 "The listener"]
       [:p "Pulling from a blocking queue is exactly missionary's "
        "model — park, take, emit, repeat — so on the JVM the whole "
        "listener is one " [:code "m/ap"] ". No adapter thread, no "
        "core.async, no callbacks:"]
       [ui/code-block
        "(ns server.tx-listener
  (:require [datomic.api :as d]
            [missionary.core :as m])
  (:import [java.util.concurrent BlockingQueue]))

(defn tx-report-flow
  \"A discrete flow of tx-reports from `conn`.\"
  [conn]
  (m/ap
    (let [^BlockingQueue queue
          (m/?> (m/observe (fn [emit!]
                             (emit! (d/tx-report-queue conn))
                             #(d/remove-tx-report-queue conn))))]
      (loop []
        (m/amb (m/? (m/via m/blk (.take queue)))
               (recur))))))"]
       [:p "Two lifecycle details carry the design. First, a flow "
        "is a recipe: nothing attaches to the connection until a "
        "consumer runs it, and cancelling detaches — which matters, "
        "because a report queue nobody drains accumulates reports "
        "forever. Second, " [:code "m/observe"] " is the "
        "acquire/release bracket: it emits the queue once at spawn "
        "and runs its cleanup thunk exactly once at cancel. A "
        [:code "try/finally"] " around the loop would not work — "
        [:code "m/amb"] " forks the process, so " [:code "finally"]
        " would run once per fork, detaching the queue right after "
        "the first report."]
       [:h2 "One queue per connection"]
       [:p "Datomic keeps " [:em "one"] " report queue per "
        "connection, and concurrent takers steal reports from each "
        "other. So the listener runs once, and everything "
        "downstream shares it:"]
       [ui/code-block
        ";; server.notes
(defonce tx-reports (m/stream (txl/tx-report-flow conn)))"]
       [:p [:code "m/stream"] " is lazy and refcounted — the JVM "
        "twin of " [:code "sm/hold"] "'s lifecycle. The queue "
        "attaches when the first subscriber arrives, every later "
        "subscriber joins the running listener, and it detaches "
        "when the last one leaves."]
       [:h2 "Late subscribers"]
       [:p "Sharing has a consequence: " [:code "m/stream"]
        " doesn't replay. A subscriber that arrives after ten "
        "transactions sees only the eleventh — an event feed "
        "announces the next change, never the present — so anything "
        "built on the raw feed would show nothing until someone "
        "writes. The feed's consumable form adds a catch-up head:"]
       [ui/code-block
        ";; server.notes
(def tx-reports<
  (m/ap (m/amb {:db-after (d/db conn) :tx-data []}
               (m/?> tx-reports))))"]
       [:p "The head reads the db at spawn — a flow is a recipe, so "
        "every subscriber gets its own read, current as of the last "
        "commit — and it carries no datoms, because it describes "
        "current state rather than a transaction. Replaying the "
        "last real report would be the obvious alternative, and it "
        "is subtly wrong: the lazy listener only processes reports "
        "while someone is subscribed, so a remembered report can be "
        "older than the state a subscriber already holds."]
       [:p "The demo below watches the feed. A tx-report flow is a "
        "server flow like any other, so it reaches the browser "
        "exactly as the queries page taught — an api namespace "
        "whose " [:code ":clj"] " branch shapes the shared stream "
        "for the wire and whose " [:code ":cljs"] " branch is a "
        "query:"]
       [ui/code-block
        "(ns api.txes
  (:require [missionary.core :as m]
            #?(:clj  [datomic.api :as d])
            #?(:clj  [server.notes :as notes])
            #?(:cljs [flowrpc.client :as rpc])))

(defn reports
  \"The tx-report feed so far — shaped for the wire, no db values.\"
  []
  #?(:clj  (->> notes/tx-reports
                (m/eduction (map (fn [{:keys [db-after tx-data]}]
                                   {:t (d/basis-t db-after)
                                    :tx-data tx-data})))
                (m/reductions conj []))
     :cljs (rpc/query `reports)))

;; add-note! and ping! are commands — the same shape as api.chat/send!
;; server startup:
;; (flowrpc.registry/register! #'api.txes/reports)"]
       [:p "Transact a note and the report lands with its basis-t "
        "and datoms — and so does the second button's transaction, "
        "which touches no " [:code ":note/*"] " attribute and which "
        "no notes query would care about. The feed carries every "
        "transaction on the connection; deciding which reports "
        "matter, and what to re-run when they do, is the job of the "
        "query built on top."]
       [:p "Run the example app full-stack and "
        [:code "(server.notes/add-note! \"hi\")"] " from a REPL "
        "pushes to every connected browser — the snippets above are "
        "the real code, in " [:code "server.tx-listener"] " and "
        [:code "server.notes"] "."]]
      :examples
      [{:source    (rc/inline "frontend/examples/datomic_txes.cljs")
        :component datomic-txes/example}]}

     {:id    :live-by-hand
      :title "Live queries by hand"
      :prose
      [:<>
       [:p "The demos so far have all been live, but each flow was "
        "shaped by hand for its one query — the chat page watched "
        "an atom and mapped one function over it. A "
        [:strong "live query"] " is the shape that generalizes: an "
        "ordinary query — a pure function of a database value, "
        [:code "(all-notes db)"] ", testable by calling it with any "
        "db in hand, no flows, no server — re-run against the "
        "databases the previous page's feed supplies: the head's "
        "db, then the " [:code ":db-after"] " of every report. "
        "The query never learns it is live; the feed decides when "
        "it re-runs. Composed in the notes domain's api namespace, "
        "the whole thing is:"]
       [ui/code-block
        "(ns api.notes
  (:require [missionary.core :as m]
            #?(:clj  [datomic.api :as d])
            #?(:clj  [server.notes :as store])
            #?(:cljs [flowrpc.client :as rpc])))

;; the pure part: a function of a database value
#?(:clj
   (defn all-notes [db]
     (->> (d/q '[:find ?e ?text :where [?e :note/text ?text]] db)
          (sort-by first)
          (mapv second))))

;; the live part, by hand
(defn all-notes< []
  #?(:clj  (let [db< (m/ap (:db-after (m/?> store/tx-reports<)))]
             (m/eduction (map all-notes) (dedupe) db<))
     :cljs (rpc/query `all-notes<)))"]
       [:p "Read the " [:code ":clj"] " branch inside out: "
        [:code "db<"] " emits database values — the head's, then "
        "each report's; " [:code "(map all-notes)"] " turns each "
        "into an answer; " [:code "dedupe"] " drops answers equal "
        "to the last — so a transaction that can't change the "
        "result re-runs the query and emits " [:em "nothing"] " "
        "(the irrelevant-tx button in the demo; watch for the "
        "absence of a flash). Read the flow and the list is live."]
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
        "read endpoint, so " [:code "flowrpc.live"] " packages it: "
        [:code "(live/live tx-reports< db f)"] " is the previous "
        "page's pipeline — the head's db, then " [:code ":db-after"]
        " per report, each through " [:code "f"] ", deduplicated. "
        "The store argument is the consumable feed itself; there is "
        "nothing else to wire."]
       [:p "One thing is added on top: an optional "
        [:code ":relevant?"] " predicate on the tx-report, checked "
        [:em "before"] " the query re-runs. Dedupe already keeps "
        "unchanged answers off the wire; " [:code ":relevant?"]
        " skips the re-query itself — \"did this transaction touch a "
        [:code ":note/*"] " attribute\" — which matters once queries "
        "are expensive. The catch-up head is exempt: a fresh "
        "subscriber must reach the present no matter what the last "
        "transaction touched, so " [:code "live"] " never filters "
        "the first report. The " [:code ":clj"] " branch you wrote "
        "by hand becomes one call:"]
       [ui/code-block
        ";; api.notes
(defn all-notes< [db]
  #?(:clj  (live/live store/tx-reports< db all-notes :relevant? note-tx?)
     :cljs (call/query `all-notes< db)))"]
       [:p [:code "all-notes<"] " also takes a db argument: the "
        [:strong "anchor"] ". Use it when the client already holds "
        "a database value — for example, " [:code "add-note!"]
        " returns the database that contains the new note. Passing "
        "that database to " [:code "all-notes<"] " makes the flow "
        "compute its first answer from it, then catch up to the "
        "current database. This guarantees the subscriber never "
        "sees an answer computed from a database older than the "
        "anchor: read-your-writes. Two queries given the same "
        "anchor start from the same database. Passing "
        [:code "nil"] " means no anchor; the first answer comes "
        "from the head's current db."]
       [:p "The one flow is also the design's real requirement: "
        [:em "value semantics"] " — reports carry the new database "
        "as an immutable value. The queries page's atom qualifies ("
        [:code "m/watch"] " already emits the current value first, "
        "then every change), Datomic qualifies for free, and so "
        "does anything in that family. This is not a Datomic live "
        "query, but it is an epochal-store live query: wire in a "
        "Postgres or a Kafka topic and you build the reports "
        "yourself — and a store that can't reconstruct its state at "
        "a point in time has no anchors, so a command can't hand "
        "back the database that contains its write."]
       [:h2 "The api namespace"]
       [:p "The convention: the api namespace colocates the pure fn "
        "with its " [:code "<"] "-suffixed facade, registered under "
        "its " [:em "own"] " symbol — the client's "
        [:code "(call/query `all-notes< db)"] " resolves to the same "
        "var whose " [:code ":clj"] " branch produces the flow, so "
        "the two sides cannot drift apart. Facades return flows; "
        "views read them at point of use. Calling a read runs "
        "nothing — a flow is a recipe, and work starts when a "
        "component renders the read. The same component runs live in "
        "the browser and renders on the JVM without mocks (see the "
        "Testing section)."]
       [:p "The pattern adds one file to your app:"]
       [:details {:class "mt-4 border border-gray-200 rounded-lg overflow-hidden not-prose"}
        [:summary {:class "px-4 py-2 text-sm font-medium text-gray-600 cursor-pointer bg-gray-50"}
         "The api namespace (api.notes)"]
        [ui/code-block (rc/inline "api/notes.cljc")]]
       [:p "The demo below runs the " [:strong "real"] " combinator — "
        [:code "flowrpc.live"] " is cljc, this is the same code the "
        "server runs. Both panels are pure components — "
        [:code "[live-panel db]"] " reads the facade's flow, and "
        [:code "[pinned-panel db]"] " is the previous page's "
        "function-call-against-a-value, no flow at all; only the "
        "demo shell with its buttons performs effects. The pin "
        "button keeps the db " [:code "add-note!"] " hands back: "
        "the database that contains that write, so the pinned panel "
        "is read-your-writes, frozen."]
       [:p "Now notice what crossed a boundary. " [:code "all-notes<"]
        " takes a database; " [:code "add-note!"] " returns one; the "
        "demo just passed it around like any argument. But a browser "
        "can't hold a database value — so what does the client "
        "actually receive, and what does it pass back as an anchor? "
        "Whatever it is, it has to do everything the value did: give "
        "queries on one page a common starting point, hand a test "
        "'this exact state', and cost almost nothing to carry."]]
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
       [:h2 "Tokens"]
       [:p "The way out is the move the db already made on the "
        "previous page, generalized: the client doesn't need the "
        "value — components never look inside it, they only pass it "
        "to reads. So the client passes a " [:em "name"] " for it — "
        "a " [:strong "tagged token"] ", plain data: a tag naming "
        "the value type, a rep carrying what the server needs to "
        "rebuild it — and at the serialization boundary the server "
        "exchanges the token for the real value. The endpoint fn "
        "receives an ordinary argument and stays pure: the same fn "
        "is called with a real value in-process (JVM tests "
        "construct a user map, no auth machinery anywhere) and "
        "reconstructs one at the wire. And because reconstruction "
        "happens server-side — from your session store, a JWT, a db "
        "read; flowrpc never knows — the client can't forge what it "
        "never builds. A marker token carries nothing at all."]
       [:p "For the db, the rep is a basis-t: a database value "
        "leaves the server as " [:code "#flowdom/db {:basis-t 1010}"]
        " and comes back as an " [:em "actual database value"]
        " via " [:code "d/as-of"] ". That closes every gap the "
        "previous page listed. The client passes the token as "
        [:code "all-notes<"] "'s db argument and the flow starts "
        "from " [:em "that"] " database — the anchor from the "
        "previous page — and immediately catches up, so you never "
        "see anything older than what you hold. Queries given the "
        "same token share a starting point; a command that returns "
        "the post-transaction db (below) gives the client "
        "read-your-writes with no cache to patch; and a test given "
        "a token is given an exact, reproducible state."]
       [:h2 "Read handlers"]
       [:p "Concretely, a value type is a tag, a marker, a facade — "
        "one cljc file:"]
       [ui/code-block
        ";; api.server-info
(def tag \"app/server-info\")

(defn server-info-token [] (transit/token tag))

(defn server-info< [info]
  #?(:clj  (m/ap info)                       ;; in-process: info IS the value
     :cljs (call/query `server-info< info))) ;; wire: info is the marker token"]
       [:p "Plus one read handler where you mount the rpc handlers. "
        "The contract: " [:code ":read-handlers"] " maps a tag "
        "to " [:code "(fn [on-the-wire-value] value)"] " — it runs "
        "while the incoming args decode, receives what the token "
        "carried over the wire (its rep; a bare marker carries "
        [:code "{}"] ", which is why the fns below "
        "ignore it), and whatever it returns "
        [:em "becomes the argument"]
        " the endpoint fn receives. The canonical value type here is "
        "the current user, but flowrpc ships no auth system, so the "
        "example uses two stand-ins chosen to show the two closure "
        "lifetimes: server-info reconstructs from a closure made at "
        [:em "startup"] ", the viewer from a closure over the "
        [:em "request"] " — which is exactly where your session "
        "lookup goes, with nothing about the shape changing."]
       [ui/code-block
        ";; the mount point: your router fn has the request in scope
(def started-at (System/currentTimeMillis))

(defn query-handler [req]
  (rpc/handle-query req
    {:read-handlers
     {tag                                     ;; closes over startup state
      (fn [_wire-value] {:started-at started-at
                         :uptime-ms  (- (System/currentTimeMillis) started-at)})

      api.viewer/tag                          ;; closes over THIS request
      (fn [_wire-value] {:remote-addr (:remote-addr req)
                         :user-agent  (get-in req [:headers \"user-agent\"])})}}))"]
       [:p "In a view, a server value reads like anything else — "
        "read it at point of use:"]
       [ui/code-block
        "(let [info< (server-info< (server-info-token))]
  [:p {:fallback \"…\"} \"up \" (rx (:uptime-ms (? info<))) \" ms\"])

;; the round trip:
;;   view      (server-info-token)          a marker token, plain data
;;   wire out  [\"~#app/server-info\",[\"^ \"]]
;;   decode    the read handler for the tag runs; its return value
;;             becomes the argument server-info< receives
;;   endpoint  (server-info< {:started-at … :uptime-ms …})
;;   wire in   plain data — the query emits, the rx re-runs"]
       [:h2 "Write handlers"]
       [:p "That covers values coming in. Values also " [:em "leave"]
        ": " [:code ":write-handlers"] " is the outgoing contract, "
        "mapping a " [:em "type"] " — dispatch is by the value's "
        "type, since the server holds real values — to "
        [:code "{:tag … :rep (fn [value] on-the-wire-value)}"] ". "
        "The worked case is the db: let a command return the "
        "post-transaction database, and the client receives a token "
        "it can anchor its next read with — the read-your-writes "
        "the previous page promised, with no cache to patch."]
       [ui/code-block
        ";; the write direction: a command returns the post-tx db
(defn add-note! [text]
  (:db-after @(d/transact conn [{:note/text text}])))

(defn command-handler [req]
  (rpc/handle-command req
    {:write-handlers
     {datomic.db.Db {:tag \"flowdom/db\"
                     :rep (fn [db] {:basis-t (d/basis-t db)})}}}))

;; the client gets #flowdom/db {:basis-t t} — a token like any other —
;; and anchors its next read with it
(-> (add-note! \"buy milk\")
    (.then (fn [db] (reset! current-db db))))"]
       [:p "Note that both handler maps are server-side. The client "
        "decodes and encodes these tags with no handlers at all: "
        "flowrpc's transit layer has one default — a tag it has no "
        "read handler for decodes to a generic token, a pair of tag "
        "and rep with value equality, and a token encodes back out "
        "under its own tag. So " [:code "#flowdom/db {:basis-t 1010}"]
        " arrives in the browser as plain data, and when the client "
        "passes it back as an argument it leaves unchanged; only "
        "the server's read handler for that tag ever looks inside. "
        "This is deliberate, not a missing feature: a client that "
        "never interprets a token can't grow a dependency on the "
        "rep's contents — the server can change what a tag carries "
        "without touching any client — and meaning is only ever "
        "assigned server-side, where the trust boundary is. It also "
        "means adding a value type adds no frontend code: the "
        "handlers live at the server's mount point, and the marker "
        "constructor is one line in the cljc file."]
       [:h2 "Authorization"]
       [:p "Two conventions complete the picture. A handler that "
        "rejects — no session, expired credentials — throws "
        [:code "(ex-info \"no session\" {:flowrpc/status 401})"]
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
        "token is usually a re-observation of answers the client was "
        "already served; and data that must not be readable at "
        [:em "any"] " t is excision's job."]
       [:h2 "CSRF"]
       [:p "Everything above assumes the request is one your user "
        "actually made. When authority rides an ambient session "
        "cookie, that assumption needs defending: any page your "
        "logged-in user visits can " [:code "fetch"] " your command "
        "endpoint, and the browser attaches the cookie because the "
        "request goes to " [:em "your"] " origin. The attacker never "
        "reads the response — CORS blocks that — but a "
        [:code "swap!"] " on your database already happened. That is "
        "cross-site request forgery: the request is authentic, its "
        [:em "origin"] " is not."]
       [:p "The backbone is a cookie attribute, not application code: "
        "set the session cookie " [:code "SameSite=Lax"] " (or "
        [:code "Strict"] ") and the browser stops attaching it to "
        "cross-site requests at all. It is the only control that also "
        "covers the query stream, because " [:code "EventSource"]
        " can send neither a custom header nor a content-type — so a "
        "token-header scheme, the usual CSRF defense, is structurally "
        "impossible for SSE. SameSite is what protects reads."]
       [:p "For commands, flowrpc adds one check on top: "
        [:code "handle-command"] " rejects any request that doesn't "
        "declare " [:code "content-type: application/transit+json"]
        " with a 415. The reason is a browser rule — a cross-site "
        [:code "fetch"] " may only send a browser-\"simple\" "
        "content-type (" [:code "text/plain"] ", form-urlencoded, "
        "multipart) without "
        "first sending a CORS preflight; a preflight for "
        [:code "transit+json"] " is one your server (having no "
        "permissive CORS policy) refuses. Requiring the transit "
        "content-type therefore forces every forger through a gate "
        "they cannot open. It is anti-forgery only — "
        [:strong "authorization is still the endpoint fn's job"] "."]
       [:p "That covers same-site cookie deployments, which is most. "
        "If yours must set " [:code "SameSite=None"] " (a cookie sent "
        "cross-site by design — embedded widgets, separate API "
        "origins), SameSite protects nothing and you add the classic "
        "layer in front of these handlers: an " [:strong "Origin"]
        " allowlist on state-changing requests, or a double-submit "
        "token. flowrpc leaves that to a middleware you mount, "
        "because the allowed origins are yours to name."]]}]}

   {:title "Testing"
    :pages
    [{:id    :jvm-testing
      :title "Rendering on the JVM"
      :prose
      [:<>
       [:p "The interpreter is cljc. In the browser it patches DOM; on "
        "the JVM the same components render into a sampled value — the "
        "tree as plain hiccup, kept current by the same rx graph. So "
        "components render and " [:em "react"] " in plain Clojure: "
        [:code "swap!"] " an atom and the tree updates fine-grained, "
        "exactly like the browser."]
       [:h2 "Snapshots"]
       [:p "The API is three consumers of that value, one FRP word "
        "each: " [:code "render"] " " [:em "holds"] " the tree flow "
        "running, " [:code "snapshot"] " " [:em "samples"] " it — the "
        "tree as hiccup at this instant, control flow collapsed to "
        "what's rendered, handler fns preserved in props as data — and "
        [:code "await"] " blocks for the first sample matching a "
        "predicate. " [:code "with-render"] " scopes teardown. Snapshots are standard hiccup, so " [:code "get-in"]
        ", " [:code "tree-seq"] " and matcher-combinators are the query "
        "language, and you fire an event by calling the handler you "
        "pulled out of a snapshot. Propagation is synchronous, so there "
        "is nothing to wait for."]
       [ui/code-block
        ";; the component under test — nothing test-specific about it,
;; the same code mounts in the browser
(defn counter [{:keys [start]}]
  (let [n (atom start)]
    [:div
     [:span (rx (? n))]
     [:button {:on-click (fn [_] (swap! n inc))} \"+\"]]))

(deftest counter-behaves
  (with-render [t [counter {:start 5}]]
    (is (match? [:div [:span 5] [:button {:on-click fn?} \"+\"]]
                (snapshot t)))
    ;; handlers are data: pull the fn out of the snapshot and call it
    (let [on-click (get-in (snapshot t) [2 1 :on-click])]
      (on-click :click))
    (is (= [:span 6] (nth (snapshot t) 1)))))"]
       [:h2 "Waiting on flows"]
       [:p "Trees fed by flows that emit from other threads — timers, "
        "requests — are the one async case, and " [:code "await"]
        " covers it: block until the tree satisfies a predicate, with a "
        "timeout. Suspense and errors are asserted the same way as "
        "anything else, because pending renders the fallback and an "
        "uncaught error re-throws from " [:code "snapshot"] "."]
       [ui/code-block
        "(deftest await-async-tree
  (let [tick< (m/ap (m/? (m/sleep 40 \"later\")))]
    (with-render [t [:span {:fallback \"...\"} (rx (? tick<))]]
      (is (= [:span \"...\"] (snapshot t)))
      (is (= [:span \"later\"]
             (fd/await t #(= [:span \"later\"] %) :timeout 1000))))))"]
       [:h2 "The tree is a flow"]
       [:p [:code "render"] " isn't special — it is one " [:em "consumer"]
        " of the interpreted tree, which is itself a missionary flow of "
        "hiccup. Consume it with raw missionary and you get every state "
        "the UI passes through; attach a second consumer to a render's "
        [:code ":tree"] " and nothing re-runs — rx blocks are shared, so "
        "consumers fan out from one running computation. "
        [:code "snapshot"] " and " [:code "await"] " are themselves "
        "implemented as exactly these flow operations; the only "
        "load-bearing thing " [:code "render"] " does is keep the "
        "refcounted tree alive, so samples join the running instance "
        "instead of mounting a fresh one."]
       [ui/code-block
        ";; every state of the UI, no render involved
(let [node (fd/interpret [:div [:span (rx (? n))]])]
  ((m/reduce (fn [_ tree] (record! tree) nil) nil node)
   (fn [_]) (fn [_])))   ;; never throw from process callbacks

;; a second consumer of a mounted tree — zero re-runs
(with-render [t view]
  (m/reduce log-state nil (:tree t))
  ...)"]
       [:p "The same property is a browser " [:em "dev mode"] ": "
        [:code "(dom/mount hiccup el {:spine? true})"] " attaches both "
        "consumers — the DOM is patched as usual " [:em "and"] " the "
        "handle carries " [:code ":tree"] ", the live UI as a flow, "
        "sampled by the " [:em "same"] " " [:code "snapshot"] " JVM "
        "tests use — the beginnings of devtools. The hiccup is "
        [:code "expand"] "ed "
        "first, so both walks share one instance of every static "
        "component; two renders of one expanded tree behave the same on "
        "the JVM:"]
       [ui/code-block
        "(deftest dual-walk-shares-component-state
  (let [expanded (fd/expand [counter {:start 5}])]
    (with-render [a expanded]
      (with-render [b expanded]
        ;; a handler fired through consumer A…
        (let [on-click (get-in (snapshot a) [2 1 :on-click])]
          (on-click :click))
        ;; …moves the tree consumer B sees: one instance, two views
        (is (= [:span 6] (nth (snapshot b) 1)))))))"]
       [:p "Caveat: components inside " [:em "dynamic"] " content (rx "
        "emissions, for-by bodies) are instantiated per consumer, so "
        "their local state isn't shared across views — ns-level state "
        "always is."]
       [:h2 "Full-stack tests"]
       [:p "Because the interpreter, " [:code "flowrpc.live"]
        " and the api namespaces are all cljc, the whole stack from "
        "component to database runs in one JVM process: the real "
        [:code "notes-view"] " (the notes UI — an input, an Add "
        "button and the live list, reading " [:code "all-notes<"]
        " like the demo panels), the real facade, the real live "
        "combinator, an in-memory Datomic. "
        "No HTTP server starts and nothing is mocked, so a failure "
        "means the logic is wrong — there is no mock to drift out "
        "of sync with the implementation. Driving the UI is the "
        "same snapshot mechanics as above; the one new piece is "
        "waiting, because the update comes back through the real "
        "tx-report stream — which is exactly what " [:code "await"]
        " is for:"]
       [ui/code-block
        ";; snapshots are plain hiccup, so helpers are ordinary seq code
(defn els [snap tag]
  (->> (tree-seq vector? seq snap)
       (filter #(and (vector? %) (= tag (first %))))))

(defn prop [snap tag k] (-> (els snap tag) first second k))
(defn note-texts [snap] (map last (els snap :li)))

(deftest live-ui-roundtrip
  (with-render [t [notes-view nil]]                 ;; nil = no anchor
    ((prop (snapshot t) :input :on-input) \"buy milk\")  ;; type
    ((prop (snapshot t) :button :on-click) :click)       ;; click Add
    (is (await t #(some #{\"buy milk\"} (note-texts %)) :timeout 3000)
        \"the note came back through the tx-report stream\")))"]
       [:p "The anchor works here the way it does everywhere else: "
        "render " [:code "[notes-view db]"] " against a db value "
        "and the test starts from a known state. And because dbs "
        "are values, 'the answer at t' is a plain function call — "
        [:code "(all-notes (d/as-of db t))"] " — no flow, no render "
        "lifecycle, no awaiting. A reproducible fixture is a value "
        "you keep."]]}]}])
