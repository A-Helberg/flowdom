# solidclj

> **Not production ready.** APIs are unstable, there is no versioned release, and several rough edges remain.

ClojureScript bindings for [SolidJS](https://www.solidjs.com/) — Reagent-style hiccup syntax over SolidJS's fine-grained reactivity, with a Missionary interop layer and a thin SSE transport for backend-driven streams.

See the **[interactive tutorial](example/)** (run the example app) for a full walkthrough with live examples.

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

An un-deref'd `s/atom` in a child or prop slot is auto-bridged (equivalent to the thunk above):

```clojure
[:span temp]    ; live
[:span @temp]   ; snapshot
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
