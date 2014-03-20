;; Browser and UI-related stuff goes in this file.
;; generic stuff should go elsewhere.

(ns cbfg.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [cbfg.ago :refer [ago ago-loop achan-buf aput atake]])
  (:require [clojure.string :as string]
            [cljs.core.async :refer [<! >! put! chan timeout merge map<]]
            [goog.dom :as gdom]
            [goog.events :as gevents]
            cbfg.ddl
            [cbfg.fence :refer [make-fenced-pump]]))

(enable-console-print!)

(println cbfg.ddl/hi)

;; ------------------------------------------------

(defn get-el-value [elId]
  (.-value (gdom/getElement elId)))

(defn set-el-innerHTML [elId v]
  (set! (.-innerHTML (gdom/getElement elId)) v))

(defn listen [el type]
  (let [out (chan)]
    (gevents/listen el type (fn [e] (put! out e)))
    out))

(defn dissoc-in [m [k & ks :as keys]]
  (if ks
    (if-let [next-map (get m k)]
      (let [new-map (dissoc-in next-map ks)]
        (if (seq new-map)
          (assoc m k new-map)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; ------------------------------------------------

(defn vis-add-ch [vis ch first-taker-actx]
  (-> vis
      (update-in [:chs ch] #(if (nil? %)
                              {:id ((:gen-id vis))
                               :msgs {}
                               :first-taker-actx first-taker-actx}
                              %))
      (update-in [:chs ch :first-taker-actx]
                 #(if % % first-taker-actx))))

(def vis-event-handlers
  {"ago"
   {:start (fn [vis actx args]
             (let [[child-actx] args]
               (swap! vis #(-> %
                               (assoc-in [:actxs child-actx]
                                         {:children {} :wait-chs {}})
                               (assoc-in [:actxs actx :children child-actx] true)))
               [{:delta :actx-start :actx actx :child-actx child-actx}]))
    :end (fn [vis actx args]
           (let [[child-actx result] args]
             (swap! vis #(-> %
                             (dissoc-in [:actxs child-actx])
                             (dissoc-in [:actxs actx :children child-actx])))
             [{:delta :actx-end :actx actx :child-actx child-actx}]))}
   "aclose"
   {:before (fn [vis actx args]
              (let [[ch] args] nil))
    :after (fn [vis actx args]
             (let [[ch result] args] nil))}
   "atake"
   {:before (fn [vis actx args]
              (let [[ch] args]
                (swap! vis #(-> %
                                (assoc-in [:actxs actx :wait-chs ch] :take)
                                (vis-add-ch ch actx)))
                nil))
    :after (fn [vis actx args]
             (let [[ch msg] args]
               (swap! vis #(-> %
                               (dissoc-in [:actxs actx :wait-chs ch])
                               (dissoc-in [:chs ch :msgs msg])))
               (when (nil? msg) ; The ch is closed.
                 (swap! vis #(dissoc-in % [:chs ch])))
               [{:delta :take :msg msg :ch ch :actx actx}]))}
   "aput"
   {:before (fn [vis actx args]
              (let [[ch msg] args]
                (swap! vis #(-> %
                                (assoc-in [:actxs actx :wait-chs ch] :put)
                                (vis-add-ch ch nil)
                                (assoc-in [:chs ch :msgs msg] true)))
                [{:delta :put :msg msg :actx actx :ch ch}]))
    :after (fn [vis actx args]
             (let [[ch msg result] args]
               (swap! vis #(-> %
                               (dissoc-in [:actxs actx :wait-chs ch])))
               ; NOTE: Normally we should cleanup ch when nil result but
               ; looks like CLJS async always incorrectly returns nil from >!.
               nil))}
   "aalts"
   {:before (fn [vis actx args]
              (let [[ch-bindings] args
                    ; The ch-actions will be [[ch :take] [ch :put] ...].
                    ch-actions (map #(if (seq? %) [(first %) :put (second %)] [% :take])
                                    ch-bindings)]
                (apply concat
                       (mapv (fn [ch-action]
                               (let [[ch action & msgv] ch-action]
                                 (swap! vis #(-> %
                                                 (vis-add-ch ch
                                                             (when (= action :take) actx))
                                                 (assoc-in [:actxs actx :wait-chs ch]
                                                           action)))
                                 (when (= action :put)
                                   [{:delta :put :msg (first msgv) :actx actx :ch ch}])))
                             ch-actions))))
    :after (fn [vis actx args]
             (let [[ch-bindings result] args
                   chs (map #(if (seq? %) (first %) %) ch-bindings)
                   [result-msg result-ch] result]
               (doseq [ch chs]
                 (swap! vis #(dissoc-in % [:actxs actx :wait-chs ch])))
               (swap! vis #(dissoc-in % [:chs result-ch :msgs result-msg]))
               (when (nil? result-msg) ; The ch is closed.
                 (swap! vis #(dissoc-in % [:chs result-ch])))
               (when (some #(= % result-ch) ch-bindings)
                 [{:delta :take :msg result-msg :ch result-ch :actx actx}])))}})

;; ------------------------------------------------

(defn assign-position [positions id]
  ; NOTE: merge doesn't work as expected in CLJS.
  ; (swap! positions #(merge {id (count %)} %))
  ; (swap! positions (fn [x] (merge {id (count x)} x)))
  (swap! positions #(update-in % [id] (fn [v] (if v v (count %))))))

;; ------------------------------------------------

(defn vis-html-actx [vis actx positions actx-ch-ch-infos]
  (let [actx-id (last actx)
        actx-info (get-in vis [:actxs actx])
        children (:children actx-info)
        wait-chs (:wait-chs actx-info)
        chs (:chs vis)]
    (assign-position positions actx-id)
    ["<div id='actx-" actx-id "' class='actx'>" actx-id
     (if (not-empty wait-chs)
       [" -- waiting: ("
        (map (fn [kv] (let [[ch wait-kind] kv]
                        [(:id (get chs ch)) wait-kind ", "]))
             wait-chs)
        ")"]
       [])
     "<div class='chs'>"
     "  <ul>"
     (mapv (fn [ch-ch-info]
             (let [ch-info (second ch-ch-info)
                   ch-id (:id ch-info)]
               (assign-position positions ch-id)
               ["<li id='ch-" ch-id "'>" ch-id ": " (:msgs ch-info) "</li>"]))
           (get actx-ch-ch-infos actx))
     "  </ul>"
     "</div>"
     (if (not-empty children)
       ["<ul>" (mapv (fn [child-actx-bool]
                       ["<li>" (vis-html-actx vis (first child-actx-bool)
                                              positions actx-ch-ch-infos)
                        "</li>"])
                     children)
        "</ul>"]
       [])
     "</div>"]))

(defn vis-svg-actxs [vis positions deltas]
  (let [stroke-width 1
        line-height 21
        chs (:chs vis)
        ch-y (fn [ch] (* line-height (+ 0.5 (get positions (:id (get chs ch))))))
        actx-y (fn [actx] (* line-height (+ 0.5 (get positions (last actx)))))]
    ["<defs>"
     "<marker id='triangle'"
     " viewBox='0 0 10 10' refX='0' refY='5'"
     " markerUnits='strokeWidth'"
     " markerWidth='8' markerHeight='6'"
     " orient='auto'>"
     " <path d='M 0 0 L 10 5 L 0 10 z'/>"
     "</defs>"
     (mapv (fn [actx-actx-info]
             (let [[actx actx-info] actx-actx-info]
               (mapv (fn [ch-wait-kind]
                       (let [[ch wait-kind] ch-wait-kind]
                         (if (= :put wait-kind)
                           ["<line x1='500' y1='" (actx-y actx)
                            "' x2='600' y2='" (ch-y ch)
                            "' stroke='#faa' stroke-width='1' marker-end='url(#triangle)'/>"]
                           ["<line x1='600' y1='" (ch-y ch)
                            "' x2='500' y2='" (actx-y actx)
                            "' stroke='#faa' stroke-width='1' marker-end='url(#triangle)'/>"])))
                     (:wait-chs actx-info))))
           (:actxs vis))
     (mapv (fn [delta]
             (case (:delta delta)
               :put (when (get chs (:ch delta))
                      ["<g transform='translate(500," (actx-y (:actx delta)) ")'>"
                       "<line class='delta' x1='0' y1='0' x2='100' y2='"
                       (- (ch-y (:ch delta)) (actx-y (:actx delta)))
                       "' stroke='green' stroke-width='1' marker-end='url(#triangle)'/>"
                       "</g>"])
               :take (when (get chs (:ch delta))
                       ["<g transform='translate(600," (ch-y (:ch delta)) ")'>"
                        "<line class='delta' x1='0' y1='0' x2='-100' y2='"
                        (- (actx-y (:actx delta)) (ch-y (:ch delta)))
                        "' stroke='" (if (:msg delta) "green" "black")
                        "' stroke-width='1' marker-end='url(#triangle)'/>"
                        "</g>"])
               :actx-start (when (> (actx-y (:child-actx delta)) line-height)
                             ["<g transform='translate(30," (actx-y (:actx delta)) ")'>"
                              "<line class='delta' x1='0' y1='0' x2='30' y2='"
                              (- (actx-y (:child-actx delta)) (actx-y (:actx delta)))
                              "' stroke='green' stroke-width='1' marker-end='url(#triangle)'/>"
                              "</g>"])
               :actx-end (when (> (actx-y (:child-actx delta)) line-height)
                           ["<g transform='translate(60," (actx-y (:child-actx delta)) ")'>"
                            "<line class='delta' x1='0' y1='0' x2='-30' y2='"
                            (- (actx-y (:actx delta)) (actx-y (:child-actx delta)))
                            "' stroke='black' stroke-width='1' marker-end='url(#triangle)'/>"
                            "</g>"])
               nil))
           deltas)]))

;; ------------------------------------------------

(defn vis-init [cmds cmd-handlers]
  (let [max-inflight (atom 10)
        event-delay (atom 0)
        event-ch (chan)
        step-ch (chan)
        last-id (atom 0)
        gen-id #(swap! last-id inc)
        w [{:gen-id gen-id
            :event-ch event-ch}]
        root-actx (atom nil)
        vis (atom {:actxs {} ; {actx -> {:children {child-actx -> true},
                             ;           :wait-chs {ch -> (:take|:put)}}}.
                   :chs {}   ; {ch -> {:id (gen-id),
                             ;         :msgs {msg -> true}
                             ;         :first-taker-actx actx-or-nil}}.
                   :gen-id gen-id
                   :last-id (fn [] @last-id)})
        run-controls {"run"      #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay 0))
                      "run-slow" #(do (when (< @event-delay 0) (put! step-ch true))
                                      (reset! event-delay
                                              (js/parseInt (get-el-value "run-slowness"))))
                      "pause"    #(reset! event-delay -1)
                      "step"     #(do (reset! event-delay -1)
                                      (put! step-ch true))}]
    (go-loop [run-control-ch (merge (map #(listen (gdom/getElement %) "click")
                                         (keys run-controls)))]
      ((get run-controls (.-id (.-target (<! run-control-ch)))))
      (recur run-control-ch))
    (go-loop [num-events 0 vis-last nil vis-last-positions nil]
      (when (> @event-delay 0) (<! (timeout @event-delay)))
      (when (< @event-delay 0) (<! step-ch))
      (let [[actx event] (<! event-ch)
            [verb step & args] event
            vis-positions (atom {})
            vis-event-handler (get (get vis-event-handlers verb) step)
            deltas (vis-event-handler vis actx args)
            actx-ch-ch-infos (group-by #(:first-taker-actx (second %)) (:chs @vis))]
        (set-el-innerHTML "event"
                          (str num-events ": " (last actx) " " verb " " step " " args))
        (when (and (not (zero? @event-delay)) (not-empty deltas) vis-last vis-last-positions)
          (set-el-innerHTML "vis-svg"
                            (apply str (flatten (vis-svg-actxs vis-last vis-last-positions deltas))))
          (when (> @event-delay 0) (<! (timeout @event-delay)))
          (when (< @event-delay 0) (<! step-ch)))
        (set-el-innerHTML "vis-html"
                          (apply str (flatten (vis-html-actx @vis @root-actx vis-positions
                                                             actx-ch-ch-infos))))
        (set-el-innerHTML "vis-svg"
                          (apply str (flatten (vis-svg-actxs @vis @vis-positions nil))))
        (recur (inc num-events) @vis @vis-positions)))
    (ago world w
         (reset! root-actx world)
         (let [in (achan-buf world 100)
               out (achan-buf world 0)]
           (ago-loop main-in world [num-ins 0]
                     (let [cmd (<! cmds)
                           cmd-handler ((get cmd-handlers (:op cmd)) cmd)]
                       (aput main-in in cmd-handler)
                       (recur (inc num-ins))))
           (ago-loop main-out world [num-outs 0]
                     (let [result (atake main-out out)]
                       (set-el-innerHTML "output" result)
                       (recur (inc num-outs))))
           (make-fenced-pump world in out @max-inflight)))))

;; ------------------------------------------------

(defn example-add [actx x y delay]
  (ago example-add actx
       (<! (timeout delay))
       (+ x y)))

(defn example-sub [actx x y delay]
  (ago example-sub actx
       (<! (timeout delay))
       (- x y)))

(def example-cmd-handlers
  {"add"  (fn [cmd] {:rq #(example-add % (:x cmd) (:y cmd) (:delay cmd))
                     :fence (:fence cmd)})
   "sub"  (fn [cmd] {:rq #(example-sub % (:x cmd) (:y cmd) (:delay cmd))
                     :fence (:fence cmd)})
   "test" (fn [cmd] {:rq #(cbfg.fence/test %)
                     :fence (:fence cmd)})})

(vis-init (map< (fn [ev] {:op (.-id (.-target ev))
                          :x (js/parseInt (get-el-value "x"))
                          :y (js/parseInt (get-el-value "y"))
                          :delay (js/parseInt (get-el-value "delay"))
                          :fence (= (get-el-value "fence") "1")})
                (merge (map #(listen (gdom/getElement %) "click")
                            (keys example-cmd-handlers))))
          example-cmd-handlers)
