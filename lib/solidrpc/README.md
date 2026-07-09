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
SSE is server-to-client only. If you need low-latency client-to-server messaging (multiplayer cursors, collaborative editing), WebSockets are the right tool. For the query/command pattern — where reads are streamed and writes are one-shot — SSE is strictly simpler and more scalable.

---
## Key design decisions

**Attribute-based filtering** — each query declares which DB attributes it watches. Transactions that don't touch those attributes are ignored, so unrelated writes don't cause unnecessary re-runs.

**Debounce with max-wait** — a 200 ms quiet window collapses rapid writes (bulk import, mass update) into one query re-run. A 2 s ceiling prevents stale UI during sustained writes.

**Result deduplication** — SSE only pushes when the query result actually changed. A transaction that touches watched attributes but produces the same result generates no network traffic.

**Explicit registry whitelist** — functions must be registered at startup via `solidrpc.registry/register!`. The server looks up only from that map; no dynamic namespace loading, no `requiring-resolve` on user-supplied symbols. An unknown name returns 404 whether it exists in the codebase or not.

**Lazy subscriptions** — the EventSource only opens when a component mounts. Navigating away disposes the signal and closes the connection.

---

## License

Copyright © 2026 Andre Helberg

Distributed under the [Eclipse Public License 2.0](LICENSE).
