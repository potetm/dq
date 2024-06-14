(ns com.potetm.indexeddb
  (:refer-clojure :exclude [get])
  (:require
    [com.potetm.dq :as-alias dq]
    [goog.object :as obj]))


;; Story time:
;;
;; indexedDB transactions are "autocompleted" when the microtask queue is empty.
;; That makes core.async have a couple of problems:
;;    1. There are no semantic guarantees about schedule timing
;;    2. AFAICT it uses a mix of setTimeout and goog.async.nexttick, both of which
;;       avoid the microtask queue.
;;
;; The only way to make sure we're not prematurely closing transactions is to
;; use raw promises to stay on the microtask queue.


(defn promise-handlers [yes no req]
  (.addEventListener req
                     "success"
                     (fn []
                       (yes (.-result req))))
  (.addEventListener req
                     "error"
                     (fn [e]
                       (no (ex-info "Error opening DB"
                                    {::error e})))))


(defn db [{dbn ::dq/db-name
           s ::dq/schema}]
  (js/Promise.
    (fn [yes no]
      (let [req (js/indexedDB.open dbn 1)]
        (.addEventListener req
                           "upgradeneeded"
                           (fn []
                             (let [db (.-result req)]
                               (doseq [{sn :store/name
                                        opts :store/opts} s]
                                 (when-not (.contains (.-objectStoreNames db)
                                                      sn)
                                   (.createObjectStore db
                                                       sn
                                                       opts))))))
        (promise-handlers yes no req)))))


(defn tx [db store-name mode]
  (.transaction db store-name mode))


(defn obj-store [tx store-name]
  (.objectStore tx store-name))


(defn put [os data]
  (js/Promise.
    (fn [yes no]
      (let [req (.put os data)]
        (promise-handlers yes no req)))))


(defn get-all-keys
  ([os]
   (get-all-keys os nil))
  ([os range]
   (get-all-keys os range nil))
  ([os range limit]
   (js/Promise.
     (fn [yes no]
       (let [req (.getAllKeys os range limit)]
         (promise-handlers yes no req))))))


(defn get-all
  ([os]
   (get-all os nil))
  ([os range]
   (get-all os range nil))
  ([os range limit]
   (js/Promise.
     (fn [yes no]
       (let [req (.getAll os range limit)]
         (promise-handlers yes no req))))))


(defn get [os k]
  (js/Promise.
    (fn [yes no]
      (let [req (.get os k)]
        (promise-handlers yes no req)))))


(defn del [os id]
  (js/Promise.
    (fn [yes no]
      (let [req (.delete os id)]
        (promise-handlers yes no req)))))
