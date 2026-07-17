# solidclj

> **Not production ready.** APIs are unstable and several rough edges remain.

ClojureScript bindings for [SolidJS](https://www.solidjs.com/) — Reagent-style hiccup syntax over SolidJS's fine-grained reactivity, with a Missionary interop layer and a thin SSE transport for backend-driven streams.

See the **[Interactive Docs](https://a-helberg.github.io/solidclj/)** (code in the example folder) for a full walkthrough with live examples.

---

## The one rule

In Reagent the component function re-runs on state changes. In SolidJS it runs **once**. Wrap dynamic regions in `(fn [])` so SolidJS knows what to track:

```clojure
(defonce temp (s/atom 21))

(defn thermometer []
  [:div
   [:p "mounted at: " @temp "°C"]   ; snapshot — never updates
   (fn [] [:p "now: " @temp "°C"])]) ; reactive thunk — updates on swap!
```

---

## Getting started

Depend on the library as a git dep, pinned to a release tag. A minimal [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) project is four files. The app itself, `src/app/core.cljs`:

```clojure
(ns app.core
  (:require [solidclj.api :as s]))

(defonce clicks (s/atom 0))

(defn counter []
  [:button {:onClick #(swap! clicks inc)}
   (fn [] [:span "clicks: " @clicks])])

(defn init []
  (s/render [counter] (.getElementById js/document "app")))
```

And three files of scaffolding:

<details>
<summary><code>deps.edn</code></summary>

```clojure
{:paths ["src"]
 :deps  {thheller/shadow-cljs {:mvn/version "2.28.17"}
         io.github.a-helberg/solidclj
         {:git/url   "https://github.com/A-Helberg/solidclj"
          :git/tag   "v0.0.1"
          :git/sha   "xxxxxxx"  ; git rev-parse --short v0.0.1
          :deps/root "lib/solidclj"}}}
```

</details>

<details>
<summary><code>shadow-cljs.edn</code></summary>

```clojure
{:deps true          ; resolve dependencies via deps.edn
 :dev-http {8080 "public"}
 :builds
 {:app {:target  :browser
        :modules {:app {:init-fn app.core/init}}}}}
```

</details>

<details>
<summary><code>public/index.html</code></summary>

```html
<!doctype html>
<html>
  <head>
    <meta charset="utf-8">
    <title>solidclj app</title>
  </head>
  <body>
    <div id="app"></div>
    <script src="/js/app.js"></script>
  </body>
</html>
```

</details>

SolidJS itself comes from npm. Install it and start the watcher:

```sh
bun add solid-js             # or: npm install solid-js
bun add -d shadow-cljs
bunx shadow-cljs watch app   # http://localhost:8080
```

---

## Monorepo layout

```
lib/solidclj          Core: hiccup walker, s/atom, Missionary bridge
lib/solidrpc          SSE transport: Manifold stream → SolidJS signal
lib/solidreitrouter   Thin reitit wrapper for client-side routing
lib/solidclj-docs     Shared UI for the docs / example app
example/              Reference app and interactive tutorial
```

---

## Toolchain

- **ClojureScript** via [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
- **Bun** for JS deps and dev server
- **Taskfile** — see `Taskfile.yml` at root and per-lib
- **mise** for tool version pinning (`mise.toml`)

---

## License

Copyright © 2026 Andre Helberg

Distributed under the [Eclipse Public License 2.0](LICENSE).
