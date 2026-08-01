# flowdom

Fine-grained reactive UI for Clojure and ClojureScript, built directly
on [missionary](https://github.com/leonoel/missionary). No virtual DOM,
no diffing, no compiler, no borrowed signal runtime — the UI is a tree
of missionary processes, and cancellation is the whole lifecycle model.

This is the core library. See the [repo README](../../README.md) for
the mental model and the design doc, and `example/` for the interactive
guide.

## The three rules

1. **Components are plain functions** that run once and return hiccup.
2. **Dynamic positions are `(rx …)` blocks.** Inside one, `(? src)`
   reads an atom, a missionary flow, or another rx — at any call depth
   — and subscribes the block. A change re-runs the block and patches
   only its own DOM region. Propagation is synchronous by default.
3. **Everything is a missionary flow.** An rx *is* a flow; mounting
   subscribes, unmounting cancels. `(rx (? my-flow))` plugs any flow
   into the tree with setup/teardown for free.

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

## Namespaces

| ns | what |
|----|------|
| `flowdom.rx` | the kernel: `rx` / `rx*`, the universal read `?`, `effect` (run a flow for its side effects, lifetime = subscription), `hold` (share a cold flow: any number of readers, one subscription, pending until the first value) |
| `flowdom.core` | the interpreter — hiccup+rx → a value tree — and its JVM consumers: `render`, `snapshot`, `await`, `with-render`, `for-by`, `expand` |
| `flowdom.dom` | the browser consumer: `mount` (CLJS only) |
| `flowdom.react` | render React components in the tree (`component`, `el`) — needs `react` + `react-dom` as peer deps |
| `flowdom.react.reagent` | the same for Reagent (separate ns, so `flowdom.react` needs no reagent) — needs `reagent` |

The core (`flowdom.rx` / `.core` / `.dom`) has no JS dependencies; only
the optional bridges above pull peers, and only if you require them.

## Grammar

```clojure
[:tag props? & children]        element (props map optional)
[component-fn & args]           call the fn once, interpret the result
(rx …)                          dynamic position (child or prop value)
(for-by key-fn items body)      keyed collection
[:<> & children]                fragment (no wrapper element)
[:portal {:mount el} & kids]    render elsewhere, stay in the process tree
[:error-boundary {:fallback (fn [err retry] …)} child]
```

- **Props**: static values captured at mount; `(rx …)` / atom values
  are read and re-render in place; `:on*` values are handlers (a fn, or
  `{:handler f :capture … :passive … :once …}` for listener options);
  `:on-mount` `(fn [el] …)` runs with the connected DOM node and may
  return a teardown thunk.
- **Pending**: a position whose rx has no value yet renders the nearest
  inherited `:fallback`, in place — surrounding structure stays live.
- **Errors**: a throwing rx emits an error value that travels up to the
  nearest `:error-boundary`; a dependency change heals the subtree in
  place, `retry` remounts it.

## `mount` options

```clojure
(dom/mount hiccup el {:on-error (fn [e remount!] …)
                      :schedule :frame})
```

- **`:on-error`** — the root error hook: any error no `:error-boundary`
  caught lands here instead of the console. `remount!` tears the whole
  mount down and rebuilds it (deferred to a microtask) — the
  root-level retry.
- **`:schedule`** — `:sync` (default; a `swap!` has patched the DOM by
  the time it returns) or `:frame` (updates coalesce per region, latest
  wins, and flush once per animation frame; initial mounts stay
  synchronous). A `(fn [flush!])` is a custom scheduler, for tests.
- **`:spine?`** — dev mode: patch the DOM *and* keep the live tree as a
  sampled value; returns `{:dispose … :tree …}` where `:tree` is
  `snapshot`-able, the same fn JVM tests use.

## Testing without a browser

The same components render on the JVM, where the tree is a sampled
value — handlers included, as data:

```clojure
(with-render [t [counter]]
  ((get-in (snapshot t) [1 :on-click]) :click)
  (is (= "1" (str (nth (snapshot t) 3)))))
```

`await` blocks until the tree satisfies a predicate, for flows that
emit from other threads. `clojure -M:test` runs the suite.

## License

Copyright © 2026 Andre Helberg. [Eclipse Public License 2.0](LICENSE).
