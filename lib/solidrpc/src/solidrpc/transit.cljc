(ns solidrpc.transit
  "Transit encode/decode for the wire, with an extension point for
  app-registered handlers.

  The main use: database values. A database is a value; its wire
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

  nil crosses unchanged and means 'now' by convention — the consumer
  (solidrpc.live) resolves a nil anchor to the current db.

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

(defn write [data]
  #?(:clj  (let [out (ByteArrayOutputStream.)
                 w   (t/writer out :json {:handlers @write-handlers*})]
             (t/write w data)
             (.toString out "UTF-8"))
     :cljs (do (when (nil? @writer*) (rebuild-cljs!))
               (t/write @writer* data))))

(defn read [s]
  #?(:clj  (t/read (t/reader (if (string? s)
                               (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
                               s)
                             :json
                             {:handlers @read-handlers*}))
     :cljs (do (when (nil? @reader*) (rebuild-cljs!))
               (t/read @reader* s))))
