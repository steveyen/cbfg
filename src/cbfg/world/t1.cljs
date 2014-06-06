(ns cbfg.world.t1
  (:require-macros [cljs.core.async.macros :refer [go-loop]]
                   [cbfg.act :refer [act act-loop actx-top achan achan-buf aput atake]])
  (:require [cljs.core.async :refer [<! >! close! chan map< merge timeout dropping-buffer]]
            [goog.dom :as gdom]
            [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [ago.core :refer [make-ago-world ago-chan ago-timeout]]
            [cbfg.vis :refer [listen-el get-el-value get-el-innerHTML]]
            [cbfg.net :refer [make-net]]
            [cbfg.lane]
            [cbfg.world.net]
            [cbfg.world.lane]
            [cbfg.world.base :refer [world-cmd-loop]]))

(def prog-base (atom {})) ; { :world => world-actx
                          ;   :net-listen-ch => ch
                          ;   :net-connect-ch => ch
                          ;   :res-ch => ch }

(def prog-history
  (atom {:prog-peers {:servers {}  ; { server-addr => ports }.
                      :clients {}} ; { client-addr => client-info }.
         :prog-events []})) ; Each event is [ts label args].

(def run-world       (atom nil))
(def run-world-hover (atom nil))

; -------------------------------------------------------------------

(defn render-world [app owner]
  (apply dom/ul nil
         (map (fn [[k v]] (dom/li nil (str k ":" (count v))))
              (:prog-peers app))))

(defn on-event-focus [ts label args]
  (reset! run-world-hover args)
  (.add gdom/classes (gdom/getElement "world-container") "hover"))

(defn on-event-blur []
  (reset! run-world-hover nil)
  (.remove gdom/classes (gdom/getElement "world-container") "hover"))

(defn render-events [app owner]
  (apply dom/ul nil
         (map (fn [[ts label args]]
                (dom/li #js {:onMouseEnter #(on-event-focus ts label args)
                             :onMouseLeave #(on-event-blur)}
                        (str ts (apply str label) args)))
              (:prog-events app))))

(defn render-clients [app owner]
  (apply dom/select #js {:id "client"}
         (map (fn [client-addr]
                (dom/option #js {:value client-addr} (str client-addr)))
              (keys (:clients (:prog-peers app))))))

(defn init-roots []
  (om/root render-world run-world
           {:target (. js/document (getElementById "world"))})
  (om/root render-world run-world
           {:target (. js/document (getElementById "world-map"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-hover"))})
  (om/root render-world run-world-hover
           {:target (. js/document (getElementById "world-map-hover"))})
  (om/root render-events prog-history
           {:target (. js/document (getElementById "events"))})
  (om/root render-clients prog-history
           {:target (. js/document (getElementById "controls-clients"))}))

; ------------------------------------------------

(defn world-vis-init [el-prefix init-event-delay]
  (init-roots)
  (let [prog-ch (listen-el (gdom/getElement "prog-go") "click")
        step-ch (chan (dropping-buffer 1))
        event-delay (atom init-event-delay)
        run-controls-ch (cbfg.vis/vis-run-controls event-delay step-ch "header")]
    (go-loop [num-worlds 0]
      (let [last-id (atom 0)
            gen-id #(swap! last-id inc)
            agw (make-ago-world num-worlds)
            get-agw (fn [] agw)
            event-ch (ago-chan agw)
            event-run-ch (chan)
            make-timeout-ch (fn [actx delay] (ago-timeout agw delay))
            ; Init the top actx manually to avoid act recursion.
            w [{:gen-id gen-id
                :get-agw get-agw
                :event-ch event-ch
                :make-timeout-ch make-timeout-ch}]
            world (conj w "world-0")
            vis (atom
                 {:actxs {world {:children {} ; child-actx -> true,
                                 :wait-chs {} ; ch -> [:ghost|:take|:put optional-ch-name],
                                 :collapsed true
                                 ; :loop-state last-loop-bindings,
                                 }}
                  :chs {} ; {ch -> {:id (gen-id), :msgs {msg -> true},
                          ;         :first-taker-actx actx-or-nil}}.
                  :gen-id gen-id})
            delayed-event-cb (fn [vis-next]
                               (println :on-delayed-event-cb @last-id))]
        (reset! prog-base {:world world
                           :net-listen-ch (achan-buf world 10)
                           :net-connect-ch (achan-buf world 10)
                           :res-ch (achan-buf world 10)})
        (cbfg.vis/process-events vis event-delay cbfg.vis/vis-event-handlers
                                 event-ch step-ch event-run-ch)
        (cbfg.vis/process-render el-prefix world event-run-ch delayed-event-cb)
        (make-net world
                  (:net-listen-ch @prog-base)
                  (:net-connect-ch @prog-base))
        (let [vis-chs {}
              req-ch (achan world)
              cmd-ch (map< (fn [ev] {:op (.-id (.-target ev))
                                     :x (js/parseInt (get-el-value "x"))
                                     :y (js/parseInt (get-el-value "y"))
                                     :delay (js/parseInt (get-el-value "delay"))
                                     :fence (= (get-el-value "fence") "1")
                                     :lane (get-el-value "lane")
                                     :client (get-el-value "client")
                                     :color (get-el-value "color")
                                     :sleep (js/parseInt (get-el-value "sleep"))})
                           (merge (map #(listen-el (gdom/getElement %) "click")
                                       (keys cbfg.world.lane/cmd-handlers))))
              expand-ch (listen-el (gdom/getElement (str el-prefix "-html")) "click")
              prog (get-el-value "prog")
              prog-js (str "with (cbfg.world.t1) {" prog "}")
              prog-res (try (js/eval prog-js) (catch js/Object ex ex))]
          (world-cmd-loop world cbfg.world.lane/cmd-handlers cmd-ch
                          req-ch (:res-ch @prog-base)
                          vis-chs world-vis-init el-prefix)
          (act-loop cmd-dispatch-loop world [num-dispatches 0]
                    (when-let [msg (atake cmd-dispatch-loop req-ch)]
                      (when-let [client-req-ch
                                 (get-in @prog-history
                                         [:prog-peers :clients (:client msg) :req-ch])]
                        (aput cmd-dispatch-loop client-req-ch msg))
                      (recur (inc num-dispatches))))
          (println :prog-res prog-res)
          (println :prog-history prog-history)
          (go-loop [] ; Process expand/collapse UI events.
            (let [actx-id (cbfg.vis/no-prefix (.-id (.-target (<! expand-ch))))]
              (doseq [[actx actx-info] (:actxs @vis)]
                (when (= actx-id (last actx))
                  (swap! vis #(assoc-in % [:actxs actx :collapsed]
                                        (not (:collapsed actx-info))))
                  (>! event-run-ch [@vis nil true nil])))
              (recur)))
          (<! prog-ch)
          (close! expand-ch)
          (close! cmd-ch)
          (close! req-ch)
          (close! event-ch)
          (close! event-run-ch)
          (recur (inc num-worlds)))))))

; --------------------------------------------

(defn wait-done [done]
  (loop []
    (when (not @done)
      (cljs.core.async.impl.dispatch/process-messages)
      (recur))))

; --------------------------------------------

(defn prog-event [label f]
  (swap! prog-history #(let [ph (f %)]
                         (update-in ph [:prog-events]
                                    conj [(count (:prog-events ph))
                                          label
                                          {:prog-peers (:prog-peers ph)}]))))

(defn kv-server [server-addr & ports]
  (let [world (:world @prog-base)
        done (atom false)]
    (act server-init world
         (doseq [port ports]
           (when-let [listen-result-ch (achan server-init)]
             (aput server-init (:net-listen-ch @prog-base)
                   [server-addr port listen-result-ch])
             (when-let [[accept-ch close-accept-ch] (atake server-init listen-result-ch)]
               (cbfg.world.net/server-accept-loop world accept-ch close-accept-ch)
               (prog-event [:kv-server server-addr port]
                           #(update-in % [:prog-peers :servers server-addr]
                                       conj port)))))
         (reset! done true))
    (wait-done done)))

(defn kv-client [client-addr server-addr server-port]
  (let [world (:world @prog-base)
        done (atom false)]
    (act client-init world
         (let [req-ch (cbfg.world.net/client-loop world (:net-connect-ch @prog-base)
                                                  server-addr server-port
                                                  client-addr (:res-ch @prog-base))]
           (prog-event [:kv-client client-addr server-addr server-port]
                       #(assoc-in % [:prog-peers :clients client-addr]
                                  {:client-addr client-addr
                                   :server-addr server-addr
                                   :server-port server-port
                                   :req-ch req-ch})))
         (reset! done true))
    (wait-done done)))
