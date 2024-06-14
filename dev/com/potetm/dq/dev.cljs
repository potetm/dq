(ns com.potetm.dq.dev
  (:require-macros
    [com.potetm.dq.dev :as dev])
  (:require
    [cljs.test :as t]
    [cljs-test-display.core :as ctd]
    [com.potetm.dq :as dq]
    [com.potetm.indexeddb :as idb]
    [com.potetm.dq-test :as dqt]
    [shadow.dom :as dom]))

(defn run []
  (dom/append [:div#test-root])
  (t/run-tests (ctd/init! "test-root")
               'com.potetm.dq-test))

(comment
  (run)

  (require 'clojure.edn)
  (def msg (atom {:foo 'bar}))
  @(def settings (dq/compile-settings
                   {::dq/read clojure.edn/read-string
                    ::dq/write pr-str
                    ::dq/db-name "foo"
                    ::dq/queues [{::dq/queue-name :qname/local-sync
                                  ::dq/tx-opts {"durability" "default"}}
                                 {::dq/queue-name :qname/remote-sync}]}))



  (dev/time-promise-ms 100
    (dq/push! settings
              :qname/local-sync
              {:foo :bar}))
  (dev/time-promise
    (dq/truncate! settings
                  :qname/local-sync))
  @msg
  (meta @msg)

  (dev/time-promise-ms 100
    (dq/js-await [_ (dq/push! dqt/edn-settings:strict
                              :qname/local-sync
                              {:foo :bar})
                  v (dq/receive! dqt/edn-settings:strict
                                 :qname/local-sync)]
      (dq/js-await [_ (dq/ack! dqt/edn-settings:strict
                               :qname/local-sync
                               v)]))
    )
  (dev/time-promise-ms 100
    (dq/js-await [_ (dq/push! dqt/edn-settings
                              :qname/local-sync
                              {:foo :bar})
                  v (dq/receive! dqt/edn-settings
                                 :qname/local-sync)]
      (dq/js-await [_ (dq/ack! dqt/edn-settings
                               :qname/local-sync
                               v)]))
    )

  (let [msgs (repeat 10 {:foo :bar})
        qname (name "local-sync")]
    (dev/time-promise-ms 100
      (dq/js-await [db (idb/db settings)]
        (let [[tx p] (idb/tx db
                             #js[qname]
                             "readwrite"
                             #js{"durability" "relaxed"})]
          (dq/js-await [ret (js/Promise.all
                              (into-array
                                (map (fn [msg]
                                       (idb/put (idb/obj-store tx qname)
                                                #js{"msg" (pr-str msg)
                                                    "try-num" 0}))
                                     msgs)))]
            (.then p
                   (fn [_]
                     ret)))))))

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
