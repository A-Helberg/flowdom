# flowdom

> **Experimental.** A from-scratch rewrite; APIs are unstable.

A fine-grained reactive UI library for Clojure and ClojureScript,
built directly on [missionary](https://github.com/leonoel/missionary).
No virtual DOM, no diffing, no compiler, no borrowed signal runtime.
The UI is a tree of missionary processes; cancellation is the entire
lifecycle model.

The design rationale lives in
[docs/missionary-native-ui.md](docs/missionary-native-ui.md); the
tutorial with live examples is the app in `example/`.

## The mental model

Three rules:

1. **Components are plain functions** that run once and return hiccup — data.
2. **Dynamic positions are `(rx ...)` blocks** embedded in that data.
   Inside one, `(? src)` reads an atom, a missionary flow, or another
   rx — at any call depth, through ordinary helper functions — and
   subscribes the block. A change re-runs the block and patches only
   its own DOM region. Propagation is synchronous: when `swap!`
   returns, the DOM is updated.
3. **Everything is a missionary flow.** An rx *is* a flow; mounting
   subscribes, unmounting cancels every process in the subtree.
   Timers, SSE, websockets — anything expressible as a flow plugs in
   with `(rx (? my-flow))` and gets setup and teardown for free.

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

Control flow is data (an rx emitting different hiccup swaps the
subtree), keyed lists are `for-by`, pending async renders the nearest
`:fallback`, and errors travel as values to the nearest
`:error-boundary`. React and Reagent components mount inside the tree
(`flowdom.react`), and `lib/flowrpc/` streams live server reads over
SSE — both taught in the guide.

## Testing without a browser

The same components render on the JVM, where the tree is a sampled
value instead of DOM — handlers included, as data:

```clojure
(with-render [t [counter]]
  ((get-in (snapshot t) [1 :on-click]) :click)
  (is (= "1" (str (nth (snapshot t) 3)))))
```

`await` blocks until the tree satisfies a predicate, for flows that
emit from other threads. See `lib/flowdom/test/`.

## Layout

    lib/flowdom/          the library (rx kernel, interpreter, DOM consumer)
    lib/flowdom-docs/     the guide's chrome (sidebar shell, code blocks)
    lib/flowrpc/          SSE query/command transport (live reads, tokens)
    example/              the interactive guide, itself written in flowdom
    docs/                 design document

## Compatibility

flowdom is built on [missionary](https://github.com/leonoel/missionary)
`b.47`, which is itself a beta: its API can shift between releases.
flowdom pins that exact version across every lib; upgrade missionary
deliberately, in lockstep, not via a floating range.

## Using it in your project

No versioned release yet — depend on the libs as git deps, pinned to a
release tag or sha. Each is a subdirectory of this repo, so point
`:deps/root` at it:

```clojure
{:deps {io.github.a-helberg/flowdom
        {:git/url   "https://github.com/A-Helberg/flowdom"
         :git/sha   "…"            ; a commit on main
         :deps/root "lib/flowdom"}
        ;; optional, for the SSE query/command transport:
        io.github.a-helberg/flowrpc
        {:git/url   "https://github.com/A-Helberg/flowdom"
         :git/sha   "…"
         :deps/root "lib/flowrpc"}}}
```

Compile with [shadow-cljs](https://github.com/thheller/shadow-cljs)
(`:deps true`). flowdom itself has no JS dependencies. The optional
bridges pull peers you install yourself: `flowdom.react` needs
`react` + `react-dom`, and `flowdom.react.reagent` needs `reagent`.

## Working on flowdom itself

    task bootstrap                        # toolchain (mise) + JS deps

    cd example
    npx shadow-cljs watch app             # guide at http://localhost:1380

    cd lib/flowdom
    clojure -M:test                       # JVM test suite

    cd example
    npx shadow-cljs compile node-test
    node out/node-tests.js                # browser-consumer + guide e2e (happy-dom)
