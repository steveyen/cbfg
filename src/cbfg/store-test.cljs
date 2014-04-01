(ns cbfg.store-test
  (:require-macros [cbfg.ago :refer [achan aclose ago atake]])
  (:require [cljs.core.async :refer [into]]
            [cbfg.store :as store]))

(defn e [n result expect]
  (println (str (swap! n inc) ":")
           (if (= result expect)
             (str "pass: " (:key expect))
             (str "FAIL:" result expect)))
  (= result expect))

(defn test [actx opaque-id]
  (ago test actx
       (let [n (atom 0)
             s (store/make-store test)]
         (if (and (e n (atake test (store/store-get test s 1 "does-not-exist"))
                     {:status :not-found, :key "does-not-exist", :opaque 1})
                  (e n (atake test (store/store-get test s 2 "does-not-exist2"))
                     {:status :not-found, :key "does-not-exist2", :opaque 2})
                  (e n (atake test (store/store-del test s 3 "does-not-exist" 0))
                     {:status :not-found, :key "does-not-exist", :opaque 3})
                  (e n (atake test (store/store-set test s 4 "does-not-exist" 0 "" :replace))
                     {:status :not-found, :key "does-not-exist", :opaque 4}))
           "pass"
           (str "FAIL: on test #" @n)))))
