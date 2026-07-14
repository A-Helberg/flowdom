(ns solidrpc.transit
  "Transit encode/decode for the wire: server values, client tokens,
  exchanged at the serialization boundary.

  There is ONE handler mechanism: pass them where you call read/write
  — in practice, the opts you hand solidrpc.server's handlers at the
  rpc mount point, where your router fn has the request in scope to
  close over. Handlers that only need startup context (a db resolver
  closing over the conn) are the same thing built once and reused;
  request-dependent ones (a user from a session, however sessions
  work) are closures over the request. Nothing registers anything by
  side effect.

  The client needs no handlers at all, because tokens are generic: a
  token writes out under its own tag, and any incoming tag without a
  handler reads back as a token. So a server-minted value arrives as
  (token \"solid/db\" {:basis-t 1010}) — value equality, round-trips
  — and a client-constructed marker is just (token \"app/viewer\").
  This opacity is deliberate: the client never interprets a token's
  contents, so the server can change a rep without touching clients,
  and meaning is only ever assigned server-side. A token is a tagged
  pair: a tag naming the value type, a rep carrying what the server
  needs to rebuild it. Construct with `token`, inspect with
  `token?`/`token-tag`/`token-rep`; the concrete type differs per
  platform (a record on the JVM, transit's own TaggedValue on cljs,
  where the decoder hard-codes TaggedValue for unknown composite
  tags), which the accessors hide.

  The worked example: database values. A db cannot ship its data, so
  the server writes it as #solid/db {:basis-t t} (a type-dispatched
  write handler) and resolves incoming tokens back to actual db
  values (a tag-dispatched read handler) — both supplied at the
  mount point; see the example app's server.notes + server.core.

  nil crosses unchanged, and no meaning is assigned to it here —
  handlers dispatch on type and tag, so nil never reaches one. What a
  missing value means is the consumer's decision, made where it is
  visible."
  (:refer-clojure :exclude [read write])
  (:require [cognitect.transit :as t]
            #?(:cljs [com.cognitect.transit.types :as ty]))
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
                   [com.cognitect.transit DefaultReadHandler])))

#?(:clj (defrecord Token [tag rep]))

;; transit-cljs extends IEquiv onto its UUID/Long types but not
;; TaggedValue; tokens need value equality (follow-args dedupes args
;; with =), so we extend it here. ILookup mirrors the JVM record's
;; keys, so (get-in tok [:rep :basis-t]) reads the same on both
;; platforms.
#?(:cljs
   (extend-type ty/TaggedValue
     IEquiv
     (-equiv [this other]
       (and (t/tagged-value? other)
            (= (.-tag this) (.-tag ^js other))
            (= (.-rep this) (.-rep ^js other))))
     IHash
     (-hash [this] (hash [(.-tag this) (.-rep this)]))
     ILookup
     (-lookup
       ([this k] (-lookup this k nil))
       ([this k not-found]
        (case k
          :tag (.-tag this)
          :rep (.-rep this)
          not-found)))
     IPrintWithWriter
     (-pr-writer [this writer _opts]
       (-write writer (str "#solidrpc/token [" (.-tag this) " "
                           (pr-str (.-rep this)) "]")))))

(defn token
  "A tagged token: plain data denoting a server value. Writes out
  under `tag`; the server's mount-point read handler for that tag
  reconstructs the value."
  ([tag] (token tag {}))
  ([tag rep] #?(:clj  (->Token tag rep)
                :cljs (t/tagged-value tag rep))))

(defn token? [x]
  #?(:clj  (instance? Token x)
     :cljs (t/tagged-value? x)))

(defn token-tag [tok]
  #?(:clj (:tag tok) :cljs (.-tag ^js tok)))

(defn token-rep [tok]
  #?(:clj (:rep tok) :cljs (.-rep ^js tok)))

(def db-tag "solid/db")

#?(:clj
   (def ^:private token-write-handler
     ;; a Token writes under its own tag (cljs needs nothing: transit
     ;; writes TaggedValue natively)
     (t/write-handler (fn [tok] (:tag tok)) (fn [tok] (:rep tok)))))

;; …and any unhandled tag reads back as a token. On the JVM that
;; takes a DefaultReadHandler; on cljs it is transit's native
;; behavior (unknown composite tags decode to TaggedValue
;; unconditionally — the decoder consults no default handler for
;; them).
#?(:clj
   (def ^:private default-read
     (reify DefaultReadHandler
       (fromRep [_ tag rep] (->Token tag rep)))))

(defn- read-handler-map [handlers]
  (into {} (map (fn [[tag f]] [tag (t/read-handler f)])) handlers))

(defn- write-handler-map [handlers]
  (into #?(:clj {Token token-write-handler} :cljs {})
        (map (fn [[type {:keys [tag rep]}]]
               ;; force invocation: transit treats a non-fn rep as a
               ;; constant, which would silently write keywords like
               ;; :sid literally
               [type (t/write-handler (fn [_] tag) (fn [v] (rep v)))]))
        handlers))

#?(:cljs (def ^:private default-writer (t/writer :json)))
#?(:cljs (def ^:private default-reader (t/reader :json)))

(defn write
  "Encode `data`. Opts:
    :handlers  {type {:tag \"…\" :rep (fn [v] …)}} — write handlers
               for this call, e.g. a db value's type → its token
               form. Supply them at the rpc mount point."
  ([data] (write data nil))
  ([data {:keys [handlers]}]
   #?(:clj  (let [out (ByteArrayOutputStream.)
                  w   (t/writer out :json {:handlers (write-handler-map handlers)})]
              (t/write w data)
              (.toString out "UTF-8"))
      :cljs (if (seq handlers)
              (t/write (t/writer :json {:handlers (write-handler-map handlers)}) data)
              (t/write default-writer data)))))

(defn read
  "Decode `s`. Opts:
    :handlers  {tag (fn [rep] …)} — read handlers for this call,
               reconstructing values from tokens. Supply them at the
               rpc mount point, closing over the request when
               reconstruction needs it. Tags without a handler read
               as generic tokens."
  ([s] (read s nil))
  ([s {:keys [handlers]}]
   #?(:clj  (t/read (t/reader (if (string? s)
                                (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
                                s)
                              :json
                              {:handlers        (read-handler-map handlers)
                               :default-handler default-read}))
      :cljs (if (seq handlers)
              (t/read (t/reader :json {:handlers (read-handler-map handlers)}) s)
              (t/read default-reader s)))))
