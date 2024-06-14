(ns com.potetm.dq.dev
  (:require
    [cljs.test :as t]
    [cljs-test-display.core :as ctd]
    [shadow.dom :as dom]))

(defn run []
  (dom/append [:div#test-root])
  (t/run-tests (ctd/init! "test-root")
               'com.potetm.dq-test))

(comment
  (run)
  )
