(ns flowrpc.registry)

(defonce ^:private registry (atom {}))

(defn register!
  "Register a var for remote dispatch."
  [v]
  {:pre [(var? v)]}
  (swap! registry assoc (str (symbol v)) v)
  v)

(defn lookup
  "Returns the registered var for the given fully-qualified name string, or nil."
  [fn-name]
  (get @registry fn-name))
