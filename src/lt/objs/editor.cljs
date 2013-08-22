(ns lt.objs.editor
  (:refer-clojure :exclude [val replace range])
  (:require [crate.core :as crate]
            [lt.objs.context :as ctx-obj]
            [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.command :as cmd]
            [lt.objs.settings :as settings]
            [lt.objs.menu :as menu]
            [lt.util.events :as ev]
            [lt.util.dom :as dom]
            [lt.util.load :as load])
  (:use [lt.util.dom :only [remove-class add-class]]
        [lt.object :only [object* behavior*]]
        [lt.util.cljs :only [js->clj]]))

(def gui (js/require "nw.gui"))
(def clipboard (.Clipboard.get gui))

;;*********************************************************
;; commands
;;*********************************************************

(defn expand-tab [cm]
  (cond
   (.somethingSelected cm) (.indentSelection cm "add")
   (.getOption cm "indentWithTabs") (.replaceSelection cm "\t" "end" "+input")
   :else
   (let [spaces (.join (js/Array (inc (.getOption cm "indentUnit"))) " ")]
      (.replaceSelection cm spaces "end" "+input"))))


;;*********************************************************
;; Creating
;;*********************************************************

(def ed-id (atom 0))

(defn ed-with-elem [$elem opts]
  (js/CodeMirror (if (.-get $elem)
                            (.get $elem 0)
                            $elem)
                 (clj->js opts)))

(defn ed-headless [opts]
  (-> (js/CodeMirror. (fn []))
      (set-options opts)))

(defn ->editor [$elem opts]
  (let [ed (if $elem
             (ed-with-elem $elem opts)
             (ed-headless opts))]
    (set! (.-ltid ed) (swap! ed-id inc))
    (set! (.-ltproperties ed) (atom {}))
    ed))

(defn init [ed context]
  (-> ed
      (clear-props)
      (set-props (dissoc context :content))
      (set-val (:content context))
      (set-options {:mode (name (:type context))
                    :readOnly false
                    :dragDrop false
                    :lineNumbers false
                    :lineWrapping true})))

(defn make [$elem context]
  (let [e (->editor $elem {:mode (if (:type context)
                                   (name (:type context))
                                   "text")
                           :autoClearEmptyLines true
                           :dragDrop false
                           :onDragEvent (fn [] true)
                           :lineNumbers false
                           :lineWrapping false
                           :undoDepth 10000
                           :matchBrackets true})]
    (set-props e (dissoc context :content))
    (when-let [c (:content context)]
      (set-val e c)
      (clear-history e))
    e))

(defn ->cm-ed [e]
  (if (satisfies? IDeref e)
    (:ed @e)
    e))

(defn on [ed ev func]
  (.on (->cm-ed ed) (name ev) func))

(defn off [ed ev func]
  (.off (->cm-ed ed) (name ev) func))

(defn wrap-object-events [ed obj]
  (dom/on (->elem ed) :contextmenu #(object/raise obj :menu! %))
  (on ed :scroll #(object/raise obj :scroll %))
  (on ed :update #(object/raise obj :update % %2))
  (on ed :change #(object/raise obj :change % %2))
  (on ed :inputRead #(object/raise obj :input % %2))
  (on ed :cursorActivity #(object/raise obj :move % %2))
  (on ed :focus #(object/raise obj :focus %))
  (on ed :blur #(object/raise obj :blur %)))

;;*********************************************************
;; Params
;;*********************************************************

(defn clear-props [ed]
  (reset! (.-ltproperties ed) {})
  ed)

(defn set-props [ed m]
  (swap! (.-ltproperties ed) merge m)
  ed)

(defn ->ltid [ed]
  (.-ltid ed))

(defn ->prop
  ([ed] (deref (.-ltproperties ed)))
  ([ed k] (get (->prop ed) k)))

(defn clear-history [e]
  (.clearHistory e)
  e)

(defn ->val [e]
  (. (->cm-ed e) (getValue)))

(defn ->token [e pos]
  (js->clj (.getTokenAt (->cm-ed e) (clj->js pos)) :keywordize-keys true))

(defn ->token-js [e pos]
  (.getTokenAt (->cm-ed e) (clj->js pos)))

(defn ->coords [e]
  (js->clj (.cursorCoords (->cm-ed e)) :keywordize-keys true :force-obj true))

(defn ->elem [e]
  (.-parentElement (.getScrollerElement (->cm-ed e))))

(defn +class [e klass]
  (add-class (->elem e) (name klass))
  e)

(defn -class [e klass]
  (remove-class (->elem e) (name klass))
  e)

(defn cursor [e side]
  (.getCursor (->cm-ed e) side))

(defn ->cursor [e & [side]]
  (let [pos (cursor e side)]
    {:line (.-line pos)
     :ch (.-ch pos)}))

(defn pos->index [e pos]
  (.indexFromPos (->cm-ed e) (clj->js pos)))

(defn set-val [e v]
  (. (->cm-ed e) (setValue (or v "")))
  e)

(defn mark [e from to opts]
  (.markText (->cm-ed e) (clj->js from) (clj->js to) (clj->js opts)))

(defn find-marks [e pos]
  (.findMarksAt (->cm-ed e) (clj->js pos)))

(defn bookmark [e from widg]
  (.setBookmark (->cm-ed e) (clj->js from) (clj->js widg)))

(defn option [e o]
  (.getOption (->cm-ed e) (name o)))

(defn set-options [e m]
  (doseq [[k v] m
          :let [k (name k)]]
    (.setOption (->cm-ed e) k v))
  e)

(defn set-mode [e m]
  (.setOption (->cm-ed e) "mode" m)
  e)

(defn ->mode [e]
  (.getMode (->cm-ed e)))

(defn focus [e]
  (.focus (->cm-ed e))
  e)

(defn input-field [e]
  (.getInputField e))

(defn blur [e]
  (.blur (input-field e))
  e)

(defn refresh [e]
  (.refresh (->cm-ed e))
  e)

(defn on-move [e func]
 (.on e "onCursorActivity"
            (fn [ed delta]
              (func ed delta)))
  e)

(defn on-change [e func]
 (.on e "onChange"
            (fn [ed delta]
              (func ed delta)))
  e)

(defn on-update [e func]
 (.on e "onUpdate"
            (fn [ed delta]
              (func ed delta)))
  e)

(defn on-scroll [e func]
 (.on e "onScroll"
            (fn [ed]
              (func ed)
              ))
  e)

(defn replace
  ([e from v]
   (.replaceRange (->cm-ed e) v (clj->js from)))
  ([e from to v]
   (.replaceRange (->cm-ed e) v (clj->js from) (clj->js to))))

(defn range [e from to]
  (.getRange (->cm-ed e) (clj->js from) (clj->js to)))

(defn line-count [e]
  (.lineCount e))

(defn insert-at-cursor [ed s]
  (replace (->cm-ed ed) (->cursor ed) s)
  ed)

(defn move-cursor [ed pos]
  (.setCursor (->cm-ed ed) (clj->js pos)))

(defn scroll-to [ed x y]
  (.scrollTo (->cm-ed ed) x y))

(defn center-cursor [ed]
  (let [l (:line (->cursor ed))
        y (.-top (.charCoords (->cm-ed ed) (clj->js {:line l :ch 0}) "local"))
        half-h (/ (.-offsetHeight (.getScrollerElement (->cm-ed ed))) 2)]
    (scroll-to ed nil (- y half-h -55))))

(defn selection-bounds [e]
  (when (selection? e)
    {:from (->cursor e"start")
     :to (->cursor e "end")}))

(defn selection [e]
  (.getSelection (->cm-ed e)))

(defn selection? [e]
  (.somethingSelected (->cm-ed e)))

(defn set-selection [e start end]
  (.setSelection (->cm-ed e) (clj->js start) (clj->js end)))

(defn replace-selection [e neue]
  (.replaceSelection (->cm-ed e) neue "end" "+input"))

(defn undo [e]
  (.undo (->cm-ed e)))

(defn redo [e]
  (.redo (->cm-ed e)))

(defn copy [e]
  (.set clipboard (selection e) "text"))

(defn cut [e]
  (copy e)
  (replace-selection e ""))

(defn paste [e]
  (replace-selection e (.get clipboard "text")))

(defn select-all [e]
  (set-selection e
                 {:line (first-line e)}
                 {:line (last-line e)}))

(defn clear-history [e]
  (.clearHistory e)
  e)

(defn get-history [e]
  (.getHistory (->cm-ed e)))

(defn set-history [e v]
  (.setHistory (->cm-ed e) v)
  e)

(defn char-coords [e pos]
  (js->clj (.charCoords (->cm-ed e) (clj->js pos)) :keywordize-keys true :force-obj true))

(defn operation [e func]
  (.operation (->cm-ed e) func)
  e)

(defn compound [e fun]
  (.compoundChange (->cm-ed e) fun)
  e)

(defn on-click [e func]
  (let [elem (->elem e)]
    (ev/capture elem :mousedown func)
    e))

(defn extension [name func]
  (.defineExtension js/CodeMirror name func))

(defn line-widget [e line elem & [opts]]
  (.addLineWidget (->cm-ed e) line elem (clj->js opts)))

(defn remove-line-widget [e widg]
  (.removeLineWidget (->cm-ed e) widg))

(defn line [e l]
  (.getLine (->cm-ed e) l))

(defn first-line [e]
  (.firstLine (->cm-ed e)))

(defn last-line [e]
  (.lastLine (->cm-ed e)))

(defn line-handle [e l]
  (.getLineHandle (->cm-ed e) l))

(defn lh->line [e lh]
  (.getLineNumber (->cm-ed e) lh))

(defn line-length [e l]
  (count (line e l)))

(defn +line-class [e lh plane class]
  (.addLineClass e lh (name plane) (name class)))

(defn -line-class [e lh plane class]
  (.removeLineClass e lh (name plane) (name class)))

(defn show-hints [e hint-fn options]
  (js/CodeMirror.showHint (->cm-ed e) hint-fn (clj->js options))
  e)

(defn inner-mode [e state]
  (let [state (or state (->> (cursor e) (->token-js e) (.-state)))]
    (-> (js/CodeMirror.innerMode (.getMode (->cm-ed e)) state)
        (.-mode))))

(defn adjust-loc [loc dir]
  (update-in loc [:ch] + dir))

(defn get-char [ed dir]
  (let [loc (->cursor ed)]
    (if (> dir 0)
      (range ed loc (adjust-loc loc dir))
      (range ed (adjust-loc loc dir) loc))))

(defn indent-line [e l dir]
  (.indentLine (->cm-ed e) l dir))

(defn indent-selection [e dir]
  (.indentSelection (->cm-ed e) dir))

(defn line-comment [e from to opts]
  (.lineComment (->cm-ed e) (clj->js from) (clj->js to) (clj->js opts)))

(defn uncomment [e from to opts]
  (.uncomment (->cm-ed e) (clj->js from) (clj->js to) (clj->js opts)))

;;*********************************************************
;; Object
;;*********************************************************

(load/js "core/node_modules/codemirror/codemirror.js" :sync)

(object* ::editor
         :tags #{:editor :editor.inline-result :editor.keys.normal}
         :init (fn [obj info]
                 (let [ed (make nil info)]
                   (object/merge! obj {:ed ed :info (dissoc info :content)})
                   (wrap-object-events ed obj)
                   (->elem ed)
                   )))

;;*********************************************************
;; Behaviors
;;*********************************************************


(behavior* ::read-only
           :triggers #{:init}
           :reaction (fn [obj]
                       (set-options (:ed @obj) {:readOnly "nocursor"})))

(behavior* ::wrap
           :triggers #{:object.instant :lt.object/tags-removed}
           :desc "Editor: Wrap lines"
           :exclusive [::no-wrap]
           :type :user
           :reaction (fn [obj]
                       (set-options obj {:lineWrapping true})))

(behavior* ::no-wrap
           :triggers #{:object.instant :lt.object/tags-removed}
           :desc "Editor: Unwrap lines"
           :exclusive [::wrap]
           :type :user
           :reaction (fn [obj]
                       (set-options obj {:lineWrapping false})))

(behavior* ::line-numbers
           :triggers #{:object.instant :lt.object/tags-removed}
           :desc "Editor: Show line numbers"
           :exclusive [::hide-line-numbers]
           :type :user
           :reaction (fn [this]
                       (set-options this {:lineNumbers true})))

(behavior* ::hide-line-numbers
           :triggers #{:object.instant :lt.object/tags-removed}
           :desc "Editor: Hide line numbers"
           :exclusive [::line-numbers]
           :type :user
           :reaction (fn [this]
                       (set-options this {:lineNumbers false})))

(behavior* ::tab-settings
           :triggers #{:object.instant}
           :desc "Editor: indent settings (tab size, etc)"
           :params [{:label "Use tabs?"
                     :type :boolean}
                    {:label "Tab size in spaces"
                     :type :number}
                    {:label "Spaces per indent"
                     :type :number}]
           :type :user
           :exclusive true
           :reaction (fn [obj use-tabs? tab-size indent-unit]
                       (set-options obj {:tabSize tab-size
                                         :indentWithTabs use-tabs
                                         :indentUnit indent-unit})))

(object/behavior* ::read-only
                  :triggers #{:object.instant}
                  :desc "Editor: make editor read-only"
                  :exclusive [::not-read-only]
                  :reaction (fn [this]
                              (object/update! this [:info :name] str " (read-only)")
                              (set-options this {:readOnly true})))

(object/behavior* ::not-read-only
                  :triggers #{:object.instant}
                  :desc "Editor: make editor writable"
                  :exclusive [::read-only]
                  :reaction (fn [this]
                              (set-options this {:readOnly false})))

(object/behavior* ::blink-rate
                  :triggers #{:object.instant}
                  :desc "Editor: set cursor blink rate"
                  :exclusive true
                  :type :user
                  :reaction (fn [this rate]
                              (if rate
                                (set-options this {:cursorBlinkRate rate})
                                (set-options this {:cursorBlinkRate 0}))))

(behavior* ::active-on-focus
           :triggers #{:focus}
           :reaction (fn [obj]
                       (object/add-tags obj [:editor.active])
                       (when-let [parent (object/parent obj)]
                         (object/raise parent :active))
                       (object/raise obj :active)))

(behavior* ::inactive-on-blur
           :triggers #{:blur}
           :reaction (fn [obj]
                       (object/remove-tags obj [:editor.active])
                       (when-let [parent (object/parent obj)]
                         (object/raise parent :inactive))
                       (object/raise obj :inactive)))

(behavior* ::refresh!
           :triggers #{:refresh!}
           :reaction (fn [this]
                       (refresh this)))

(behavior* ::on-tags-added
           :triggers #{:lt.object/tags-added}
           :reaction (fn [this added]
                       (doseq [a added]
                         (ctx-obj/in! a this))))

(behavior* ::on-tags-removed
           :triggers #{:lt.object/tags-removed}
           :reaction (fn [this removed]
                       (doseq [r removed]
                         (ctx-obj/out! r this))))

(behavior* ::context-on-active
           :triggers #{:active}
           :reaction (fn [obj]
                       ;;TODO: this is probably inefficient due to inactive
                       (ctx-obj/in! (:tags @obj) obj)
                         ))

(behavior* ::context-on-inactive
           :triggers #{:inactive}
           :reaction (fn [obj]
                       (let [tags (:tags @obj)
                             cur-editor (ctx-obj/->obj :editor)]
                         ;;blur comes after the focus of a second editor
                         ;;so only go out if I was the editor that is active
                         (ctx-obj/out! tags)
                         (when (and cur-editor
                                    (not= cur-editor obj))
                           (ctx-obj/in! (:tags @cur-editor) cur-editor)
                           ))))

(behavior* ::refresh-on-show
           :triggers #{:show}
           :reaction (fn [obj]
                       (refresh (:ed @obj))
                       (object/raise obj :focus!)
                       ))

(behavior* ::focus
           :triggers #{:focus!}
           :reaction (fn [obj]
                       (focus (:ed @obj))))

(behavior* ::destroy-on-close
           :triggers #{:close.force}
           :reaction (fn [obj]
                       (object/destroy! obj)))

(behavior* ::highlight-current-line
           :triggers #{:object.instant}
           :exclusive true
           :reaction (fn [this]
                       (set-options this {:styleActiveLine true})))

(behavior* ::menu!
           :triggers #{:menu!}
           :reaction (fn [this e]
                       (let [items (sort-by :order (object/raise-reduce this :menu+ []))]
                                (-> (menu/menu items)
                                    (menu/show-menu (.-clientX e) (.-clientY e))))
                       (dom/prevent e)
                       (dom/stop-propagation e)
                       ))

(behavior* ::copy-paste-menu+
           :triggers #{:menu+}
           :reaction (fn [this items]
                       (conj items
                             {:label "Copy"
                              :order 1
                              :enabled (boolean (selection? this))
                              :click (fn []
                                       (copy this))}
                             {:label "Cut"
                              :order 2
                              :enabled (boolean (selection? this))
                              :click (fn []
                                       (cut this))}
                             {:label "Paste"
                              :order 3
                              :enabled (boolean (not (empty? (.get clipboard "text"))))
                              :click (fn []
                                       (paste this))}
                             {:type "separator"
                              :order 4}
                             {:label "Select all"
                              :order 5
                              :click (fn []
                                       (select-all this))})))

(object/behavior* ::init-codemirror
                  :triggers #{:init}
                  :reaction (fn [this]
                              (load/js "core/node_modules/codemirror/matchbracket.js" :sync)
                              (load/js "core/node_modules/codemirror/comment.js" :sync)
                              (load/js "core/node_modules/codemirror/active-line.js" :sync)
                              (load/js "core/node_modules/codemirror/overlay.js" :sync)
                              (doseq [mode (files/ls "core/node_modules/codemirror/modes")
                                      :when (= (files/ext mode) "js")]
                                (load/js (str "core/node_modules/codemirror/modes/" mode) :sync))
                              (aset js/CodeMirror.keyMap.basic "Tab" expand-tab)))
