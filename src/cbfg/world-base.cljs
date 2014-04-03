(ns cbfg.world-base
  (:require-macros [cbfg.ago :refer [ago aclose achan aput atake atimeout]])
  (:require [cbfg.vis :refer [vis-init listen-el get-el-value set-el-innerHTML]]))

(defn filter-r [r] (if (map? r)
                     (-> r
                         (dissoc :opaque)
                         (dissoc :op)
                         (dissoc :fence))
                     r))

(defn render-client-cmds [client-cmds]
  (set-el-innerHTML "client"
                    (apply str
                           (flatten ["<table>"
                                     "<tr><th>op</th><th>fence</th>"
                                     "    <th>request</th><th>timeline</th></tr>"
                                     (map (fn [[opaque [request responses]]]
                                            ["<tr class='"
                                             (when (some #(:result (second %)) responses)
                                               "complete") "'>"
                                             " <td>" (:op request) "</td>"
                                             " <td>" (:fence request) "</td>"
                                             " <td>" (filter-r request) "</td>"
                                             " <td class='responses'><ul>"
                                             "<li style='list-type: none; margin-left: "
                                             opaque "em;'>request</li>"
                                             (map (fn [[out-time response]]
                                                    ["<li style='margin-left: " out-time "em;'>"
                                                     (-> (filter-r response)
                                                         (dissoc :lane)
                                                         (dissoc :delay))
                                                     "</li>"])
                                                  (reverse responses))
                                             "</ul></td>"
                                             "</tr>"])
                                          (sort #(compare [(:lane (first (second %1))) (first %1)]
                                                          [(:lane (first (second %2))) (first %2)])
                                                client-cmds))
                                     "</table>"]))))

;; ------------------------------------------------

(defn example-add [actx c]
  (ago example-add actx
       (let [timeout-ch (atimeout example-add (:delay c))]
         (atake example-add timeout-ch)
         (assoc c :result (+ (:x c) (:y c))))))

(defn example-sub [actx c]
  (ago example-sub actx
       (let [timeout-ch (atimeout example-sub (:delay c))]
         (atake example-sub timeout-ch)
         (assoc c :result (- (:x c) (:y c))))))

(defn example-count [actx c]
  (let [out (achan actx)]
    (ago example-count actx
         (doseq [n (range (:x c) (:y c))]
           (let [timeout-ch (atimeout example-count (:delay c))]
             (atake example-count timeout-ch))
           (aput example-count out (assoc c :partial n)))
         (let [timeout-ch (atimeout example-count (:delay c))]
           (atake example-count timeout-ch))
         (aput example-count out (assoc c :result (:y c)))
         (aclose example-count out))
    out))
