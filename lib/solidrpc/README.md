# rpc

Seamless backend–frontend reactivity over SSE. A thin transport layer that connects a Manifold stream on the server to a SolidJS signal on the client, with transit encoding, automatic reconnect, and no shared state.

---

## Rationale

### Why SSE instead of WebSockets?

WebSockets are the obvious choice for push — but SSE fits this pattern better in almost every way.

**HTTP/1.1 connection limits kill WebSockets at scale.**
Browsers cap concurrent connections per origin. Under HTTP/1.1 each WebSocket upgrade consumes one of those slots permanently. Open enough sockets and you starve other requests. SSE shares the same HTTP connection pool but doesn't block it the same way, and under HTTP/2 and HTTP/3 it multiplexes over a single connection with no per-stream cap.

**QUIC connection reuse.**
HTTP/3 runs over QUIC, which multiplexes streams over a single UDP connection with independent flow control per stream. An SSE response is just a long-lived HTTP stream — it gets QUIC's head-of-line-unblocking, connection migration, and 0-RTT reconnect for free. A WebSocket upgrade steps outside the HTTP layer and gets none of this.

**SSE load balances naturally; WebSockets fight it.**
A WebSocket is a long-lived, stateful, bidirectional pipe to one specific server. Sticky sessions or a message broker are required to route client messages back to the right connection. SSE is unidirectional — the server only pushes. Each SSE connection is an ordinary HTTP request and can land on any backend node. Writes go through a normal POST, which also routes freely. The two directions are decoupled, and the load balancer can do its job without coordination.

**SSE reconnects to any backend — deploys just work.**
When a WebSocket drops, the client must reconnect to the same server (or that server's state must be replicated). SSE has no server-side connection state — the backend is a stream of values derived from the database. On reconnect the client opens a new SSE request, any node picks it up, re-runs the query against the current DB value, and the client is back in sync. Replication not required.

This makes rolling deploys transparent. When a backend instance is replaced, its SSE connections drop. Clients reconnect automatically — with exponential backoff — and land on a new instance. Because the new instance has no state to restore, it immediately serves the current DB value. From the user's perspective the UI blinks once and catches up. No drain period, no session handoff, no coordination between old and new instances.

**Writes are already HTTP.**
Commands (writes) are plain POSTs. There is no benefit to funnelling them through the same socket as pushes. Keeping them as independent requests means they get retried, routed, and traced like any other HTTP call.

**The tradeoff.**
SSE is server-to-client only. If you need low-latency client-to-server messaging (multiplayer cursors, collaborative editing), WebSockets are the right tool for now; when WebTransport (bidirectional streams over QUIC) matures, that choice is worth revisiting. For the query/command pattern — where reads are streamed and writes are one-shot — SSE is strictly simpler and more scalable.

---
## Key design decisions

**Attribute-based filtering** — each query declares which DB attributes it watches. Transactions that don't touch those attributes are ignored, so unrelated writes don't cause unnecessary re-runs.

**Debounce with max-wait** — a 200 ms quiet window collapses rapid writes (bulk import, mass update) into one query re-run. A 2 s ceiling prevents stale UI during sustained writes.

**Result deduplication** — SSE only pushes when the query result actually changed. A transaction that touches watched attributes but produces the same result generates no network traffic.

**Explicit registry whitelist** — functions must be registered at startup via `solidrpc.registry/register!`. The server looks up only from that map; no dynamic namespace loading, no `requiring-resolve` on user-supplied symbols. An unknown name returns 404 whether it exists in the codebase or not.

**Lazy subscriptions** — the EventSource only opens when a component mounts. Navigating away disposes the signal and closes the connection.

---

## Live reads

`solidrpc.live` is the standard shape for read endpoints. Backend authors write **pure functions of a database value** — `(f db) → shaped, authorized data` — and pass real db values around; on a Datomic peer they are cheap. `live` lifts such a function into a flow that starts from the db you hand it and re-emits whenever a transaction changes the answer. Views hold the flow; the wire carries only endpoint-shaped results.

### The serialization boundary

`solidrpc.transit` provides the general mechanism: **server values, client refs, exchanged at the wire.** There is one handler mechanism — handlers are supplied as opts where you mount the rpc handlers — and the client needs none at all, because refs are generic: a `Ref` record writes out under its own `:tag`, and any incoming tag without a handler reads back as a `Ref`. A server-minted value arrives as `(->Ref "solid/db" {:basis-t 1010})` — plain data, value equality, round-trips — and a client-constructed marker is just `(transit/ref "app/viewer" {})`. `nil` crosses unchanged and carries no meaning at this layer; what a missing value means (now? anonymous? an error?) is your domain's decision, made in your endpoint fns and handler reps where it's visible.

The db value is the worked example. A db cannot ship its data, so its wire form is `#solid/db {:basis-t 1010}` — a basis-t is all a peer needs — and the incoming ref resolves back to an **actual db value** (`d/as-of`), so endpoint fns receive databases, never refs. Handlers that only need startup context are built once, closing over the conn, as plain data:

```clojure
(def transit-handlers
  {:read-handlers  {transit/db-tag (fn [{:keys [basis-t]}]
                                     (cond-> (d/db conn) basis-t (d/as-of basis-t)))}
   :write-handlers {datomic.db.Db {:tag transit/db-tag
                                   :rep (fn [db] {:basis-t (d/basis-t db)})}}})
```

The server does not restrict which t a client may name. The trust boundary is the query fn — authorize against the *present*, read domain data at t — a ref is usually a re-observation of answers the client was already served, and data that must not be readable at *any* t is excision's job.

### Handlers at the mount point

Everything above is handed to solidrpc where you mount the handlers. Your router fn has the request in scope, so a value type that can only be reconstructed *with the request* — a current user from a session, whatever the session mechanism is — is a handler closing over it, sitting in the same map as the startup-scoped ones. A read handler runs while the incoming args decode, and its return value becomes the argument the endpoint fn receives in place of the ref. The mount point becomes the app's whole value vocabulary, per request, and solidrpc never learns whether reconstruction means a JWT, a server-side session store, or a db lookup:

```clojure
["/api/query"
 {:get (fn [req]
         (rpc/handle-query req
           (update transit-handlers :read-handlers assoc
                   "myapp/user" (fn [rep]
                                  (sessions/user-for
                                   (get-in req [:cookies "sid" :value]) rep)))))}]
```

`handle-command` takes the same opts. `:write-handlers` (`{type {:tag … :rep …}}`) covers the outgoing direction and rides the SSE stream's closures, so per-request encoding holds for the connection's lifetime.

A rejecting handler signals its status through ex-data — `(throw (ex-info "no session" {:solidrpc/status 401}))` — and the response carries it, so consumers can tell an invalid session from a server error.

One consequence to design for: decode runs once per request, and an SSE connection can be long-lived. Reconstruct **identity** at the edge and derive **authorization** from the db inside the query fn — `(fn [db] (visible-notes db (d/entity db user-id)))` re-reads roles from every `db-after`, so open streams tighten on the transaction that revokes, not at reconnect.

### Anchors

`live`'s db argument is the flow's **lower bound**: the flow starts from the anchor and immediately catches up to the current db (latest-wins), so consumers see the freshest answer and never anything older than what they hold. Queries anchored to the same value start from the same point in time; a command response carrying the post-transaction db gives read-your-writes by construction. `live` passes the anchor to `f` exactly as given — nil included; a facade whose convention is "nil means now" substitutes the current db itself before calling.

**A fixed point in time needs no flow at all.** An as-of view is an immutable value, so "the answer at t" is plain function application: `(all-notes (d/as-of db t))`. Render against a fixed value and nothing ever updates — which is what SSR and JVM test fixtures consume.

### The env

`live` is storage-agnostic — wire your store as two things:

```clojure
{:db      (fn [] current-db-value)   ;; nil-anchor + catch-up reads
 :reports flow-of-tx-reports}        ;; maps with :db-after (+ whatever :relevant? inspects)
```

Share ONE running report flow per connection (`m/stream`) — Datomic's report queue semantics require it, and it gives the whole chain hold-style lazy/refcounted lifecycle.

### Efficiency

1. **`dedupe` is built in** — a transaction that doesn't change `f`'s answer emits nothing. Correctness backstop; saves bandwidth.
2. **`:relevant?`** — a predicate on the tx-report, checked *before* re-running `f` (e.g. "did this tx touch a `:note/*` attribute"). Opt-in deliberately: a missed dependency in a relevance predicate is a correctness bug, so the default re-runs on every report and lets dedupe suppress.
3. **Cross-client sharing** of identical (endpoint, args) flows: wrap the result in `m/stream` when fan-out costs show up. Not `live`'s job.

### The facade convention

The api namespace colocates the pure fn with its `<`-suffixed facade, registered under its own symbol — the client queries the same var whose `:clj` branch produces the flow, so the two sides cannot drift apart. Facades return flows; views hold them at point of use:

```clojure
(ns api.notes
  (:require #?@(:clj  [[datomic.api :as d]
                       [server.notes :as store]
                       [solidrpc.live :as live]]
                :cljs [[solidrpc.call.solidjs :as call]])))

#?(:clj
   (defn all-notes [db] ...))          ;; pure — call it with any as-of view

(defn all-notes< [db]                  ;; < says flow; db is the anchor
  #?(:clj  (live/live store/env (or db ((:db store/env)))   ;; facade convention: nil means now
                      all-notes :relevant? note-tx?)
     :cljs (call/query `all-notes< db)))
```

```clojure
;; in a view — hold at point of use
(let [notes< (sm/hold (notes/all-notes< db) :initial [])] ...)
```

Calling a read runs nothing — flows are recipes. No connection opens until a component renders the hold; unmounting closes it. Flow-returning endpoints are adapted to SSE automatically by `solidrpc.server/handle-query`.

### Properties

Components become pure functions of a db anchor, and the same component:

- runs **live in the browser** over SSE;
- renders **on the JVM without HTTP or mocks** — real facade, real flow, real database (see the example's `frontend.notes-view-test`: drive the UI through snapshot handlers, watch the answer come back through the tx-report stream);
- and fixed points in time are just function calls — "the answer at t" as a value you keep, for fixtures and SSR.

---

## License

Copyright © 2026 Andre Helberg

Distributed under the [Eclipse Public License 2.0](LICENSE).
