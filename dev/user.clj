(ns user
  (:require
    [clojure.java.io :as io]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.server :as shadow-server]
    [shadow.cljs.devtools.server.runtime :as rt])
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
  (when (rt/get-instance)
    (shadow/stop-worker :dq))
  (shadow-server/stop!))

(defn refresh []
  (stop)
  (clean)
  (start))

(comment
  (refresh)
  (clean)
  (start)

  (shadow/repl :dq)
  (shadow/watch-set-autobuild! :dq false)
  (shadow/watch-set-autobuild! :dq true)
  )
