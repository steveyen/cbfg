(ns cbfg.world-lane
  (:require-macros [cbfg.ago :refer [ago ago-loop aclose achan achan-buf
                                     aalts aput atake atimeout]])
  (:require [cljs.core.async :refer [chan <! merge map< sliding-buffer]]
            [goog.dom :as gdom]
            [cbfg.vis :refer [vis-init listen-el get-el-value
                              set-el-innerHTML render-client-cmds]]
            [cbfg.fence :refer [make-fenced-pump]]
            [cbfg.lane :refer [make-lane-pump]]
            [cbfg.lane-test]))

(defn example-add [actx opaque x y delay]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add delay)]
         (atake example-add timeout-ch)
         {:opaque opaque :result (+ x y)})))

(defn example-sub [actx opaque x y delay]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub delay)]
         (atake example-sub timeout-ch)
         {:opaque opaque :result (- x y)})))

(defn example-count [actx opaque x y delay]
  (let [out (achan actx)]
    (ago example-count actx
         (doseq [n (range x y)]
           (let [timeout-ch (atimeout example-count delay)]
             (atake example-count timeout-ch))
           (aput example-count out {:opaque opaque :partial n}))
         (let [timeout-ch (atimeout example-count delay)]
           (atake example-count timeout-ch))
         (aput example-count out {:opaque opaque :result y})
         (aclose example-count out))
    out))

(def example-cmd-handlers
  {"add"   (fn [c] {:opaque (:opaque c) :fence (:fence c)
                    :rq #(example-add % (:opaque c) (:x c) (:y c) (:delay c))})
   "sub"   (fn [c] {:opaque (:opaque c) :fence (:fence c)
                    :rq #(example-sub % (:opaque c) (:x c) (:y c) (:delay c))})
   "count" (fn [c] {:opaque (:opaque c) :fence (:fence c)
                    :rq #(example-count % (:opaque c) (:x c) (:y c) (:delay c))})
   "test"  (fn [c] {:opaque (:opaque c) :fence (:fence c)
                    :rq #(cbfg.lane-test/test % (:opaque c))})})

(def example-max-inflight (atom 10))

(defn world-vis-init [el-prefix]
  (let [cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                               :x (js/parseInt (get-el-value "x"))
                               :y (js/parseInt (get-el-value "y"))
                               :delay (js/parseInt (get-el-value "delay"))
                               :fence (= (get-el-value "fence") "1")
                               :lane (get-el-value "lane")})
                     (merge (map #(listen-el (gdom/getElement %) "click")
                                 (keys example-cmd-handlers))))
        client-cmds (atom {})] ; Keyed by opaque -> [request, replies].
    (vis-init (fn [world]
                (let [in-ch (achan-buf world 100)
                      out-ch (achan-buf world 0)]
                  (ago-loop client world [num-ins 0 num-outs 0]
                            (let [[v ch] (aalts client [cmd-ch out-ch])
                                  ts (+ num-ins num-outs)]
                              (cond
                                (= ch cmd-ch) (let [cmd (assoc v :opaque ts)
                                                    cmd-handler ((get example-cmd-handlers (:op cmd)) cmd)]
                                                (render-client-cmds (swap! client-cmds
                                                                           #(assoc % ts [cmd nil])))
                                                (aput client in-ch cmd-handler)
                                                (recur (inc num-ins) num-outs))
                                (= ch out-ch) (do (render-client-cmds (swap! client-cmds
                                                                             #(update-in % [(:opaque v) 1]
                                                                                         conj [ts v])))
                                                  (recur num-ins (inc num-outs))))))
                  (make-fenced-pump world in-ch out-ch @example-max-inflight)))
              el-prefix nil)))

(let [last-id (atom 0)
      gen-id #(swap! last-id inc)]
  (ago lane-test-actx [{:gen-id gen-id :event-ch (chan (sliding-buffer 1))}]
       (println "lane-test:"
                (<! (cbfg.lane-test/test lane-test-actx 0)))))
