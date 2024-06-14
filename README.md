# DQ: A Durable Queue for the Browser

A fast, durable, async queue for the browser backed by
[IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API).
Guarantees:

* Messages will not be lost (even after page refresh)<sup>†</sup>
* Messages will be delivered in order
    * Retries will be delivered prior to new messages
* Messages will be delivered at least once

## Examples

Using the provided `js-await` macro:

```clj
(def settings
  (dq/compile-settings
    {::dq/read edn/read-string
     ::dq/write pr-str
     ::dq/db-name "testdb"
     ::dq/queues [{::dq/queue-name :qname/local-sync}]}))

(dq/js-await [_ (dq/push! settings
                          :qname/local-sync
                          {:foo :bar})
              v (dq/receive! settings
                             :qname/local-sync)]
  (dq/js-await [_ (dq/ack! settings
                           :qname/local-sync
                           v)]
    (js/console.log "All done!")))
```


Using core.async (and the [`core.async.interop/<p!` macro](https://clojurescript.org/guides/promise-interop#using-promises-with-core-async)):

```clj
(def settings
  (dq/compile-settings
    {::dq/read edn/read-string
     ::dq/write pr-str
     ::dq/db-name "testdb"
     ::dq/queues [{::dq/queue-name :qname/local-sync}]}))

(go
  (<p! (dq/push! settings
                 :qname/local-sync
                 {:foo :bar}))
  (let [v (<p! (dq/receive! settings
                            :qname/local-sync))]
    (dq/ack! settings
             :qname/local-sync
             v)))
```

## Settings
* `::dq/read` -  A function that takes a serialized string and returns a Clojurescript data structure. The return value must implement `IMeta`.
* `::dq/write` - A function that takes a Clojurescript data structure and returns a serialized string.
* `::dq/db-name` - A string that will be the name of the IndexedDB Database.
* `::dq/queues` - A sequence of queue settings
* `::dq/queue-name` - A keyword name for your queue
* `::dq/tx-opts` - A Clojurescript hashmap of [Transaction Options](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction#options) (e.g. `{"durability" "strict"}` or `{"durability" "default"}`

<sup>†</sup> The default behavior leaves a small, but non-zero, chance of data
loss. You probably don't need to worry about this, but if it's critical that
_nothing_ is lost, I have good news! If you use `::dq/tx-opts {"durability" "strict"}`. See the [MDN
site](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction#options),
and the related [Chrome Status
Feature](https://chromestatus.com/feature/5730701489995776) for details.
