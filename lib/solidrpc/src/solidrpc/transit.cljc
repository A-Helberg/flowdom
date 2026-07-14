(ns solidrpc.transit
  "Transit encode/decode for the wire, with an extension point for
  app-registered handlers: server values, client refs, exchanged at
  the serialization boundary.

  Handlers come in two scopes. Request-independent ones (anything
  reconstructible from startup context, like a db resolver closing
  over the conn) go in the registry via register-read-handler! /
  register-write-handler!. Request-dependent ones (a user from a
  session, however sessions work) are supplied per call through the
  :handlers opt on read/write — solidrpc.server threads them from the
  opts you pass at the rpc mount point, where your router fn has the
  request in scope to close over.

  The main use so far: database values. A database is a value; its wire
  form is a tiny ref — #solid/db {:basis-t 1010} — because a basis-t
  is all a peer needs to reconstitute the value. The exchange happens
  HERE, at the serialization boundary, so it is invisible to
  application code:

  - server → wire: an app-registered write handler turns a db value
    into the ref (e.g. datomic.db.Db → {:basis-t (d/basis-t db)})
  - wire → client: the ref reads as a DbRef record — an opaque token
    components pass around like any value (value equality, so arg
    deduplication and satom =-gating just work)
  - client → wire: DbRef writes back as the same tag
  - wire → server: an app-registered read handler resolves the ref
    back to an ACTUAL db value (d/as-of), so endpoint fns receive
    databases, never refs

  nil crosses unchanged, and no meaning is assigned to it here — write
  handlers dispatch on type and read handlers on tag, so nil never
  reaches a handler. What a missing value means is the consumer's
  decision, made where it is visible: a facade may substitute the
  current db for a nil anchor, a resolver may treat {:basis-t nil} as
  now, another domain may reject nil outright — all consumer-side
  choices, none imposed by this namespace.

  Without app registration the default read handler on both platforms
  yields the DbRef record itself, so the lib works standalone."
  (:refer-clojure :exclude [read write])
  (:require [cognitect.transit :as t])
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

(defrecord DbRef [basis-t])

(def db-tag "solid/db")

(defonce ^:private write-handlers*
  (atom {DbRef (t/write-handler (fn [_] db-tag) (fn [r] (into {} r)))}))

(defonce ^:private read-handlers*
  (atom {db-tag (t/read-handler map->DbRef)}))

#?(:cljs (defonce ^:private writer* (atom nil)))
#?(:cljs (defonce ^:private reader* (atom nil)))

#?(:cljs
   (defn- rebuild-cljs! []
     (reset! writer* (t/writer :json {:handlers @write-handlers*}))
     (reset! reader* (t/reader :json {:handlers @read-handlers*}))))

(defn register-write-handler!
  "Register a write handler: values of `type` serialize as `tag` with
  (rep-fn value) as their representation. E.g. the server side of
  db-as-value:

      (register-write-handler! datomic.db.Db db-tag
                               (fn [db] {:basis-t (d/basis-t db)}))"
  [type tag rep-fn]
  (swap! write-handlers* assoc type (t/write-handler (fn [_] tag) rep-fn))
  #?(:cljs (rebuild-cljs!))
  nil)

(defn register-read-handler!
  "Register a read handler for `tag`. E.g. the server side of
  db-as-value — resolving the ref back to an actual database value:

      (register-read-handler! db-tag
                              (fn [{:keys [basis-t]}]
                                (cond-> (d/db conn) basis-t (d/as-of basis-t))))"
  [tag f]
  (swap! read-handlers* assoc tag (t/read-handler f))
  #?(:cljs (rebuild-cljs!))
  nil)

(defn- read-handler-map
  "The registry, with per-call `handlers` ({tag (fn [rep] …)}) merged
  on top. Per-call handlers win on tag collision and never touch the
  registry."
  [handlers]
  (if (seq handlers)
    (merge @read-handlers*
           (into {} (map (fn [[tag f]] [tag (t/read-handler f)])) handlers))
    @read-handlers*))

(defn- write-handler-map
  "The registry, with per-call `handlers`
  ({type {:tag \"…\" :rep (fn [v] …)}}) merged on top."
  [handlers]
  (if (seq handlers)
    (merge @write-handlers*
           (into {} (map (fn [[type {:keys [tag rep]}]]
                           ;; force invocation: transit treats a non-fn
                           ;; rep as a constant, which would silently
                           ;; write keywords like :sid literally
                           [type (t/write-handler (fn [_] tag) (fn [v] (rep v)))]))
                 handlers))
    @write-handlers*))

(defn write
  "Encode `data`. Opts:
    :handlers  {type {:tag \"…\" :rep (fn [v] …)}} — per-call write
               handlers, merged over the registry. Supply these at the
               rpc mount point for request-scoped encoding."
  ([data] (write data nil))
  ([data {:keys [handlers]}]
   #?(:clj  (let [out (ByteArrayOutputStream.)
                  w   (t/writer out :json {:handlers (write-handler-map handlers)})]
              (t/write w data)
              (.toString out "UTF-8"))
      :cljs (if (seq handlers)
              (t/write (t/writer :json {:handlers (write-handler-map handlers)}) data)
              (do (when (nil? @writer*) (rebuild-cljs!))
                  (t/write @writer* data))))))

(defn read
  "Decode `s`. Opts:
    :handlers  {tag (fn [rep] …)} — per-call read handlers, merged
               over the registry. Supply these at the rpc mount point,
               closing over the request (session lookup, verification,
               whatever reconstruction means for that value type)."
  ([s] (read s nil))
  ([s {:keys [handlers]}]
   #?(:clj  (t/read (t/reader (if (string? s)
                                (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
                                s)
                              :json
                              {:handlers (read-handler-map handlers)}))
      :cljs (if (seq handlers)
              (t/read (t/reader :json {:handlers (read-handler-map handlers)}) s)
              (do (when (nil? @reader*) (rebuild-cljs!))
                  (t/read @reader* s))))))
