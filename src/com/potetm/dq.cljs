(ns com.potetm.dq
  (:require-macros
    [com.potetm.dq :as dq])
  (:require
    [com.potetm.indexeddb :as idb]
    [goog.object :as obj]))


(defn in-flight-q [qname]
  (str qname "-in-flight"))


(defn compile-settings [{qs ::queues :as s}]
  (assoc s
    ::schema
    (into {}
          (map (fn [{qn ::queue-name
                     tx-opts ::tx-opts}]
                 (let [tx-opts (or (clj->js tx-opts)
                                   #js{"durability" "default"})]
                   [qn [{:store/name (name qn)
                         :store/opts #js{"keyPath" "id"
                                         "autoIncrement" true}
                         :tx/opts tx-opts}
                        {:store/name (in-flight-q (name qn))
                         :store/opts #js{"keyPath" "id"}
                         :tx/opts tx-opts}]])))
          qs)))


(defn store-name [settings qn]
  (get-in settings
          [::schema qn 0 :store/name]))


(defn tx-opts [settings qn]
  (get-in settings
          [::schema qn 0 :tx/opts]))


(defn push-batch! [{w ::write :as settings} qname msgs]
  (let [sn (store-name settings
                       qname)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn]
                           #js["readwrite"]
                           (tx-opts settings
                                    qname))]
        (dq/js-await [ret (js/Promise.all
                            (into-array
                              (map (fn [msg]
                                     (idb/put (idb/obj-store tx sn)
                                              #js{"msg" (w msg)
                                                  "try-num" 0}))
                                   msgs)))]
          (.then p
                 (fn [_]
                   ret)))))))


(defn push! [settings qname msg]
  (push-batch! settings
               qname
               [msg]))


(defn receive-batch! [{r ::read :as settings} qname n]
  (let [sn (store-name settings
                       qname)
        ifq (in-flight-q sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifq]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifq)]
        (dq/js-await [vs (idb/get-all q-os
                                      nil
                                      n)]
          (when (seq vs)
            (dq/js-await [ret (js/Promise.all
                                (.map vs
                                      (fn [v]
                                        (let [tn (inc (obj/get v "try-num"))]
                                          (dq/js-await [_ (idb/put ifq-os (doto v
                                                                            (obj/set "try-num"
                                                                                     tn)))
                                                        _ (idb/del q-os (obj/get v "id"))]
                                            (with-meta (r (obj/get v "msg"))
                                                       {::id (obj/get v "id")
                                                        ::try-num tn}))))))]
              (.then p
                     (fn [_]
                       ret)))))))))


(defn receive! [settings qname]
  (.then (receive-batch! settings qname 1)
         (fn [[v]]
           v)))


(defn ack-batch! [settings qname msgs]
  (let [sn (store-name settings
                       qname)
        ifq (in-flight-q sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifq]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            ifq-os (idb/obj-store tx ifq)]
        (dq/js-await [ret (js/Promise.all
                            (into-array (mapv (fn [msg]
                                                (idb/del ifq-os (::id (meta msg))))
                                              msgs)))]
          (.then p
                 (fn [_]
                   ret)))))))


(defn ack! [settings qname msg]
  (.then (ack-batch! settings
                     qname
                     [msg])
         (fn [[v]]
           v)))


(defn fail-batch! [{w ::write :as settings} qname msgs]
  (let [sn (store-name settings
                       qname)
        ifq (in-flight-q sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifq]
                           "readwrite"
                           (tx-opts settings qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifq)]
        (dq/js-await [_ (js/Promise.all
                          (int-array (mapv (fn [m]
                                             (let [met (meta m)]
                                               (idb/put q-os
                                                        #js{"msg" (w m)
                                                            "id" (::id met)
                                                            "try-num" (::try-num met)})))
                                           msgs)))
                      ret (js/Promise.all
                            (into-array (mapv (fn [m]
                                                (idb/del ifq-os
                                                         (::id (meta m))))
                                              msgs)))]
          (.then p
                 (fn [_]
                   ret)))))))


(defn fail! [settings qname msg]
  (.then (fail-batch! settings
                      qname
                      [msg])
         (fn [[v]]
           v)))


(defn fail-all! [settings qname]
  (let [sn (store-name settings
                       qname)
        ifq (in-flight-q sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifq]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifq)]
        (dq/js-await [msgs (idb/get-all ifq-os)
                      _ (js/Promise.all
                          (.map msgs
                                (fn [m]
                                  (idb/put q-os
                                           m))))
                      ret (js/Promise.all
                            (.map msgs
                                  (fn [m]
                                    (idb/del ifq-os
                                             (obj/get m "id")))))]
          (.then p
                 (fn [_]
                   ret)))))))


(defn truncate! [settings qname]
  (let [qname (store-name settings
                          qname)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[qname]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx qname)]
        (dq/js-await [ret (js/Promise.
                            (fn [yes no]
                              (let [req (.clear q-os)]
                                (idb/promise-handlers yes no req))))]
          (.then p
                 (fn [_]
                   ret)))))))


(comment
  (require 'clojure.edn)
  (def msg (atom {:foo 'bar}))
  @(def settings (compile-settings
                   {::read clojure.edn/read-string
                    ::write pr-str
                    ::db-name "foo"
                    ::queues [{::queue-name :qname/local-sync}
                              {::queue-name :qname/remote-sync}]}))



  (push! settings
         :qname/local-sync
         @msg)
  @msg
  (meta @msg)

  (.then (receive! settings
                   :qname/local-sync)
         (fn [v]
           (reset! msg v)))

  @msg
  (meta @msg)

  (.then (ack! settings
               :qname/local-sync
               @msg)
         (fn [v]
           (js/console.log v)))

  (.then (fail! settings
                :qname/local-sync @msg)
         (fn [v]
           (js/console.log v)))

  )

