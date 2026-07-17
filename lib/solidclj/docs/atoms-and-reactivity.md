# Atoms and reactivity in solidclj

SolidJS does not re-run component functions on state change. Reactive state
lives in **`s/atom`** (`solidclj.api/atom`, implemented in `solidclj.satom`):
a real Clojure atom — `swap!`, `reset!`, `add-watch`, validators all behave
exactly like `cljs.core/atom` — whose deref *also* subscribes the Solid
tracking scope it runs in.

The whole model is one rule: **reactivity is a deref inside a thunk.** The
walker never interprets refs — an atom in the tree is just a value, and a
deref outside a tracking scope is just a snapshot. Custom reference types
participate in tracked derefs by implementing `solidclj.satom/IReactiveAtom`
(plus `IDeref` + `IWatchable`).

## Two patterns

**1. Deref inside a `(fn [] …)` thunk** — the thunk re-runs when the atom changes:

```clojure
(:require [solidclj.api :as s])

(defonce temp (s/atom 21))

(fn [] [:p "it is " @temp "°C"])
```

**2. Bare deref under the `h` macro** — `@temp` reads as `(deref temp)`, a
list form, so `h` wraps it in its own thunk. This works in child slots *and*
prop values (except `:on*`/`:ref`, which are callbacks):

```clojure
(h [:p "it is " @temp "°C"])       ; text node updates in place
(h [:input {:value @temp}])        ; live prop
```

Everywhere a slot wants a reactive value — `:when`, `:each`, `:component`,
`:mount`, prop values, style/class entries — pass an accessor: a zero-arg fn
that derefs inside. `h` writes exactly those for you.

```clojure
[:show {:when (fn [] @on?)} …]
[:for {:each (fn [] @items)} render-fn]
[:dynamic {:component (fn [] @tag)}]
```

`s/?` still exists for missionary flows (`(s/? some-flow)` → getter fn) and
also accepts an s/atom, where it is equivalent to a deref.

## Pitfalls

**`@atom` outside any thunk is a snapshot.** Component bodies are not
tracking scopes (unlike Reagent, the component fn runs once), so a bare
deref at the top level of a component captures the value at mount:

```clojure
(defn c []
  [:div @temp])          ; frozen at mount — deref ran in the component body

(defn c []
  [:div (fn [] @temp)])  ; live — deref runs inside the thunk

(defn c []
  (h [:div @temp]))      ; live — h moves the deref into a thunk for you
```

**A bare atom in a slot renders its printed representation.** `[:div temp]`
puts `#<SAtom: 21>` in the DOM and warns in dev builds — a visible bug, not
a silent one. The same goes for plain `cljs.core/atom`s; the renderer treats
all refs alike.

## Quick reference

```
(fn [] @my-satom)     live — deref subscribes the thunk
(h [:p @my-satom])    live — h auto-wraps derefs (children and props)
@my-satom             outside a thunk: snapshot, captured once
my-satom              bare in a slot: printed representation, dev warning
(s/? some-flow)       missionary flow → returns getter fn
plain (atom …)        same as any ref — never reactive in the tree
```
