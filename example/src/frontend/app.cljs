(ns frontend.app
  (:require [flowdom.docs :as docs]
            [frontend.flash :as flash]
            [frontend.pages :as pages]))

(defn app []
  [docs/app {:title          "flowdom"
             :subtitle       "guide"
             :sections       pages/sections
             :sidebar-footer flash/toggle}])
