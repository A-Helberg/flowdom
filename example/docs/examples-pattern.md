# The live-examples docs pattern

How to build a docs/tutorial site where every code sample is shown next to its
**live, running result** — and the displayed source can never drift from the
running code, because they are the same file.

This document describes the pattern as implemented in a ClojureScript +
shadow-cljs + SolidJS project, but every piece has an equivalent in other
stacks; adaptation notes are inline. Give this file to whoever (or whatever)
is building the docs app for a new project.

## The idea

**Each example lives in its own source file, which is both compiled into the
app (so it runs) and inlined as a string at build time (so it displays).**

```clojure
;; pages registry
{:source    (rc/inline "frontend/examples/counter.cljs")  ;; string at compile time
 :component counter/example}                              ;; the same file, compiled
```

There is no copy of the code in the docs, no markdown code fence to update, no
"sync the snippet" chore. If the example is refactored, the displayed code and
the behavior change together in the same commit. If the example stops
compiling, the docs app stops building — broken samples cannot ship.

Compile-time string inlining exists almost everywhere:

| Stack                | Mechanism                                    |
|----------------------|----------------------------------------------|
| shadow-cljs          | `(shadow.resource/inline "path/from/classpath")` (also tracks the file for hot reload) |
| Vite                 | `import source from './counter.tsx?raw'`     |
| webpack              | `raw-loader` / `asset/source`                |
| Rust                 | `include_str!("counter.rs")`                 |
| Anything else        | a build step that reads the file into a constant |

If the language allows it, prefer a mechanism the bundler tracks, so editing
an example hot-reloads both the running component and the displayed source.

## Architecture: four pieces

```
src/frontend/
├── examples/          one file per example — the payload
│   ├── counter.cljs
│   ├── suspense.cljs
│   └── …
├── pages.cljs         the registry: sections → pages → prose + examples (pure data)
├── ui.cljs            building blocks: button, code-block, example-block
└── app.cljs           shell: sidebar nav, hash routing, page renderer
```

### 1. Example files (`examples/*.cljs`)

One file per example. Rules that keep them good teaching material:

- **The whole file is displayed** — namespace declaration, imports, comments,
  everything. So the file must be honest and minimal: nothing in it that
  isn't part of the lesson, and nothing needed for the lesson missing from it.
- **Each file exports one entry point** (here: a nullary `example` component
  the registry mounts). Everything else in the file exists to support it.
- **Comments in the example are part of the prose.** Use them for the line
  the reader's eye should pause on:

  ```clojure
  ;; hold is lazy too: the flow starts when this page first derefs it and
  ;; is cancelled when the last subscriber unmounts. Navigate away and
  ;; back — it restarts from 0, defonce notwithstanding.
  (defonce seconds (sm/hold ticks :initial 0))
  ```

- **Shared cosmetic components are allowed** (a styled `ui/button`,
  `ui/input`) so examples focus on the concept being taught instead of CSS
  classes. The reader sees `[ui/button {:on-click …} "+1"]` and understands;
  they don't need forty utility classes. Keep the shared vocabulary tiny.
- **Module-level state uses `defonce`** (or your stack's hot-reload-stable
  equivalent) so live-editing an example doesn't reset its state.
- If a demo needs to fake honesty (e.g. our React-vs-Solid perf demo stamps
  each dot with the render pass that produced it, because React's diffing
  hides render work), say so in the page prose. Never let a demo imply
  something the code doesn't do.

### 2. The registry (`pages.cljs`) — pure data

The entire information architecture is one literal data structure:
sections → pages → prose + example blocks. No logic lives here.

```clojure
(def sections
  [{:title "Reactivity"
    :pages
    [{:id    :satom                       ;; stable id → used as the URL hash
      :title "Reactive atoms — s/atom"
      :prose
      [:<>
       [:p "Prose is hiccup (or JSX/HTML), not markdown — so it can use "
        [:code "inline code"] ", links, emphasis…"]]
      :examples
      [{:title     "Optional block title"   ;; omit for single-example pages
        :source    (rc/inline "frontend/examples/satom.cljs")
        :component satom/example}]}
     …]}
   …])
```

Notes:

- A page may have **multiple examples** (e.g. "the mechanism" + "the same
  thing applied") — each gets its own code/result block with an optional
  block title.
- Pages that don't fit the prose+examples mold (a landing page with a big
  demo) can supply a raw `:body` instead; the page renderer branches on it.
- **Order pages pedagogically and enforce it**: nothing may use a construct
  before the page that introduces it. When adding a page, check what its
  example imports and place it after those pages.
- Because the registry is data, tests can walk it (see Testing below).

### 3. UI building blocks (`ui.cljs`)

The one that matters is `example-block`: **code on the left, live result on
the right** (stacked on narrow screens), visually one unit:

```clojure
(defn example-block
  [{:keys [title source component]}]
  [:section {:class "mt-8"}
   (when title [:h2 {:class "text-lg font-semibold mb-3"} title])
   [:div {:class "grid grid-cols-1 xl:grid-cols-2 border rounded-lg overflow-hidden"}
    [:div {:class "bg-gray-950 min-w-0"}
     [code-block source]]                                   ;; dark code panel
    [:div {:class "p-5 bg-white min-w-0 border-t xl:border-t-0 xl:border-l"}
     [:div {:class "text-[11px] uppercase tracking-wider text-gray-400 mb-3"} "result"]
     [component]]]])                                        ;; the LIVE component
```

`code-block` does syntax highlighting at render time with highlight.js
(core build + just the one language grammar, to keep the bundle small),
injecting the highlighted markup via the framework's raw-HTML prop:

```clojure
(:require ["highlight.js/lib/core" :as hljs]
          ["highlight.js/lib/languages/clojure" :as clojure-lang])
(.registerLanguage hljs "clojure" clojure-lang)

(defn code-block [source]
  [:pre {:class "m-0 p-4 bg-gray-950 text-xs overflow-x-auto"}
   [:code {:class     "hljs language-clojure"
           :innerHTML (.-value (.highlight hljs source #js {:language "clojure"}))}]])
```

(Any highlighter works — Shiki, Prism… If the build step can highlight at
compile time instead, even better.)

### 4. The shell (`app.cljs`)

Deliberately boring:

- **Sidebar**: iterate the registry — section headings, page links,
  highlight the current page.
- **Routing**: the page id is the URL hash (`#satom`). Read it on load,
  write it on navigation, scroll to top. No router library — a docs site
  with a data registry doesn't need one.
- **Page renderer**: title, prose (typography-styled container, e.g.
  Tailwind's `prose` class), then one `example-block` per entry.

## Testing

Two cheap tests keep the whole site trustworthy:

1. **Smoke test: render every page.** Walk the registry, mount each page in
   a headless DOM (we use happy-dom under node), assert the page title lands
   in the output and that highlighted `<span>`s exist in code blocks. This
   catches broken examples, broken registry entries, and load-order mistakes
   in one loop — and new pages are covered automatically because the test
   walks the data:

   ```clojure
   (deftest every-page-renders
     (doseq [page (mapcat :pages pages/sections)]
       (let [root (fresh-root)]
         (render [page-view page] root)
         (is (.includes (.-textContent root) (:title page))))))
   ```

2. Unit tests for any nontrivial site machinery (we have one for the
   DOM-update flash observer described below).

Because examples are real compiled code, the smoke test actually **executes
every example** — a sample that throws on mount fails CI.

## Optional extras (adapt or skip)

- **Flash-on-change**: a global `MutationObserver` that briefly outlines any
  DOM node that changes (like Chrome DevTools paint flashing), with a sidebar
  toggle. For a reactivity library this *is* documentation — readers see
  exactly which nodes update. Details that matter if you build one: alternate
  between two identical animation classes to restart the animation without
  forcing reflow; filter out the observer's own class mutations; and blame
  precisely (on child-list changes flash inserted nodes, flash the container
  only for pure removals) or components will look like they re-render when
  they don't.
- **Landing-page comparison demo** with the sources in collapsible
  `<details>` blocks rather than example-blocks — a pitch, not a lesson.
- **Styling**: Tailwind + the typography plugin covers prose nicely. Scan
  your source files so utility classes used in examples are emitted.

## Replication checklist

1. Pick the compile-time source-inlining mechanism for your stack.
2. Create `examples/` — one file per lesson, one exported entry point each,
   whole file written to be read.
3. Create the registry: sections → pages → `{prose, [{title?, source,
   component}]}`, pure data, ids stable for URLs.
4. Build `example-block` (code left, live result right) and a highlighted
   `code-block`.
5. Build the shell: registry-driven sidebar, hash routing, page renderer.
6. Add the smoke test that walks the registry and renders every page.
7. House rules: nothing used before it's taught; examples self-contained and
   honest; shared UI vocabulary minimal; state hot-reload-stable.
