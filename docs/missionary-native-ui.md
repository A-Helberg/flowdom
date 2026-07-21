# A missionary-native UI library

A design for a fine-grained reactive UI library for Clojure and
ClojureScript, built directly on [missionary](https://github.com/leonoel/missionary).
No virtual DOM, no signal runtime borrowed from JavaScript, no
compiler. The UI is a tree of **slot processes**; what happens to a
slot's values — mutate the DOM, compose a data view of the whole
tree, or both — is decided by which **consumers** you attach at
mount. Cancellation is the entire lifecycle model.

This document assumes you know Clojure and roughly what a reactive UI
framework does. It introduces the missionary concepts it needs.

## The problem

A reactive UI library answers one question: when state changes, how
does the right piece of the page update — and nothing else?

The mainstream answers:

- **Virtual DOM** (React, Reagent): re-run the component, diff the
  output, patch. Simple model, pays a diff on every update.
- **Signals** (SolidJS, Vue, Svelte 5): state cells record who reads
  them; writes re-run exactly the computations that read them.
  Fine-grained, no diffing — but the runtime carries a dependency
  tracker, an ownership tree for cleanup, and a scheduler.
- **Functional reactive programming** (reflex, Electric): the UI is a
  composition of values-over-time ("flows" here, "behaviors" and
  "events" in FRP literature). The dataflow graph *is* the program.

Missionary is Clojure's structured-concurrency and dataflow library:
**tasks** (a value you'll get once) and **flows** (values over time),
composed as ordinary data, with two properties that matter enormously
for UI:

- **Continuous flows** model "a value that is always readable and
  changes over time." They propagate *invalidation* eagerly but
  *recompute* lazily, on transfer — consumers sample the latest value
  when they want it, and intermediate values nobody looked at are
  skipped. This is exactly the read discipline a renderer wants, and
  it is glitch-free by construction.
- **Supervision is structural.** Running a composed flow runs all its
  parts; cancelling it cancels all its parts, transitively, and
  teardown code runs in the right order. There is no separate
  lifecycle system to maintain.

So the thesis: use missionary as the reactive engine of a UI library,
and the ownership tree, the disposal registry, the scheduler, and the
async bridge layer of a signal framework all disappear — they are
what missionary already does. What remains to build is small, and
this document specifies it.

## The one hard problem: flow → value

Before the design, the obstacle. It is the reason signal runtimes
exist, and any flow-based design must answer it explicitly.

A flow is a value with a change protocol: it can tell you *when* it
changes. But you cannot call an ordinary function on one:

```clojure
(str "$" price<)   ;; price< is a flow — str sees the flow object, not the price
```

To compute with a flow's value you must be inside a flow context:

```clojure
(m/cp (str "$" (m/?< price<)))   ;; ⇒ a new flow of formatted strings
```

`m/?<` reads the current value and arranges for the enclosing block to
re-evaluate when it changes. But `m/cp` is a macro: the read only
works *lexically inside the block*. Move the logic into a helper
function and it breaks:

```clojure
(defn fmt-price [] (str "$" (m/?< price<)))  ;; ✗ throws — ?< can't cross a fn boundary
```

So every function touching a reactive value must take flows and
return flows — "function coloring." Your code stops being functions
over values and becomes explicit graph wiring.

There are exactly three known ways out:

1. **Lexical capture** — what `m/cp`/`m/?<` do. Doesn't cross
   function boundaries; this is the coloring problem itself.
2. **A compiler** — rewrite the whole language so every function
   boundary is transparent to the dataflow graph. This is Electric
   Clojure; it works, and it is a very large machine.
3. **Dynamic tracking** — a dynamic var holds "the currently running
   computation"; a read subscribes that computation to the flow and
   returns the current value; when the flow ticks, the runtime
   re-runs the computation *from scratch*. Restart-from-scratch is
   the only re-run semantics that makes sense (everything after the
   read consumed the stale value), so some delimiter must mark where
   restarts begin.

Option 3 is a signal runtime — but notice how little of one you need
if flows do everything else. You need a delimiter, a dynamic var, and
a subscription record. You do **not** need an ownership tree
(cancellation supervises), a disposal API (flow teardown), a
scheduler (continuous-flow conflation), or async bridges (everything
already is a flow).

That minimal kernel is the first thing we build.

## The design

### State sources: plain Clojure

No custom ref types. State is a Clojure atom; `(m/watch a)` is
already a continuous flow of its values. Event handlers are plain
functions that `swap!`. Streams come from `m/observe`
(callback-based sources), mailboxes (`m/mbx`), or any flow you
compose. IO is a task.

```clojure
(def route (atom :home))                 ;; state
(defn go! [r] (reset! route r))          ;; event handler — just a function
```

### The kernel: `rx` and `?`

`rx` is the restart delimiter. `?` is the universal read, valid only
inside `rx`. The result of `rx` is a **continuous flow** of the
block's values, deduplicated with `=`:

```clojure
(rx (str "$" (? price)))
;; ⇒ Flow of strings; re-evaluates when price changes
```

Semantics:

- `(? x)` accepts an atom, a flow, or another `rx`. It returns the
  current value and records the dependency. Reads work at any call
  depth — inside helper functions, inside `if` branches — because
  tracking rides a dynamic var, not the macro:

  ```clojure
  (defn fmt-price [] (str "$" (? price)))   ;; ✓ plain function
  (rx (fmt-price))                           ;; ✓ tracked through the call
  ```

- Dependencies are re-recorded on every run, so conditional reads
  subscribe only to what the current branch actually read.
- Reading a flow that has not yet produced a value **parks** the
  block: the `rx` flow is in its pending state (see Suspense below)
  until the dependency first emits.
- An `rx` is lazy and shared like any missionary flow: it runs while
  someone is consuming it and is torn down when its consumer cancels.
  There is no manual disposal anywhere in the API.

Implementation size: a dynamic var, a run function that swaps
subscription sets between runs, an `m/observe`-shaped wrapper.
Roughly two hundred lines, platform-neutral.

### Views: data with flows inside

Components are plain functions, called **once**, returning hiccup.
Reactivity does not re-run components; it lives in the flows embedded
in the data:

```clojure
(defn counter [{:keys [start]}]
  (let [n (atom start)]                        ;; local state: a let-bound atom
    [:div
     [:span (rx (? n))]                        ;; dynamic child: a flow
     [:button {:on-click (fn [_] (swap! n inc))} "+"]]))
```

The grammar:

- `[:tag props? & children]` — an element.
- `[component-fn props? & children]` — call the function once,
  interpret what it returns.
- A **flow** in a child or prop position marks a dynamic position.
  (`rx` returns a reified type the renderer recognizes, so there is
  no ambiguity with plain functions in props.)
- Anything else is static content.

Because components run once and return data, there is nothing special
about them: they compose like functions, test like functions, and the
renderer never needs to see "inside" one.

### Slots: the unit of the renderer

Mounting walks the data once. Static structure becomes nodes
directly. Each flow position becomes a **slot** — a running process
that owns everything that position means:

- A flow of scalars is a dynamic text node or attribute.
- A flow of **hiccup** makes the slot structural: each emission is
  interpreted recursively, and switching to new content **cancels the
  process tree of the old content**. Unmount, cleanup, and
  subscription disposal are all this one act of cancellation —
  missionary guarantees it reaches every descendant.
- Pending and error states (see below) are slot states.

Control flow needs no framework components: it is just flows emitting
different data.

```clojure
(rx (if (? logged-in)
      [profile-page]
      [login-form]))
;; the slot interprets whichever tree is current;
;; toggling cancels the old subtree's every process
```

Crucially, a slot only *produces values*. What happens with them is
not the slot's business — that is the consumers' job, and it is what
makes the rest of the design a set of configurations rather than a
set of forks.

### Consumers: attachable and dumb

Two consumers can be attached to slots at mount, independently:

- **The patch consumer** (browser): when a slot transfers a new
  value, patch *that slot's own DOM region* — the slot knows its
  location. Effects fire at slot transfer, so patching is exactly as
  fine-grained as the slots are. No diffing exists anywhere: nothing
  ever compares two trees, because the value that changed arrives at
  the place that renders it.
- **The spine consumer**: feed each slot's value into a pure
  `m/latest` composition mirroring the tree structure. The root of
  the spine is a continuous `Flow` of the whole UI as hiccup — the
  live page as an ordinary Clojure value you can sample at any
  moment.

The demand topology differs by configuration:

- **With a spine**, one root driver samples the spine once per
  animation frame whenever something invalidated. Missionary's
  transfer semantics recompute only the stale path: the dirty slot
  re-runs (its patch consumer, if attached, fires right there), the
  `m/latest` nodes on the path rebuild from cached children, and
  clean siblings are never touched. All effects for a frame fire
  inside one transfer over one consistent cut of the inputs —
  **transactional frames**; batching is the sampling discipline, not
  a scheduler feature.
- **Without a spine** (production), nothing would demand the slots,
  so each slot gets its own small consumer process, gated on the same
  animation-frame clock so batching behavior matches the spine
  configuration. The residual dev/prod difference is that a spine
  frame is one globally consistent cut while per-slot consumers cut
  per slot within the frame — small, but nonzero, and worth a
  tripwire test.

Sharing: a missionary flow feeds one consumer. When both consumers
are attached, each slot is wrapped in `m/signal` to fan out; in
single-consumer configurations no sharing is needed. Always wrapping
is a small constant cost that buys **attach-on-demand**: devtools can
subscribe a spine to a running production app and detach without
disturbing the patch consumers.

### Configurations

| | patch consumer | spine consumer | used for |
|---|---|---|---|
| JVM / tests | — | ✓ | `snapshot`/`await` sample the root; SSR |
| browser dev | ✓ | ✓ | devtools, live tree-as-data, frame debugging |
| browser prod | ✓ | — | no spine allocation, no cached tree values |

Everything that can be wrong — the kernel, the interpreter, `for-by`,
suspense — lives in the slot layer, which is identical in all three
columns. The consumers are deliberately too dumb to hide bugs: a
patch call, a `m/latest` feed. That is what makes a passing JVM test
say something about the browser, without a simulator and without a
parity suite.

### Suspense and errors: slot states, not magic

Async needs no dedicated primitives, because flows carry their own
lifecycle:

- A slot whose flow **has not yet emitted** is pending. It yields the
  position's fallback (so a pending child never stalls the spine or
  blanks the page) until first emission. That is the entirety of
  Suspense — no thrown promises, no boundary re-entry protocol.

  ```clojure
  [:div {:fallback [spinner]}
   (rx [:p "Hello, " (:name (? user))])]   ;; user: a flow fed by a request
  ```

- A slot whose process **fails** is an error at a position. An error
  boundary is a slot that catches its subtree's failure and renders
  the fallback with the error and a retry thunk (retry = re-spawn the
  subtree, which is just running the flow again — flows are recipes).

- A one-shot request is `(m/memo task)` or a small `latest` helper
  over a task — there is no separate "resource" type to learn.

### Keyed collections: `for-by`

The one genuinely hard piece of engineering, in this or any
fine-grained design. A flow emits a list; each item should render
once and update in place; reordering must **move** rendered items,
preserving their processes and local state, not rebuild them.

```clojure
[:ul (for-by :id (m/watch todos)
       (fn [todo]                          ;; todo: continuous flow of ONE item
         [:li (rx (:title (? todo)))]))]
```

Contract:

- `key-fn` identifies items across emissions.
- Body runs **once per key**, receiving a continuous flow of that
  item's latest value.
- New keys spawn subtrees; departed keys cancel them; reorders move
  DOM without touching processes; an item whose value changed ticks
  its item-flow only.
- **Spine duty**: the list's node in the spine must update its cached
  children vector incrementally — `assoc` at the changed index — not
  reassemble it. A naive `m/latest` over a thousand children turns
  one item's tick into an O(n) rebuild per frame; incremental update
  keeps spine work at O(depth) with log-time fanout.

Internally this is an incremental diff of keyed sequences driving
process management — the analog of Electric's `e/for-by` and reflex's
`listWithKey`. Budget it as much effort as everything else in this
document combined.

## Testing

The rendered tree is a behavior — a value that exists at every moment
— and the spine reifies it, so the test vocabulary is stock
missionary rather than a test framework:

- `snapshot` **samples the root flow**: the UI as hiccup at this
  instant, handler fns preserved in props as data.
- `tree-flow` is not a helper; it *is* the root flow.
- `await` is `m/reduce`-until-predicate with a timeout — the async
  assertion for trees fed by timers, requests, or other threads.

```clojure
(deftest counter-behaves
  (with-render [t [counter {:start 5}]]
    (is (match? [:div [:span 5] [:button {:on-click fn?} "+"]]
                (snapshot t)))
    ((get-in (snapshot t) [2 1 :on-click]) :click)   ;; handlers are data
    (is (= [:span 6] (nth (snapshot t) 1)))))

(deftest clock-renders-eventually
  (with-render [t [clock]]
    (await t #(= [:span "12:00"] %) :timeout 1000)))
```

Because the spine is a mount configuration rather than a test
harness, the same sampling works in a dev browser build — the live
page as data in a REPL — and against production via
attach-on-demand.

## What to build, in order

| Piece | Size | Notes |
|---|---|---|
| `rx` / `?` kernel | small | dynamic tracking emitting continuous flows; ~200 lines, `.cljc` |
| slot layer + interpreter | medium | the renderer core; recursive slots, switch-cancels-subtree |
| spine consumer + `snapshot`/`with-render`/`await` | small | JVM configuration; enables TDD on everything after |
| patch consumer + frame clock | medium | DOM mutation at slot transfer; event props; `m/signal` fan-out |
| suspense / error boundaries | small | falls out of slot states |
| `for-by` | **large** | keyed incremental collections, including the incremental spine duty |
| SSR | small | mount spine-only, sample once, serialize |

The spine lands before the patch consumer deliberately: the whole
slot layer is then developed against sampled-tree tests, and the
browser work is reduced to attaching a second, dumb consumer.

## Costs and assumptions

Relative to wrapping an existing framework, this design gives up a
battle-tested browser runtime, compiled templates, event delegation,
and an ecosystem. Its own costs:

- **Spine work** is O(dirty-path depth) per frame, plus fanout at the
  changed node — which is why `for-by` must update incrementally, and
  why production runs spine-off. Patch-consumer updates themselves
  are leaf-fine-grained with no diffing, comparable to SolidJS;
  mounting pays hiccup interpretation, comparable to Reagent.
- **Spine memory** is the cached latest value at every composition
  node — proportional to UI size. This is the other half of the
  spine-off production default.
- **A load-bearing assumption to verify first**: that missionary's
  continuous-flow transfer (`m/latest`, `m/cp`) re-samples only
  inputs that signaled change and retains the rest. Everything above
  rests on it; the first prototype should prove it with a counter
  before anything else is built. Relevant history: Electric runs on
  missionary and still built dedicated work-skipping and
  incremental-sequence machinery rather than leaning on raw `latest`
  composition — a hint to measure spines early, not late.
- **Dev/prod divergence** is confined to frame-cut granularity
  (global cut with a spine, per-slot cuts without). Both
  configurations share the frame clock precisely to keep this the
  *only* difference; a tripwire test asserting identical rendered
  results across configurations is cheap insurance.

## Prior art

Nearest relatives, worth studying before building:

- **reflex / reflex-dom** (Haskell): the same architecture — data
  with dynamics embedded, keyed collection combinators, monadic
  builder where we use processes.
- **Electric Clojure**: missionary underneath a whole-language
  compiler; its `e/for-by` is the reference for ours. This design is
  roughly Electric minus the compiler and the network, made viable by
  the `rx` kernel standing in for the compiler at function
  boundaries.
- **SolidJS**: the fine-grained rendering discipline (components run
  once, granularity from where you place reactive expressions) — kept
  here; its signal runtime and ownership tree — replaced by
  missionary.

The summary that fits in one sentence: the UI is a tree of slot
processes; `rx` turns plain Clojure into flows; attach a patch
consumer and it renders, attach a spine and it is data; cancellation
is the entire lifecycle model.

## Implementation status

The design above is implemented in `lib/flowdom` (`flowdom.rx`,
`flowdom.core`, `flowdom.dom`), with these v1 divergences:

- **Propagation is synchronous, not frame-batched.** Both consumers
  patch/emit on the emitting call stack: when `swap!` returns, the
  DOM (or spine value) is current. This is what the tests rely on;
  the raf frame clock is future work.
- **The spine is rx-composition, not raw `m/latest`.** Element
  assembly, slots, `for-by`, and boundaries are all rx blocks reading
  child nodes through `?`. Because `?` cells persist across re-runs
  keyed by source identity, this bought the design's hardest property
  for free: `for-by` preserves item processes across reorders on the
  spine too, without a dedicated incremental combinator. Cost: the rx
  kernel recomputes eagerly on invalidation rather than lazily on
  transfer.
- **The three configurations exist; the switch is coarser than the
  spec.** JVM mounts the spine (`flowdom.core/render`, whose handle
  exposes `:tree` — the interpreted tree, itself a missionary flow;
  `render` is just one consumer of it). Browser prod mounts the patch
  consumer (`flowdom.dom/mount`). Browser dev is
  `(dom/mount hiccup el {:spine? true})`: both consumers at once, the
  handle carrying `:tree`, sampled by the same cross-platform
  `snapshot`. `snapshot` and `await` are themselves derived from the
  flow (a first-transfer sample and a reduce-until-predicate) — the
  named consumers are conveniences, not primitives; only `render`'s
  keeper is load-bearing, holding the refcounted tree alive so samples
  join a running instance instead of mounting a fresh one. It is implemented as two
  walks over one `flowdom.core/expand`-ed tree rather than one slot
  layer with attachable consumers: `expand` pre-runs every statically
  reachable component so both walks share one instance (rx blocks are
  `m/signal`-shared, so computations run once and fan out). Caveat:
  components inside dynamic content (rx emissions, for-by bodies) are
  instantiated per walk, so their local state is not shared across
  consumers — ns-level state always is. The single-walk
  slot-plus-consumers refactor remains the end state.
- **Never throw from missionary process callbacks.** An exception
  escaping a subscriber callback mid-propagation can wedge shared
  propagation state process-wide (everything mounts, nothing updates).
  flowdom's own callbacks swallow cancellation; test code consuming
  `:tree` directly must do the same.
- **`?` subscribes by flow identity.** Flows must be created outside
  rx bodies (or memoized): a fresh flow object built inside a
  re-running body resubscribes forever and never settles. The
  tutorial's async section teaches this as "the one rule of async".
- **Pending and errors are values end to end**, as specified:
  per-position `:fallback` for pending, `:error-boundary` with
  heal-on-dependency-change and remount-on-retry for errors.
