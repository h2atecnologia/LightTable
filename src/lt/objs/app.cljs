(ns lt.objs.app
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.files :as files]
            [lt.util.js :refer [now]]
            [lt.util.dom :refer [$] :as dom]))

(def gui (js/require "nw.gui"))
(def win (.Window.get gui))
(def closing true)

(defn prevent-close []
  (set! closing false))

(defn close [force?]
  (when force?
    (object/raise app :closing)
    (object/raise app :closed))
  (.close win force?))

(defn refresh []
  (js/window.location.reload true))

(defn open-window []
  (let [id (store-swap! :window-id inc)
        w (.Window.open gui (str "LightTable.html?id=" id) (clj->js {:toolbar false
                                                                     :icon "core/img/lticon.png"
                                                                     :new-instance true
                                                                     :min_height 400
                                                                     :min_width 400
                                                                     :show false}))]
    (set! (.-ltid w) id)
    w))

(defn args []
  (when-not (= 0 (.-App.argv.length gui))
    (seq (.-App.argv gui))))

(defn init []
  (object/raise app :deploy)
  (object/raise app :pre-init)
  (object/raise app :init)
  (object/raise app :post-init)
  (object/raise app :show))

(defn fetch [k]
  (when-let [v (aget js/localStorage (name k))]
    (when (not= "null" v)
      (js/JSON.parse v))))

(defn store! [k v]
  (aset js/localStorage (name k) (if (string? v)
                                   (pr-str v)
                                   v)))

(defn store-swap! [k f]
  (let [neue (f (fetch k))]
    (store! k neue)
    neue))

(defn ensure-greater [x cap]
  (let [x (if (string? x)
            (js/parseInt x)
            x)]
    (max x cap)))

;;*********************************************************
;; Behaviors
;;*********************************************************

(object/behavior* ::refresh
                  :triggers #{:refresh}
                  :reaction (fn [obj]
                              (set! closing true)
                              (object/raise app :reload)
                              (when closing
                                (refresh))))

(object/behavior* ::close!
                  :triggers #{:close!}
                  :reaction (fn [this]
                              (set! closing true)
                              (object/raise this :close)
                              (when closing
                                (close true))))

(object/behavior* ::store-position-on-close
                  :triggers #{:closed :refresh}
                  :reaction (fn [this]
                              (set! js/localStorage.x (.-x win))
                              (set! js/localStorage.y (.-y win))
                              (set! js/localStorage.width (.-width win))
                              (set! js/localStorage.height (.-height win))
                              (set! js/localStorage.fullscreen (.-isFullscreen win))))

(object/behavior* ::restore-fullscreen
                  :triggers #{:show}
                  :reaction (fn [this]
                                (when (= js/localStorage.fullscreen "true")
                                  (.enterFullscreen win))))

(object/behavior* ::restore-position-on-init
                  :triggers #{:show}
                  :reaction (fn [this]
                              (when-not (empty? (.-width js/localStorage))
                                (.resizeTo win (ensure-greater js/localStorage.width 400) (ensure-greater js/localStorage.height 400))
                                (.moveTo win (ensure-greater js/localStorage.x 0) (ensure-greater js/localStorage.y 0)))))

(object/behavior* ::on-show-bind-navigate
                  :triggers #{:show}
                  :reaction (fn [this]
                              (dom/on ($ :#canvas) :click (fn [e]
                                                            ;;TODO: when prevent default has been called don't do this.
                                                            (when (= (.-target.nodeName e) "A")
                                                              (dom/prevent e)
                                                              (when-let [href (.-target.href e)]
                                                                (.Shell.openExternal gui href)
                                                                (.focus win)))))))

(object/behavior* ::initial-focus
                  :triggers #{:show}
                  :reaction (fn [this]
                              ;;(dom/focus (dom/$ :body))
                              (.focus win)))

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::app
                :tags #{:app}
                :delays 0
                :init (fn [this]
                        (ctx/in! :app this)))

(def app (object/create ::app))

(.on win "close" (fn [] (object/raise app :close!)))

(.on (.-App gui) "open" (fn [path] (object/raise app :open! path)))

;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :app.refresh
              :desc "Window: Refresh Light Table"
              :exec (fn []
                      (object/raise app :refresh))})

(cmd/command {:command :window.new
              :desc "Window: Open new window"
              :exec (fn []
                      (let [w (open-window)]))})

(cmd/command {:command :window.close
              :desc "Window: Close window"
              :exec (fn []
                      (object/raise app :close!))})
