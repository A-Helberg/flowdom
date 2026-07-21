# Missionary (flowdom) vs solid-js (main branch)

What this branch trades by building the reactive engine on missionary
(`lib/flowdom`) instead of wrapping solid-js (`lib/solidclj` on main).
Both designs share the surface philosophy — components are plain
functions called once, granularity comes from where you put reactive
expressions — so the comparison is about everything underneath.

The two architectures, in one paragraph each:

- **main**: on CLJS, `solidclj.runtime` re-exports real solid-js;
  `solidclj.hiccup` (~900 lines) walks hiccup into `solid-js/h` calls.
  On the JVM, `runtime.clj` (~600 lines) is a hand-written simulator
  of Solid's semantics — signals, effects, owners, cleanup — kept
  honest by parity fixtures run on both platforms. Async crosses a
  dedicated bridge, `solidclj.missionary` (~900 lines): `hold`,
  `resource`, `spawn!`, plus a naming policy to keep two vocabularies
  from colliding.
- **this branch**: `flowdom.rx` (~220 lines) is the whole reactive
  kernel — an `rx` block re-runs when anything it read through `?`
  changes, and emits a shared missionary continuous flow.
  `flowdom.core` interprets hiccup into a flow of the whole tree (the
  JVM/test renderer); `flowdom.dom` attaches DOM patching to the same
  grammar. One implementation, both platforms, no bridge, no
  simulator.

## Pros

**One implementation instead of an implementation and a simulator.**
This is the strongest argument. On main, a passing JVM test proves the
*simulator* behaves; whether the browser does too rests on the parity
suite, and `runtime.clj` openly documents divergences (unordered
propagation, diamond re-runs, no Suspense timing). Here the rx kernel,
the interpreter, `for-by` semantics, pending, and error boundaries are
the same `.cljc` code on both platforms; only the thin patch layer in
`flowdom.dom` is browser-specific. A JVM snapshot test exercises the
code that ships.

**No async bridge, because async is the substrate.** Main needed ~900
lines (`solidclj.missionary`) to move values between missionary and
Solid, two lifecycle models to reconcile, and a naming policy to stop
`watch`/`signal`/`dispose!` from meaning two things. Here a flow *is*
a reactive source: `(? some-flow)` inside an rx, done. Server data,
websockets, timers, and UI state are one vocabulary end to end.

**Cancellation is the entire lifecycle model.** Solid carries an
ownership tree, `onCleanup` registries, and a scheduler; main's
simulator had to reimplement all of it. In flowdom, unmounting a
subtree is cancelling its flow, and missionary's structured
supervision guarantees teardown reaches every descendant. There is no
disposal API to call and none to forget.

**No JavaScript dependency.** No npm install, no solid version drift,
no ESM/CJS interop hacks (main's `runtime.cljs` has to unwrap
`solid-js/h`'s `.default` differently per bundler), no externs
concerns, and the JVM side needs no JS runtime at all.

**Plain atoms, uniform reads.** Main introduced `satom`, a custom
reactive atom type bridged into Solid's tracking. Here state is a
stock `clojure.core/atom`; `?` accepts atoms, flows, and rx values
alike. Less API, and event handlers are just functions that `swap!`.

**The live tree is a value.** The spine — the whole UI as a
continuous flow of hiccup — enables `snapshot`/`await` against a
*running* app, cross-platform, with handler fns preserved as data.
Main's snapshot existed only against the JVM simulator; here the same
`snapshot` works on a browser dev mount.

**Roughly a third of the code.** ~1000 lines of flowdom source
replace ~2900 lines of solidclj source plus the parity apparatus.
Less to hold in your head, less to maintain.

## Cons

**You gave up a battle-tested renderer, and it shows.** solid-js has
years of production hardening; `flowdom.dom` is new and visibly
incomplete. Concretely, today: `mount-element` calls `createElement`,
never `createElementNS`, so SVG does not render; `set-prop!` is ~30
lines and won't know about the long tail of DOM property quirks Solid
handles; there is no event delegation (one `addEventListener` per
handler per element); there is no hydration — main had
`render-to-string` and `hydrate` from `solid-js/web`, and flowdom's
SSR story stops at serializing a snapshot. Every one of these is
fixable, and every fix is on you, verified only by your own ~500
lines of tests instead of Solid's suite and its ecosystem of users.

**Propagation is not glitch-free.** Solid orders memo updates
topologically and batches, so a computation never observes an
inconsistent cut of its inputs. flowdom's v1 rx recomputes eagerly
and unordered on the emitting call stack: in a diamond (a feeds b and
c; d reads both), a change to `a` re-runs `d` with new-b/old-c before
re-running it again — and the intermediate state can reach the DOM.
On main this flaw existed only in the JVM simulator, where it was
harmless for tests; here it ships. The design doc's answer
(frame-batched sampling, transactional cuts) is specified but not
built.

**Restart semantics have a sharp edge Solid doesn't.** `?` subscribes
by source identity, so a flow constructed *inside* an rx body is a
fresh object every re-run: it resubscribes forever and never
settles. The failure mode is an app that spins, not an error message.
Solid's `createMemo` inside a component is owned and safe by
construction. "Create flows outside rx bodies" is teachable, but it
is a rule users must carry, and the penalty for forgetting it is
severe.

**A stray exception can wedge the world.** An exception escaping a
missionary subscriber callback mid-propagation can corrupt shared
propagation state process-wide — everything mounts, nothing updates.
flowdom's own callbacks defend against this, but any consumer of
`:tree` must know to as well. Solid degrades more locally.

**JVM concurrency is thinner than it looks.** Runs are serialized by
a per-rx lock and propagation happens synchronously on the emitting
thread — so an emission holds locks down the dependency path. Two
threads emitting into a diamond can acquire those locks in opposite
orders. Nothing in the test suite exercises contended multi-threaded
graphs; treat the JVM story as "safe for tests," not "safe for
concurrent server rendering," until proven otherwise.

**Performance is asserted, not established.** Solid compiles templates
to near-optimal DOM operations; flowdom interprets hiccup at runtime,
brackets every slot with comment markers, spawns a process per
dynamic prop, wraps every rx in `m/signal`, and dedupes every
emission with `=` — which is O(size) on large hiccup values, paid per
tick. The DOM `for-by` checks departed keys with a linear scan (O(n²)
per emission) and moves ranges without a longest-increasing-
subsequence pass, so reorders do more DOM motion than Solid's
reconciler. The spine caches the latest value at every node — memory
proportional to UI size — which is why prod runs spine-off, creating
its own dev/prod divergence. `perf_flowdom.cljs` exists; numbers
don't yet. Notably, Electric — the closest prior art, also on
missionary — found raw flow composition insufficient and built
dedicated work-skipping machinery; assuming flowdom won't need the
same is optimism, not evidence.

**The dev/prod seam moved, it didn't disappear.** Main's divergence
was simulator-vs-solid, policed by a parity suite. Here dev mounts
two walks over one `expand`ed tree, and component state inside
dynamic content (rx emissions, for-by bodies) is instantiated per
walk — the spine can silently disagree with the DOM about such
state. Smaller seam, but subtler, and currently without a tripwire
test.

**Missionary is a hard dependency in every sense.** Small community,
dense semantics, notoriously opaque failure modes (a hung transfer
gives you a stack trace into the machinery, not your code). Solid has
large docs, devtools, and Stack Overflow. Anyone debugging flowdom's
internals must first be fluent in missionary's protocol — a real
hiring/onboarding cost for a library meant to be built on.

**The ecosystem is gone.** Wrapping solid-js left the door open to
its component ecosystem and utilities. flowdom is compatible with
nothing pre-existing; every control, router, and integration starts
from zero (as `solidreitrouter` already shows).

## Bottom line

The missionary approach wins decisively on architecture: one small
cross-platform implementation with cancellation-as-lifecycle and
async built in beats a wrapper plus a simulator plus a bridge plus a
parity suite. What it costs is everything solid-js had already paid
for — renderer completeness, glitch-freedom, scheduling, performance
engineering, ecosystem — and those bills come due incrementally, as
apps hit SVG, diamonds, big lists, and multi-threaded servers. The
honest framing: this branch trades a maintenance problem (keeping two
runtimes in agreement) for an engineering problem (finishing and
hardening one runtime). The second problem is the better one to have,
but it is not the smaller one.
