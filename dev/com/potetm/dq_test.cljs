(ns com.potetm.dq-test
  (:require-macros
    [com.potetm.dq :as dq])
  (:require
    [cljs.test :refer [deftest testing is async]]
    [clojure.edn :as edn]
    [com.potetm.dq :as dq]))

(deftest hello
  (testing "it works!"
    (let [settings (dq/compile-settings
                     {::dq/read edn/read-string
                      ::dq/write pr-str
                      ::dq/db-name "foobar"
                      ::dq/queues [{::dq/queue-name :qname/local-sync}]})]
      (async done
        (dq/js-await [_ (dq/push! settings
                                  :qname/local-sync
                                  {:foo :bar})
                      v (dq/receive! settings
                                     :qname/local-sync)]
          (is (= v {:foo :bar}))
          (is (pos? (::dq/id (meta v))))
          (is (= 1 (::dq/try-num (meta v))))
          (dq/js-await [_ (dq/ack! settings
                                   :qname/local-sync
                                   v)]
            (done)))))))
