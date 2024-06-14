(ns com.potetm.dq-test
  (:require-macros
    [com.potetm.dq :as dq])
  (:require
    [cljs.test :refer [deftest testing is async use-fixtures]]
    [clojure.edn :as edn]
    [com.potetm.dq :as dq]
    [com.potetm.indexeddb :as idb]))


(def edn-settings
  (dq/compile-settings
    {::dq/read edn/read-string
     ::dq/write pr-str
     ::dq/db-name "testdb"
     ::dq/queues [{::dq/queue-name :qname/local-sync}]}))


(defn del-db [db-name]
  (js/Promise.
    (fn [yes no]
      (let [req (js/indexedDB.deleteDatabase db-name)]
        (idb/promise-handlers yes no req)))))


(defn get-all [settings qname]
  (dq/js-await [db (idb/db settings)]
    (let [qname (name qname)
          tx (idb/tx db
                     #js[qname]
                     "readonly")
          q-os (idb/obj-store tx qname)]
      (idb/get-all q-os))))


(use-fixtures :once
              {:before (fn []
                         (async done
                           (dq/js-await [_ (del-db (::dq/db-name edn-settings))]
                             (done))))})

(deftest hello
  (testing "it works!"
    (async done
      (dq/js-await [_ (dq/push! edn-settings
                                :qname/local-sync
                                {:foo :bar})
                    v (dq/receive! edn-settings
                                   :qname/local-sync)]
        (is (= v {:foo :bar}))
        (is (pos? (::dq/id (meta v))))
        (is (= 1 (::dq/try-num (meta v))))
        (dq/js-await [_ (dq/ack! edn-settings
                                 :qname/local-sync
                                 v)]
          (done))))))

(deftest failing-msg
  (testing "failing msg"
    (async done
      (dq/js-await [_ (dq/push! edn-settings
                                :qname/local-sync
                                {:foo :bar})
                    v (dq/receive! edn-settings
                                   :qname/local-sync)]
        (dq/js-await [_ (dq/fail! edn-settings
                                  :qname/local-sync
                                  v)
                      v' (dq/receive! edn-settings
                                      :qname/local-sync)]
          (is (= v v'))
          (is (= 2 (::dq/try-num (meta v'))))
          (dq/js-await [_ (dq/ack! edn-settings
                                   :qname/local-sync
                                   v')]
            (done)))))))


(deftest batch-operations
  (testing "batch operations"
    (async done
      (dq/js-await [_ (dq/push-batch! edn-settings
                                      :qname/local-sync
                                      (map (fn [i]
                                             {:foo i})
                                           (range 10)))
                    vs (dq/receive-batch! edn-settings
                                          :qname/local-sync
                                          10)]
        (is (= 10 (count vs)))
        (dq/js-await [_ (dq/ack-batch! edn-settings
                                       :qname/local-sync
                                       vs)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 0 (count vs)))
          (done))))))


(deftest batch-failing
  (testing "batch failing"
    (async done
      (dq/js-await [_ (dq/push-batch! edn-settings
                                      :qname/local-sync
                                      (map (fn [i]
                                             {:foo i})
                                           (range 10)))
                    vs (dq/receive-batch! edn-settings
                                          :qname/local-sync
                                          10)]
        (is (= 10 (count vs)))
        (dq/js-await [_ (dq/fail-batch! edn-settings
                                        :qname/local-sync
                                        vs)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 10 (count vs)))
          (dq/js-await [_ (dq/truncate! edn-settings
                                        :qname/local-sync)]
            (done)))))))


(deftest fail-all
  (testing "fail all"
    (async done
      (dq/js-await [_ (dq/push-batch! edn-settings
                                      :qname/local-sync
                                      (map (fn [i]
                                             {:foo i})
                                           (range 10)))
                    vs (dq/receive-batch! edn-settings
                                          :qname/local-sync
                                          10)]
        (is (= 10 (count vs)))
        (dq/js-await [_ (dq/fail-all! edn-settings
                                      :qname/local-sync)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 10 (count vs)))
          (dq/js-await [_ (dq/truncate! edn-settings
                                        :qname/local-sync)]
            (done)))))))
