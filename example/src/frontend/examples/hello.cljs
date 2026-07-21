(ns frontend.examples.hello)

;; A component is a plain function that returns hiccup.
(defn greeting [who]
  [:p "Hello, " [:strong who] "!"])

;; Use it by putting the function in the first slot of a vector.
(defn example []
  [:div
   [greeting "world"]
   [greeting "flowdom"]])
