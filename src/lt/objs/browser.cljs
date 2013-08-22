(ns lt.objs.browser
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.command :as cmd]
            [lt.objs.console :as console]
            [lt.objs.files :as files]
            [lt.objs.eval :as eval]
            [lt.objs.clients :as clients]
            [lt.objs.sidebar.clients :as scl]
            [lt.objs.menu :as menu]
            [lt.objs.context :as ctx]
            [lt.objs.editor :as editor]
            [lt.objs.keyboard :as keyboard]
            [lt.objs.notifos :as notifos]
            [lt.objs.clients.devtools :as devtools]
            [lt.util.dom :as dom]
            [clojure.string :as string]
            [crate.core :as crate]
            [crate.binding :refer [bound subatom]])
  (:require-macros [lt.macros :refer [defui]]))

(def utils (js-obj))
(set! js/lttools utils)

(def no-history-sites #{"data:text/html,chromewebdata"})

(defn check-http [url]
  (if (and (= (.indexOf url "http") -1)
           (= (.indexOf url "file://") -1))
    (str "http://" url)
    url))

(defn add-util [nme fn]
  (aset utils (name nme) fn))

(defui url-bar [this]
  [:input.url-bar {:type "text" :placeholder "url" :value (bound this :urlvalue)}]
  :focus (fn []
           (ctx/in! :browser.url-bar this)
           (object/raise this :active))
  :blur (fn []
          (object/raise this :inactive)
          (ctx/out! :browser.url-bar)))

(defui backward [this]
  [:button {:value "<"} "<"]
  :click (fn []
           (object/raise this :back!)))

(defui forward [this]
  [:button {:value ">"} ">"]
  :click (fn []
           (object/raise this :forward!)))

(defui refresh [this]
  [:button {:value "re"} "↺"]
  :click (fn []
           (object/raise this :refresh!)))

(defui iframe [this]
  [:iframe {:src (bound (subatom this :url)) :id (browser-id this) :nwfaketop "true" :nwdisable "true"}]
  :focus (fn []
           (object/raise this :active))
  :blur (fn []
          (object/raise this :inactive)))

(defn browser-id [this]
  (str "browser" (object/->id this)))

(defn to-frame [this]
  (let [id (if (string? this)
             this
             (browser-id this))]
    (aget js/window.frames id)))

(defn handle-cb [cbid command data]
  (object/raise clients/clients :message [cbid command data]))


(defn connect-client [this]
  (clients/handle-connection! {:name (:urlvalue @this)
                               :frame this
                               :frame-id (browser-id this)
                               :tags [:frame.client]
                               :commands #{:editor.eval.cljs.exec
                                           :editor.eval.js
                                           :editor.eval.html
                                           :editor.eval.css}
                               :type :frame}))


(defn add []
  (let [browser (object/create ::browser)]
    (tabs/add! browser)
    (tabs/active! browser)
    browser))


;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::browser
                :name "browser"
                :tags #{:browser}
                :history []
                :history-pos -1
                :url "about:blank"
                :urlvalue "about:blank"
                :init (fn [this]
                        (object/merge! this {:client (connect-client this)})
                        [:div#browser
                         [:div.frame-shade]
                         (iframe this)
                         [:nav
                          (backward this)
                          (forward this)
                          (url-bar this)
                          (refresh this)]
                         ]))

;;*********************************************************
;; Behaviors
;;*********************************************************

(object/behavior* ::destroy-on-close
                  :triggers #{:close}
                  :reaction (fn [this]
                              (object/raise this :inactive)
                              (object/destroy! this)))

(object/behavior* ::rem-client
                  :triggers #{:destroy}
                  :reaction (fn [this]
                              (when (= (ctx/->obj :global.browser) this)
                                (ctx/out! :global.browser))
                              (when-let [b (first (object/by-tag :browser))]
                                (ctx/in! :global.browser b))
                              (clients/rem! (:client @this))))

(object/behavior* ::navigate!
                  :triggers #{:navigate!}
                  :reaction (fn [this n]
                              (let [bar (dom/$ :input (object/->content this))
                                    frame (to-frame this)
                                    url (check-http (or n (dom/val bar)))]
                                (notifos/working)
                                (object/merge! this {:url url :urlvalue url :loading-counter (inc (:loading-counter @this 0))}))))

(object/behavior* ::store-history
                  :triggers #{:navigate}
                  :reaction (fn [this loc]
                                (when (and
                                       (not (no-history-sites loc))
                                       (not= loc (-> (:history @this)
                                                    (get (:history-pos @this)))))
                                (if-not (= (dec (count (:history @this)))
                                           (:history-pos @this))
                                  ;;clear forward
                                  (object/merge! this {:history (-> (subvec (:history @this) 0 (inc (:history-pos @this)))
                                                                   (conj loc))})
                                  (object/update! this [:history] conj loc)
                                  )
                                (object/update! this [:history-pos] inc))))

(object/behavior* ::url-focus!
                  :triggers #{:url.focus!}
                  :reaction (fn [this]
                              (dom/focus (dom/$ :input (object/->content this)))))

(object/behavior* ::focus!
                  :triggers #{:focus!}
                  :reaction (fn [this]
                              (dom/focus (dom/$ :iframe (object/->content this)))))

(object/behavior* ::back!
                  :triggers #{:back!}
                  :reaction (fn [this]
                              (let [frame (to-frame this)]
                                (when (> (:history-pos @this) 0)
                                  (object/update! this [:history-pos] dec)
                                  (object/raise this :navigate! (-> (:history @this)
                                                                    (get (:history-pos @this))))))))

(object/behavior* ::forward!
                  :triggers #{:forward!}
                  :reaction (fn [this]
                              (let [frame (to-frame this)]
                                (when (< (:history-pos @this) (dec (count (:history @this))))
                                  (object/update! this [:history-pos] inc)
                                  (object/raise this :navigate! (-> (:history @this)
                                                                    (get (:history-pos @this))))))))

(object/behavior* ::refresh!
                  :triggers #{:refresh!}
                  :reaction (fn [this]
                              (let [frame (to-frame this)]
                                (.location.reload frame))))

(object/behavior* ::menu!
                  :triggers #{:menu!}
                  :reaction (fn [this e]
                       (let [items (sort-by :order (object/raise-reduce this :menu+ []))]
                                (-> (menu/menu items)
                                    (menu/show-menu (.-clientX e) (.-clientY e))))
                       (dom/prevent e)
                       (dom/stop-propagation e)))

(object/behavior* ::menu+
                  :triggers #{:menu+}
                  :reaction (fn [this menu]
                              (conj menu
                                    {:label "forward"
                                     :order 0
                                     :click (fn [e]
                                              (cmd/exec! :browser.forward))}
                                    {:label "back"
                                     :order 0
                                     :click (fn [e]
                                              (cmd/exec! :browser.back))})


                       ))

(object/behavior* ::window-load-click-handler
                  :triggers #{:window.loaded}
                  :reaction (fn [this window loc]
                              (.document.addEventListener window "contextmenu"
                                                          (fn [e]
                                                            (object/raise this :menu! e)))
                              (.document.addEventListener window "click"
                                                          (fn [e]
                                                            (object/raise this :active)
                                                            (.log js/console (.-clientX e))
                                                            (when (and
                                                                   (= (.-target.nodeName e) "A")
                                                                   (or (and (platform/mac?) (.-metaKey e))
                                                                       (.-ctrlKey e)))
                                                              (.preventDefault e)
                                                              (.stopPropagation e)
                                                              (cmd/exec! :add-browser-tab (.-target.href e))))
                                                          true)))

(object/behavior* ::window-load-key-handler
                  :triggers #{:window.loaded}
                  :reaction (fn [this window loc]
                              (let [script (.document.createElement window "script")]
                                (set! (.-type script) "text/javascript")
                                (set! (.-innerHTML script) (:content (files/open-sync "core/node_modules/lighttable/util/keyevents.js")))
                                (.document.head.appendChild window script))
                              (.document.addEventListener window "keydown"
                                                          (fn [e]
                                                            (when (keyboard/capture e)
                                                              (dom/prevent e)
                                                              (dom/stop-propagation e)))
                                                          true)))
(object/behavior* ::window-load-lttools
                  :triggers #{:window.loaded}
                  :reaction (fn [this window loc]
                              (set! (.-lttools window) utils)))

(object/behavior* ::init!
                  :triggers #{:init}
                  :reaction (fn [this]
                              (let [frame (dom/$ :iframe (object/->content this))
                                    bar (dom/$ :input (object/->content this))]
                                (set! (.-onload frame) (fn []
                                                         (let [loc (.-contentWindow.location.href frame)]
                                                           (object/raise this :window.loaded (.-contentWindow frame) loc)
                                                           (set! (.-contentWindow.onhashchange frame) (fn []
                                                                                                        (dom/val bar (.-contentWindow.location.href frame))))
                                                           (devtools/clear-scripts!)
                                                           (dom/val bar loc)
                                                           (object/raise this :navigate loc)))))))

(object/behavior* ::set-client-name
                  :triggers #{:navigate}
                  :reaction (fn [this loc]
                              (let [title (.-document.title (to-frame this))
                                    title (if-not (empty? title)
                                            title
                                            "browser")]
                                (object/merge! this {:name title})
                                (tabs/refresh! this)
                                (dotimes [x (:loading-counter @this)]
                                  (notifos/done-working))
                                (object/merge! (:client @this) {:name loc}))))

(object/behavior* ::set-active
                  :triggers #{:active :show}
                  :reaction (fn [this]
                              (ctx/in! :global.browser this)))

(object/behavior* ::active-context
                  :triggers #{:active :show}
                  :reaction (fn [this]
                              (ctx/in! :browser this)))

(object/behavior* ::focus-on-show
                  :triggers #{:show}
                  :reaction (fn [this]
                              (object/raise this :focus!)))

(object/behavior* ::inactive-context
                  :triggers #{:inactive}
                  :reaction (fn [this]
                              (ctx/out! :browser)))

(object/behavior* ::handle-send!
                  :triggers #{:send!}
                  :reaction (fn [this msg]
                              (object/raise this (keyword (str (second msg) "!")) msg (js->clj msg :keywordize-keys true))
                              ))

(object/behavior* ::handle-refresh!
                  :triggers #{:client.refresh!}
                  :reaction (fn [this]
                              (object/raise (:frame @this) :refresh!)))

(object/behavior* ::handle-close!
                  :triggers #{:client.close!}
                  :reaction (fn [this]
                              (object/raise (:frame @this) :close)
                              (clients/rem! this)))

(object/behavior* ::change-live
                  :triggers #{:editor.eval.js.change-live!}
                  :reaction (fn [this msg clj-msg]
                              (when-let [ed (clients/cb->obj (first clj-msg))]
                                 (devtools/changelive! ed (-> clj-msg last :path) (js/lt.plugins.watches.watched-range ed nil nil js/lt.objs.langs.js.src->watch)
                                                       (fn [res]
                                                         ;;TODO: check for exception, otherwise, assume success
                                                         (object/raise ed :editor.eval.js.change-live.success)
                                                         )
                                                       identity))))

(object/behavior* ::js-eval-file
                  :triggers #{:editor.eval.js.file!}
                  :reaction (fn [this msg clj-msg cb]
                              (when-let [ed (clients/cb->obj (first clj-msg))]
                                (let [data (last msg)]
                                  (set! (.-code data) (str (-> clj-msg last :code) "\n\n//# sourceURL=" (-> clj-msg last :path)))
                                  (devtools/eval-in-frame (:frame-id @this) msg (fn [res]
                                                                                  (when cb (cb))
                                                                             ;;TODO: check for exception, otherwise, assume success
                                                                               (object/raise ed :editor.eval.js.file.success)))))))

(object/behavior* ::html-eval
                  :triggers #{:editor.eval.html!}
                  :reaction (fn [this msg clj-msg]
                              (when-let [ed (clients/cb->obj (first clj-msg))]
                                (object/raise this :client.refresh!))))

(object/behavior* ::css-eval
                  :triggers #{:editor.eval.css!}
                  :reaction (fn [this msg clj-msg]
                              (let [info (last clj-msg)
                                    frame (to-frame (:frame-id @this))
                                    node-name (string/replace (:name info) #"\." "-")
                                    node (.document.querySelector frame (str "#" node-name))
                                    neue (crate/html [:style {:type "text/css" :id node-name} (:code info)])]
                                (if node
                                  (do
                                    (dom/remove node)
                                    (dom/append (.-document.head frame) neue))
                                  (let [links (.document.querySelectorAll frame "link")
                                        node (first (filter #(> (.indexOf (dom/attr % :href) (:name info)) -1) (dom/lazy-nl-via-item links)))]
                                    (when node
                                      (dom/remove node))
                                    (dom/append (.-document.head frame) neue))))))

(object/behavior* ::cljs-exec
                  :triggers #{:editor.eval.cljs.exec!}
                  :reaction (fn [this msg clj-msg]
                              (let [frame-id (:frame-id @this)
                                    frame (to-frame frame-id)
                                    window (devtools/get-frame-window frame-id)
                                    info (last clj-msg)]
                                (doseq [form (:results info)]
                                  (try
                                    ;;TODO: this is a hack for bad compiler output. We need to just move to the latest cljs
                                    (handle-cb (first clj-msg) :editor.eval.cljs.result {:result (eval/cljs-result-format (.eval.call window window (string/replace (:code form) ")goog" ")\ngoog")))
                                                                                         :meta (:meta form)})
                                    (catch (.-Error window) e
                                      (handle-cb (first clj-msg) :editor.eval.cljs.exception {:ex e
                                                                                              :meta (:meta form)})))))))

(defn eval-js-form [this msg clj-msg]
  (set! (.-code (last msg)) (eval/append-source-file (-> clj-msg last :code) (-> clj-msg last :path)))
  (devtools/eval-in-frame (:frame-id @this) msg
                          (fn [res]
                            (let [result (devtools/inspector->result res)
                                  req (last clj-msg)
                                  result (assoc result :meta (:meta req) :no-inspect true)]
                              (if-not (:ex result)
                                (handle-cb (first clj-msg) :editor.eval.js.result result)
                                (handle-cb (first clj-msg) :editor.eval.js.exception result)))))
  (object/raise this :editor.eval.js.change-live! msg clj-msg))

(defn must-eval-file? [clj-msg]
  ;;we eval the whole file if there's no meta, or this file isn't loaded in the current page
  (or (not (-> clj-msg last :meta))
      (not (devtools/find-script devtools/local (-> clj-msg last :path)))))


(object/behavior* ::js-eval
                  :triggers #{:editor.eval.js!}
                  :reaction (fn [this msg clj-msg]
                              (if (must-eval-file? clj-msg)
                                (when-let [ed (clients/cb->obj (first clj-msg))]
                                  (let [data (last msg)
                                        origcode (.-code data)]
                                    (set! (.-code data) (str (editor/->val ed) "\n\n//# sourceURL=" (-> clj-msg last :path)))
                                    (devtools/eval-in-frame (:frame-id @this) msg (fn [res]
                                                                                    (set! (.-code (last msg)) origcode)
                                                                                    (eval-js-form this msg clj-msg)))))
                                (eval-js-form this msg clj-msg))))

;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :browser.url-bar.navigate!
              :desc "BrowserUrlBar: navigate to location"
              :hidden true
              :exec (fn [loc]
                      (when-let [b (ctx/->obj :browser.url-bar)]
                        (when @b
                          (object/raise b :navigate! loc))))})

(cmd/command {:command :browser.url-bar.focus
              :desc "Browser: focus url"
              :hidden true
              :exec (fn [loc]
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :url.focus!))))})

(cmd/command {:command :browser.focus-content
              :desc "Browser: focus content"
              :hidden true
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :focus!))))})

(cmd/command {:command :browser.back
              :desc "Browser: back"
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :back!))))})

(cmd/command {:command :browser.forward
              :desc "Browser: forward"
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :forward!))))})

(cmd/command {:command :add-browser-tab
              :desc "Browser: add browser tab"
              :exec (fn [loc]
                      (let [b (add)]
                        (if-not loc
                          (object/raise b :focus!)
                          (object/raise b :navigate! loc))))})

(cmd/command {:command :refresh-connected-browser
              :desc "Browser: refresh active browser tab"
              :exec (fn []
                      (when-let [b (ctx/->obj :global.browser)]
                        (when @b
                          (object/raise b :refresh!))))})

;;*********************************************************
;; Misc
;;*********************************************************

(scl/add-connector {:name "Browser"
                    :desc "Open a browser tab to eval JavaScript, CSS, and HTML live."
                    :connect (fn []
                               (cmd/exec! :add-browser-tab))})
