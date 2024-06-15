(ns com.potetm.dq
  (:require-macros
    [com.potetm.dq :as dq])
  (:require
    [com.potetm.indexeddb :as idb]))


(defn tx-opts [settings qn]
  ;; Avoid clj->js since this is called on every op.
  (js-obj
    "durability" (get-in settings
                         [::queues qn ::tx-opts "durability"]
                         "default")))


(defn push-batch! [{w ::write :as settings} qname msgs]
  (let [sn (idb/store-name qname)]
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
  (let [sn (idb/store-name qname)
        ifsn (idb/in-flight-store-name sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifsn]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifsn)]
        (dq/js-await [vs (idb/get-all q-os
                                      nil
                                      n)]
          (when (seq vs)
            (dq/js-await [ret (js/Promise.all
                                (.map vs
                                      (fn [v]
                                        (let [id (unchecked-get v "id")
                                              tn (inc (unchecked-get v "try-num"))]
                                          (dq/js-await [_ (idb/put ifq-os
                                                                   (doto v
                                                                     (unchecked-set "try-num" tn)))
                                                        _ (idb/del q-os id)]
                                            (vary-meta (r (unchecked-get v "msg"))
                                                       assoc
                                                       ::id id
                                                       ::try-num tn))))))]
              (.then p
                     (fn [_]
                       ret)))))))))


(defn receive! [settings qname]
  (.then (receive-batch! settings qname 1)
         (fn [[v]]
           v)))


(defn ack-batch! [settings qname msgs]
  (let [sn (idb/store-name qname)
        ifsn (idb/in-flight-store-name sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifsn]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            ifq-os (idb/obj-store tx ifsn)]
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
  (let [sn (idb/store-name qname)
        ifsn (idb/in-flight-store-name sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifsn]
                           "readwrite"
                           (tx-opts settings qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifsn)]
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


(defn fail-all!
  "Fail all in-flight messages. Useful when starting a new process that might
  have left messages hanging."
  [settings qname]
  (let [sn (idb/store-name qname)
        ifsn (idb/in-flight-store-name sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifsn]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifsn)]
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
                                             (unchecked-get m "id")))))]
          (.then p
                 (fn [_]
                   ret)))))))


(defn truncate!
  "Delete all messages. Primarily here to facilitate testing."
  [settings qname]
  (let [sn (idb/store-name qname)
        ifsn (idb/in-flight-store-name sn)]
    (dq/js-await [db (idb/db settings)]
      (let [[tx p] (idb/tx db
                           #js[sn ifsn]
                           "readwrite"
                           (tx-opts settings
                                    qname))
            q-os (idb/obj-store tx sn)
            ifq-os (idb/obj-store tx ifsn)]
        (dq/js-await [ret (idb/clear q-os)
                      ret' (idb/clear ifq-os)]
          (.then p
                 (fn [_]
                   [ret ret'])))))))


(comment
  (require 'clojure.edn)
  (def msg (atom {:foo 'bar}))
  @(def settings {::read clojure.edn/read-string
                  ::write pr-str
                  ::db-name "foo"
                  ::queues {:qname/local-sync {}
                            :qname/remote-sync {}}})



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

