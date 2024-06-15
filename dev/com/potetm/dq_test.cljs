(ns com.potetm.dq-test
  (:require-macros
    [com.potetm.dq :as dq])
  (:require
    [cljs.core.async :as a]
    [cljs.core.async.interop :as ai]
    [cljs.test :refer [deftest testing is async use-fixtures]]
    [clojure.edn :as edn]
    [cognitect.transit :as t]
    [com.potetm.dq :as dq]
    [com.potetm.indexeddb :as idb]))


(def edn-settings
  {::dq/read edn/read-string
   ::dq/write pr-str
   ::dq/db-name "testdb"
   ::dq/queues {:qname/local-sync {}}})


(def edn-settings:strict
  {::dq/read edn/read-string
   ::dq/write pr-str
   ::dq/db-name "testdb-strict"
   ::dq/queues {:qname/local-sync {::dq/tx-opts {"durability" "strict"}}}})


(def transit-settings
  {::dq/read (let [r (t/reader :json)]
               #(t/read r %))
   ::dq/write (let [w (t/writer :json)]
                #(t/write w %))
   ::dq/db-name "testdb-transit"
   ::dq/queues {:qname/local-sync {}}})


(defn del-db [db-name]
  (js/Promise.
    (fn [yes no]
      (let [req (js/indexedDB.deleteDatabase db-name)]
        (idb/promise-handlers yes no req)))))


(defn get-all [settings qname]
  (dq/js-await [db (idb/db settings)]
    (let [qname (name qname)
          [tx _p] (idb/tx db
                          #js[qname]
                          "readonly")
          q-os (idb/obj-store tx qname)]
      (idb/get-all q-os))))


(use-fixtures :once
              {:before (fn []
                         (async done
                           (dq/js-await [_ (del-db (::dq/db-name edn-settings))
                                         _ (del-db (::dq/db-name edn-settings:strict))
                                         _ (del-db (::dq/db-name transit-settings))]
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
                                 v)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 0 (count vs)))
          (done))))))


(deftest hello:transit
  (testing "it works!"
    (async done
      (dq/js-await [_ (dq/push! transit-settings
                                :qname/local-sync
                                {:foo :bar})
                    v (dq/receive! transit-settings
                                   :qname/local-sync)]
        (is (= v {:foo :bar}))
        (is (pos? (::dq/id (meta v))))
        (is (= 1 (::dq/try-num (meta v))))
        (dq/js-await [_ (dq/ack! transit-settings
                                 :qname/local-sync
                                 v)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 0 (count vs)))
          (done))))))


(deftest hello:strict
  (testing "it works!"
    (async done
      (dq/js-await [_ (dq/push! edn-settings:strict
                                :qname/local-sync
                                {:foo :bar})
                    v (dq/receive! edn-settings:strict
                                   :qname/local-sync)]
        (is (= v {:foo :bar}))
        (is (pos? (::dq/id (meta v))))
        (is (= 1 (::dq/try-num (meta v))))
        (dq/js-await [_ (dq/ack! edn-settings:strict
                                 :qname/local-sync
                                 v)
                      vs (get-all edn-settings
                                  :qname/local-sync)]
          (is (= 0 (count vs)))
          (done))))))


(deftest hello:core-async
  (testing "it works!"
    (async done
      (a/go
        (ai/<p! (dq/push! edn-settings:strict
                          :qname/local-sync
                          {:foo :bar}))
        (let [v (ai/<p! (dq/receive! edn-settings:strict
                                     :qname/local-sync))]
          (is (= v {:foo :bar}))
          (is (pos? (::dq/id (meta v))))
          (is (= 1 (::dq/try-num (meta v))))
          (ai/<p! (dq/ack! edn-settings:strict
                           :qname/local-sync
                           v))
          (let [vs (ai/<p! (get-all edn-settings
                                    :qname/local-sync))]
            (is (= 0 (count vs)))
            (done)))))))


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
                                   v')
                        vs (get-all edn-settings
                                    :qname/local-sync)]
            (is (= 0 (count vs)))
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
                                        :qname/local-sync)
                        vs (get-all edn-settings
                                    :qname/local-sync)]
            (is (= 0 (count vs)))
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
                                  :qname/local-sync)
                      ifs (get-all edn-settings
                                   (idb/in-flight-store-name (idb/store-name :qname/local-sync)))]
          (is (= 10 (count vs)))
          (is (= 0 (count ifs)))
          (dq/js-await [_ (dq/truncate! edn-settings
                                        :qname/local-sync)
                        vs (get-all edn-settings
                                    :qname/local-sync)]
            (is (= 0 (count vs)))
            (done)))))))


