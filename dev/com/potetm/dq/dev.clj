(ns com.potetm.dq.dev
  (:require
    [com.potetm.dq :as dq]))


(defmacro time-promise [& promise]
  `(let [start# (js/Date.)]
     (dq/js-await [ret# ~@promise]
       (println (str "Elapsed time: "
                     (- (.getTime (js/Date.))
                        (.getTime start#))
                     "ms"))
       ret#)))


(defmacro time-promise-ms [n & promise]
  `(let [start# (js/Date.)]
     (dq/js-await [_ret# (js/Promise.all
                           (into-array (map (fn [_i#]
                                              ~@promise)
                                            (range ~n))))]
       (println (str "Average elapsed time: "
                     (double (/ (- (.getTime (js/Date.))
                                   (.getTime start#))
                                ~n))
                     "ms")))))
