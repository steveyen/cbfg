(ns cbfg.ago
    (:require [cljs.core.async.macros :refer [go go-loop]]))

(defmacro ago [ainfo & body]
  `(go ~@body))

(defmacro ago-loop [ainfo bindings & body]
  `(go (loop ~bindings ~@body)))

(defmacro aput [ch msg]
  `(cljs.core.async/>! ~ch ~msg))

(defmacro atake [ch]
  `(cljs.core.async/<! ~ch))
