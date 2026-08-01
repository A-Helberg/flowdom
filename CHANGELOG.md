# Changelog

All notable changes to the flowdom stack (flowdom, flowrpc,
flowdom-docs). This project has not cut a versioned release yet; the
entries below track the work leading to `0.0.1`.

## Unreleased

### flowdom
- Fine-grained reactive UI on missionary: `rx` kernel, hiccup
  interpreter, DOM consumer, and a JVM consumer (`render` / `snapshot`
  / `await`) that renders the same components without a browser.
- `flowdom.rx/effect` — run a flow for its side effects, lifetime =
  subscription.
- `:on-mount` element prop: the connected DOM node, with an optional
  teardown thunk (flowdom's take on refs).
- React and Reagent bridges (`flowdom.react`, `flowdom.react.reagent`):
  props follow the same atom/rx read rule as everywhere else.
- `dom/mount` options: `:on-error (fn [e remount!])` root error hook,
  and `:schedule :frame` for batched (coalesced, per-region) patches;
  `:sync` remains the default.
- DOM surface: SVG (`createElementNS`), listener options
  (`{:handler … :capture … :passive … :once …}`), an extended
  property-vs-attribute policy, custom-element object properties, and
  IME-safe controlled inputs.
- Unified pending/fallback semantics (in-place, inherited) and the
  `for-by` contract (distinct keys, pending → fallback) across the DOM
  and JVM consumers.
- Churn detection + a runaway circuit-breaker for the
  flow-built-inside-an-rx footgun.
- `flowdom.rx/hold` — share a cold flow as an rx: N readers over one
  subscription (one SSE connection for a flowrpc query), pending until
  the first value, optional immediate `initial`.

### flowrpc
- Client ported to flowdom: a query is a cold missionary flow read
  directly with `(rx (? q))`; renamed `flowrpc.call.solidjs` →
  `flowrpc.client`.
- Errors (server exception, decode failure, closed EventSource) fail
  the query flow to the nearest `:error-boundary` instead of only
  logging.
- `flowrpc.client/unresolved` — sentinel for followed refs whose value
  isn't known yet (≠ nil = resolved to nothing): while a followed ref
  holds it the query emits nothing, so loading holds and a query whose
  argument derives from another query's answer is never asked at its
  initial value.
- Security: `handle-command` requires `application/transit+json`
  (CSRF); `handle-query` / `handle-command` reject over-large /
  over-deep payloads with 413 before parsing (`:max-bytes` /
  `:max-depth`).

### Naming
- `solidrpc` → `flowrpc`, `solidclj-docs` → `flowdom-docs`, wire tag
  `#solid/db` → `#flowdom/db`, maven group `solidjclj` →
  `io.github.a-helberg`.
