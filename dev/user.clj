(ns user
  (:require
    [clojure.java.io :as io]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.server :as shadow-server])
  (:import
    (java.io File)))


(defn clean []
  (doseq [^File f (reverse (file-seq (io/file "./target")))]
    (.delete f)))


(defn start []
  (shadow-server/start!)
  (shadow/watch :dq)
  (shadow/dev :dq))

(defn stop []
  (when (shadow/get-runtime!)
    (shadow/stop-worker :dq))
  (shadow-server/stop!))


(comment
  (clean)
  (start)
  (stop)
  (shadow/test)
  (shadow/repl :dq)
  (shadow/watch-set-autobuild! :dq false)
  (shadow/watch-set-autobuild! :dq true)
  )