(ns com.potetm.dq
  (:require-macros
    [com.potetm.dq :as pq])
  (:require
    [com.potetm.indexeddb :as idb]
    [goog.object :as obj]))


(defn in-flight-q [qname]
  (str qname "-in-flight"))


(defn compile-settings [{qs ::pq/queues :as s}]
  (assoc s
    ::schema
    (mapcat (fn [{qn ::pq/queue-name}]
              [{:store/name (name qn)
                :store/opts #js{"keyPath" "id"
                                "autoIncrement" true}}
               {:store/name (in-flight-q (name qn))
                :store/opts #js{"keyPath" "id"}}])
            qs)))


(defn push! [{w ::write :as settings} qname msg]
  (let [qname (name qname)]
    (pq/js-await [db (idb/db settings)]
      (let [tx (idb/tx db
                       #js[qname]
                       "readwrite")]
        (idb/put (idb/obj-store tx qname)
                 #js{"msg" (w msg)
                     "try-num" 0})))))


(defn receive!
  ([settings qname]
   (.then (receive! settings qname 1)
          (fn [[v]]
            v)))
  ([{r ::read :as settings} qname n]
   (let [qname (name qname)
         ifq (in-flight-q qname)]
     (pq/js-await [db (idb/db settings)]
       (let [tx (idb/tx db
                        #js[qname ifq]
                        "readwrite")
             q-os (idb/obj-store tx qname)
             ifq-os (idb/obj-store tx ifq)]
         (pq/js-await [vs (idb/get-all q-os
                                       nil
                                       n)]
           (when (seq vs)
             (js/Promise.all
               (.map vs
                     (fn [v]
                       (let [tn (inc (obj/get v "try-num"))]
                         (pq/js-await [_ (idb/put ifq-os (doto v
                                                           (obj/set "try-num"
                                                                    tn)))
                                       _ (idb/del q-os (obj/get v "id"))]
                           (with-meta (r (obj/get v "msg"))
                                      {::id (obj/get v "id")
                                       ::try-num tn})))))))))))))


(defn ack! [settings qname msg]
  (let [qname (name qname)
        ifq (in-flight-q qname)]
    (pq/js-await [db (idb/db settings)]
      (let [tx (idb/tx db
                       #js[qname ifq]
                       "readwrite")
            ifq-os (idb/obj-store tx ifq)]
        (idb/del ifq-os (::id (meta msg)))))))


(defn ack-all! [settings qname msgs]
  (let [qname (name qname)
        ifq (in-flight-q qname)]
    (pq/js-await [db (idb/db settings)]
      (let [tx (idb/tx db
                       #js[qname ifq]
                       "readwrite")
            ifq-os (idb/obj-store tx ifq)]
        (js/Promise.all
          (into-array (mapv (fn [msg]
                              (idb/del ifq-os (::id (meta msg))))
                            msgs)))))))


(defn fail! [{w ::write :as settings} qname msg]
  (let [qname (name qname)
        ifq (in-flight-q qname)]
    (pq/js-await [db (idb/db settings)]
      (let [tx (idb/tx db
                       #js[qname ifq]
                       "readwrite")
            q-os (idb/obj-store tx qname)
            ifq-os (idb/obj-store tx ifq)
            {k ::id
             tn ::try-num} (meta msg)]
        (pq/js-await [_ (idb/put q-os
                                 #js{"id" k
                                     "try-num" tn
                                     "msg" (w msg)})]
          (idb/del ifq-os k))))))


(defn fail-all!
  ([settings qname]
   (let [qname (name qname)
         ifq (in-flight-q qname)]
     (pq/js-await [db (idb/db settings)]
       (let [tx (idb/tx db
                        #js[qname ifq]
                        "readwrite")
             q-os (idb/obj-store tx qname)
             ifq-os (idb/obj-store tx ifq)]
         (pq/js-await [msgs (idb/get-all ifq-os)
                       _ (js/Promise.all
                           (.map msgs
                                 (fn [m]
                                   (idb/put q-os
                                            m))))]
           (js/Promise.all
             (.map msgs
                   (fn [m]
                     (idb/del ifq-os
                              (obj/get m "id"))))))))))
  ([{w ::write :as settings} qname msgs]
   (let [qname (name qname)
         ifq (in-flight-q qname)]
     (pq/js-await [db (idb/db settings)]
       (let [tx (idb/tx db
                        #js[qname ifq]
                        "readwrite")
             q-os (idb/obj-store tx qname)
             ifq-os (idb/obj-store tx ifq)]
         (pq/js-await [_ (js/Promise.all
                           (int-array (mapv (fn [m]
                                              (let [met (meta m)]
                                                (idb/put q-os
                                                         #js{"msg" (w m)
                                                             "id" (::id met)
                                                             "try-num" (::try-num met)})))
                                            msgs)))]
           (js/Promise.all
             (into-array (mapv (fn [m]
                                 (idb/del ifq-os
                                          (::id (meta m))))
                               msgs)))))))))


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

  (.then (fail! "local-sync" @msg)
         (fn [v]
           (js/console.log v)))

  (fail-all! "local-sync")
  )

