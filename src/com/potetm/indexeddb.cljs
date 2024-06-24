(ns com.potetm.indexeddb
  (:refer-clojure :exclude [get])
  (:require
    [com.potetm.dq :as-alias dq]))


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


(defn store-name [qname]
  (str qname))


(defn in-flight-store-name [store-name]
  (str store-name ":if"))


(defn db [{dbn ::dq/db-name
           v ::dq/db-version
           qs ::dq/queues
           as ::dq/additional-stores
           :or {v 1}}]
  (js/Promise.
    (fn [yes no]
      (let [req (js/indexedDB.open dbn v)]
        (.addEventListener req
                           "upgradeneeded"
                           (fn []
                             (let [db (.-result req)
                                   osns (.-objectStoreNames db)]
                               (doseq [[qn _opts] qs]
                                 (let [sn (store-name qn)
                                       ifsn (in-flight-store-name sn)]
                                   (when-not (.contains osns sn)
                                     (.createObjectStore db
                                                         sn
                                                         #js{"keyPath" "id"
                                                             "autoIncrement" true}))
                                   (when-not (.contains osns ifsn)
                                     (.createObjectStore db
                                                         ifsn
                                                         #js{"keyPath" "id"}))))
                               (doseq [{sn ::dq/store-name
                                        opts ::dq/store-opts} as]
                                 (when-not (.contains osns sn)
                                   (.createObjectStore db
                                                       sn
                                                       opts))))))
        (promise-handlers yes no req)))))


(defn tx
  ([db store-name mode]
   (tx db store-name mode nil))
  ([db store-name mode options]
   (let [t (.transaction db store-name mode options)]
     [t
      (js/Promise.
        (fn [yes no]
          (.addEventListener t
                             "complete"
                             (fn [e]
                               (yes e)))
          (.addEventListener t
                             "error"
                             (fn [e]
                               (no (ex-info "Error opening DB"
                                            {::error e}))))))])))


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


(defn clear [os]
  (js/Promise.
    (fn [yes no]
      (let [req (.clear os)]
        (promise-handlers yes no req)))))
