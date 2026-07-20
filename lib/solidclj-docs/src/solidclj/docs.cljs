(ns solidclj.docs
  "Docs shell: sidebar navigation on the left, the selected guide page
  (prose + live examples) on the right. Rendered with flowdom.

  Usage:
    [docs/app {:title          \"mylib\"
               :subtitle       \"guide\"
               :sections       pages/sections
               :sidebar-footer my-ns/toggle-component}]"
  (:require [flowdom.rx :refer [rx ?]]
            [solidclj.docs.ui :as ui]))

(defn- all-pages [sections]
  (vec (mapcat :pages sections)))

(defn- find-page [sections id]
  (let [pages (all-pages sections)]
    (or (some #(when (= id (:id %)) %) pages)
        (first pages))))

(defn- initial-page-id [sections]
  (let [h (.. js/location -hash)]
    (if (seq h)
      (:id (find-page sections (keyword (subs h 1))))
      (:id (first (all-pages sections))))))

(defn- nav-item [{:keys [id title todo]} current go]
  [:button {:class    (rx (str "block w-full text-left px-2 py-1 rounded text-sm cursor-pointer "
                               (if (= id (? current))
                                 "bg-blue-100 text-blue-700 font-medium"
                                 (if todo
                                   "text-gray-400 hover:bg-gray-100"
                                   "text-gray-600 hover:bg-gray-100"))))
            :on-click #(go id)}
   title
   (when todo
     [:span {:class "ml-1.5 text-[10px] font-semibold uppercase tracking-wide text-amber-600"}
      "soon"])])

(defn- brand [title subtitle]
  [:div {:class "flex items-baseline gap-2"}
   [:span {:class "text-xl font-bold text-gray-900"} title]
   (when subtitle
     [:span {:class "text-xs text-gray-400"} subtitle])])

(defn- top-bar
  "Mobile-only (md:hidden) sticky header: menu button + brand."
  [title subtitle toggle]
  [:header {:class "sticky top-0 z-20 flex items-center gap-3 border-b border-gray-200 bg-white/95 backdrop-blur px-4 py-3 md:hidden"}
   [:button {:class      "flex h-9 w-9 items-center justify-center rounded-md border border-gray-200 text-gray-600 hover:bg-gray-100 cursor-pointer select-none"
             :aria-label "Toggle navigation"
             :on-click   toggle}
    "☰"]
   [brand title subtitle]])

(defn- sidebar
  "Static column from md up; below that a slide-in drawer driven by the
  `open?` atom (the :class rx keeps the transform live)."
  [title subtitle sections current go sidebar-footer open?]
  [:aside {:class (rx (str "fixed inset-y-0 left-0 z-40 w-64 border-r border-gray-200 bg-gray-50 "
                           "transition-transform duration-200 ease-out "
                           "md:static md:z-auto md:shrink-0 md:translate-x-0 md:transition-none "
                           (if (? open?) "translate-x-0" "-translate-x-full")))}
   [:div {:class "sticky top-0 max-h-screen overflow-y-auto p-4"}
    [brand title subtitle]

    (for [{:keys [title pages]} sections]
      [:div {:class "mt-5"}
       [:h2 {:class "text-xs font-semibold uppercase tracking-wider text-gray-400"} title]
       [:ul {:class "mt-2 space-y-0.5"}
        (for [page pages]
          [:li [nav-item page current go]])]])

    (when sidebar-footer
      [sidebar-footer])]])

(defn page-view [{:keys [title prose body examples]}]
  [:div
   [:h1 {:class "text-2xl font-bold text-gray-900 mb-4"} title]
   (if body
     body
     [:<>
      [:div {:class "prose prose-gray max-w-none"} prose]
      (for [ex examples]
        [ui/example-block ex])])])

(defn app
  "Top-level docs shell component.

   Props:
     :title          — displayed in sidebar header (required)
     :subtitle       — small label next to title (optional)
     :sections       — seq of {:title str :pages [{:id :title :prose :examples}]}
     :sidebar-footer — optional zero-arg component rendered at the bottom of the sidebar"
  [{:keys [title subtitle sections sidebar-footer]}]
  (let [current   (atom (initial-page-id sections))
        nav-open? (atom false)
        go        (fn [id]
                    (reset! current id)
                    (reset! nav-open? false)
                    (set! (.. js/location -hash) (name id))
                    (js/window.scrollTo 0 0))]
    [:div {:class "min-h-screen bg-white text-gray-900 antialiased"}
     [top-bar title subtitle (fn [_] (swap! nav-open? not))]
     ;; backdrop: closes the drawer; never rendered from md up
     (rx (when (? nav-open?)
           [:div {:class    "fixed inset-0 z-30 bg-gray-900/40 md:hidden"
                  :on-click (fn [_] (reset! nav-open? false))}]))
     [:div {:class "flex md:min-h-screen"}
      [sidebar title subtitle sections current go sidebar-footer nav-open?]
      [:main {:class "flex-1 min-w-0 px-4 py-6 sm:px-8 sm:py-10"}
       (rx [page-view (find-page sections (? current))])]]]))
