# AGENTS.md

flowdom: fine-grained reactive UI library for Clojure/ClojureScript. Built on missionary. No virtual DOM, no diffing, no compiler. UI = tree of missionary processes. Cancellation = whole lifecycle model. Experimental; API unstable.

Design rationale: `docs/missionary-native-ui.md`. Tutorial: `example/` app.

## Mental model

Three rules. Internalize before touching code.

1. Components = plain functions. Run once, return hiccup (data).
2. Dynamic positions = `(rx ...)` blocks inside hiccup. Inside block, `(? src)` reads atom, missionary flow, or other rx — any call depth, through helper functions. Read subscribes block. Change re-runs block, patches only own DOM region. Propagation synchronous: `swap!` returns, DOM already updated.
3. Everything = missionary flow. rx is flow. Mount subscribes; unmount cancels every process in subtree. Timers, SSE, websockets: any flow plugs in via `(rx (? my-flow))`, gets setup + teardown free.

```clojure
(ns app.core
  (:require [flowdom.dom :as dom]
            [flowdom.rx :refer [rx ?]]))

(defn counter []
  (let [n (atom 0)]
    [:button {:on-click (fn [_] (swap! n inc))}
     "clicks: " (rx (? n))]))

(dom/mount [counter] (js/document.getElementById "app"))
```

Control flow = data: rx emitting different hiccup swaps subtree. Keyed lists: `for-by`. Pending async: nearest `:fallback` renders. Errors: travel as values to nearest `:error-boundary`. React/Reagent interop: `flowdom.react`, `flowdom.react.reagent`.

## Layout

    lib/flowdom/          library: rx kernel, interpreter, DOM consumer
      src/flowdom/core.cljc   interpreter, snapshot, await, with-render
      src/flowdom/rx.cljc     rx, ?
      src/flowdom/dom.cljc    mount, DOM consumer
      src/flowdom/react.cljs  React bridge
    lib/flowdom-docs/     guide chrome: sidebar shell, code blocks
    lib/flowrpc/          SSE query/command transport: live server reads, tokens
    example/              interactive guide, itself written in flowdom
    docs/                 design document

## Consume as dependency

No versioned release. Git deps only, pinned to release tag or sha. Each lib = subdirectory; point `:deps/root` at it:

```clojure
{:deps {io.github.a-helberg/flowdom
        {:git/url   "https://github.com/A-Helberg/flowdom"
         :git/sha   "…"            ; commit on main
         :deps/root "lib/flowdom"}
        ;; optional, SSE query/command transport:
        io.github.a-helberg/flowrpc
        {:git/url   "https://github.com/A-Helberg/flowdom"
         :git/sha   "…"
         :deps/root "lib/flowrpc"}}}
```

Compile with shadow-cljs (`:deps true`). flowdom: zero JS deps. Optional bridges need peers you install: `flowdom.react` needs `react` + `react-dom`; `flowdom.react.reagent` needs `reagent`.

## Server push (flowrpc)

Server query = plain missionary flow. Client reads directly: `(rx (? (chat/messages)))`. Transport: SSE + transit, auto reconnect, no server-side connection state. Writes = plain POST commands. Rationale + API: `lib/flowrpc/README.md`.

## Test without browser

Same components render on JVM. Tree = sampled value, not DOM. Handlers included, as data:

```clojure
(with-render [t [counter]]
  ((get-in (snapshot t) [1 :on-click]) :click)
  (is (= "1" (str (nth (snapshot t) 3)))))
```

`await` blocks until tree satisfies predicate — use for flows emitting from other threads. Helpers live in `lib/flowdom/src/flowdom/core.cljc`; examples in `lib/flowdom/test/`.

## Rules for agents

- Missionary pinned at `b.47` (beta, API shifts between releases). Same exact version across every lib. Never bump via floating range; upgrade deliberately, all libs in lockstep.
- Components run once. Never put reads outside `(rx ...)` and expect updates. Dynamic = rx block, static = plain hiccup.
- `(? ...)` valid only inside rx (any call depth). Reads atom, flow, or rx.
- Prefer JVM tests (`with-render`, `snapshot`, `await`) over browser tests. Faster, no DOM needed.
- Keyed lists: `for-by`, not bare `for` — bare seq gets no keyed patching.
- Guide in `example/` doubles as e2e suite. New feature: teach it there, node-test covers it.
