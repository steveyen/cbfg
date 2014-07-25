(ns cbfg.kvs
  (:require-macros [cbfg.act :refer [act act-loop achan aput-close aput atake]])
  (:require [cbfg.misc :refer [dissoc-in]]))

; A kvs is an abstraction of ordered-KV store.  Concrete examples
; would include couchkvs, forestDB, rocksDB, sqlite, bdb, gklite.
; The mapping is ordered key => sq => item, and ordered sq => item.
; Abstract operations include...
; - multi-get
; - multi-change (a change is an upsert or delete).
; - scan-keys
; - scan-changes
; - fsync
; - stats
; - has compaction/maintenance abstraction

; TODO: Track age, utilization, compaction for simulated performance.
; TODO: Cannot use atoms because they prevent time travel.
; TODO: Add snapshot ability.
; TODO: Readers not blocked by writers.
; TODO: Mutations shadow old values.
; TODO: Conventions on :status, :status-info, :partial, :result responses.

(defn make-kc [] ; A kc is a keys / changes map.
  {:keys (sorted-map)      ; key -> sq
   :changes (sorted-map)}) ; [sq key] -> {:key k, :sq s, :deleted true | :val v}

(defn kc-sq-by-key [kc key]
  (get (:keys kc) key))

(defn kc-entry-by-key-sq [kc key sq]
  (get (:changes kc) [sq key]))

(defn kc-entry-by-key [kc key]
  (kc-entry-by-key-sq kc key (kc-sq-by-key kc key)))

(defn make-kvs [name uuid]
  {:name name
   :uuid uuid
   :clean (make-kc) ; Read-only.
   :dirty (make-kc) ; Mutations go here, until "persisted / made durable".
   :next-sq 1})

(defn kvs-checker [kvs-ident work-fn]
  (fn [state] ; Returns wrapper fn to check if kvs-ident is current.
    (if-let [[name uuid] kvs-ident]
      (if-let [kvs (get-in state [:kvss name])]
        (if (= uuid (:uuid kvs))
          (work-fn state kvs)
          [state {:status :mismatch :status-info :wrong-uuid}])
        [state {:status :not-found :status-info :no-kvs}])
      [state {:status :invalid :status-info :no-kvs-ident}])))

(defn kvs-do [actx state-ch m done-ch work-fn]
  (act kvs-do actx
       (aput kvs-do state-ch [work-fn done-ch])
       (when done-ch
         (aput kvs-do (:res-ch m)
               (merge m (or (atake kvs-do done-ch)
                            {:status :error :status-info :closed-ch}))))))

(defn make-scan-fn [scan-kind result-key get-entry]
  (fn [actx state-ch m]
     (let [{:keys [from to res-ch]} m
           res-m (dissoc m :status :status-info :partial)
           work-fn (fn [state kvs]
                     (act scan-work actx
                          (let [kc-clean (:clean kvs)]
                            (doseq [[k v]
                                    (subseq (into (sorted-map) (scan-kind kc-clean))
                                            >= from < to)]
                              (when-let [entry (get-entry kc-clean k v)]
                                (when (not (:deleted entry))
                                  (aput scan-work res-ch
                                        (merge res-m {:partial :ok
                                                      result-key key
                                                      :entry entry})))))
                            (aput scan-work res-ch
                                  (merge res-m {:status :ok}))))
                     nil)]
       (kvs-do actx state-ch m nil (kvs-checker (:kvs-ident m) work-fn)))))

(def op-handlers
  {:kvs-open
   (fn [actx state-ch m]
     (kvs-do actx state-ch m (achan actx)
                 #(if-let [name (:name m)]
                    (if-let [kvs (get-in % [:kvss name])]
                      [% {:status :ok :status-info :reopened
                          :kvs-ident [name (:uuid kvs)]}]
                      [(-> %
                           (update-in [:kvss name] (make-kvs name (:next-uuid %)))
                           (assoc :next-uuid (inc (:next-uuid %))))
                       {:status :ok :status-info :created
                        :kvs-ident [name (:next-uuid %)]}])
                    [% {:status :invalid :status-info :no-name}])))

   :kvs-remove
   (fn [actx state-ch m]
     (kvs-do actx state-ch m (achan actx)
             (kvs-checker (:kvs-ident m)
                          (fn [state kvs]
                            [(dissoc-in state [:kvss (:name kvs)])
                             {:status :ok :status-info :removed}]))))

   :multi-get
   (fn [actx state-ch m]
     (let [keys (:keys m)
           res-m (dissoc m :status :status-info :partial :keys)
           res-ch (:res-ch m)
           work-fn (fn [state kvs]
                     (act multi-get actx
                          (let [kc-clean (:clean kvs)]
                            (doseq [key keys]
                              (if-let [entry (kc-entry-by-key kc-clean key)]
                                (aput multi-get res-ch
                                      (merge res-m {:partial :ok :key key :entry entry}))
                                (aput multi-get res-ch
                                      (merge res-m {:partial :not-found :key key}))))
                            (aput multi-get res-ch
                                  (merge res-m {:status :ok}))))
                     nil)]
       (kvs-do actx state-ch m nil
                   (kvs-checker (:kvs-ident m) work-fn))))

   :multi-change
   (fn [actx state-ch m]
     (let [res-m (dissoc m :status :status-info :partial :changes)
           res-ch (:res-ch m)
           work-fn (fn [state kvs]
                     (let [next-sq (:next-sq kvs)
                           [next-kvs ress] (reduce (fn [[kvs ress] change]
                                                     (let [[kvs res] (change kvs next-sq)]
                                                       [kvs (conj ress res)]))
                                                   [(assoc kvs :next-sq (inc next-sq)) []]
                                                   (:changes m))]
                       (act multi-change actx
                            (doseq [res ress]
                              (aput multi-change res-ch (merge res-m res)))
                            (aput multi-change res-ch (merge res-m {:status :ok})))
                       [(assoc-in state [:kvss (:name kvs)] next-kvs) nil]))]
       (kvs-do actx state-ch m nil
               (kvs-checker (:kvs-ident m) work-fn))))

   :scan-keys
   (make-scan-fn :keys :key kc-entry-by-key-sq)

   :scan-changes
   (make-scan-fn :changes :sq (fn [kc sq-key entry] entry))

   :sync
   (fn [actx state-ch m]
     (kvs-do actx state-ch m (achan actx)
             (kvs-checker (:kvs-ident m)
                          (fn [state kvs]
                            [(assoc-in state [:kvss (:name kvs)]
                                       (-> kvs
                                           (assoc :clean (:dirty kvs))
                                           (assoc :dirty (make-kc))))
                             {:status :ok :status-info :synced}]))))})

(defn make-kvs-mgr [actx & op-handlers-in & {:keys [cmd-ch state-ch]}]
  (let [cmd-ch (or cmd-ch (achan actx))
        state-ch (or state-ch (achan actx))]
    (act-loop kvs-mgr-in actx [tot-ops 0]
              (if-let [m (atake kvs-mgr-in cmd-ch)]
                (if-let [op-handler (get (or op-handlers-in op-handlers) (:op m))]
                  (do (op-handler kvs-mgr-in state-ch m)
                      (recur (inc tot-ops)))
                  (println :kvs-mgr-in-unknown-op-exit m))
                (println :kvs-mgr-in-ch-exit)))

    ; Using kvs-mgr-state to avoid atoms, which prevent time-travel.
    (act-loop kvs-mgr-state actx [state { :next-uuid 0 :kvss {} }]
              (if-let [[state-fn done-ch] (atake kvs-mgr-state state-ch)]
                (let [[next-state done-res] (state-fn state)]
                  (when (and done-ch done-res)
                    (aput-close kvs-mgr-state done-ch done-res))
                  (recur (or next-state state)))
                (println :kvs-mgr-state-ch-exit)))
    cmd-ch))
