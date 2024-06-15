# DQ: A Durable Queue for the Browser

A fast, durable, async queue for the browser backed by
[IndexedDB](https://developer.mozilla.org/en-US/docs/Web/API/IndexedDB_API).
Features:

* Ordered delivery
    * Retries will be delivered prior to new messages
* At least once or exactly once delivery
* "relaxed" durability by default
    * Optional ["strict"](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction#options) (i.e. guaranteed) durability
* Zero dependencies
* Promise-based API
    * No core.async, but integrates with core.async easily (see below)

## Use Cases

* Interacting with a Web Worker
* Immediate, durable writes that you later sync to the server in the background
* Any event-based activity where you don't want to lose data due to a browser refresh

## Latest Version
Deps
```clj
com.potetm/dq {:mvn/version "1.0.1"}
```

Lein
```clj
[com.potetm/dq "1.0.1"]
```

## Examples

Using the provided `js-await` macro:

```clj
(ns my.ns
  (:require
    [clojure.edn :as edn]
    [com.potetm.dq :as dq]))


(def settings
  {::dq/read edn/read-string
   ::dq/write pr-str
   ::dq/db-name "testdb"
   ::dq/queues {:qname/local-sync {}}})


(dq/js-await [_ (dq/push! settings
                          :qname/local-sync
                          {:foo :bar})
              msg (dq/receive! settings
                               :qname/local-sync)]
  (try
    (println msg)
    (catch js/Error e
      (dq/fail! settings
                :qname/local-sync
                msg)
      (throw e)))
  (dq/js-await [_ (dq/ack! settings
                           :qname/local-sync
                           msg)]
    (println "All done!")))
```


Using core.async (and the [`core.async.interop/<p!` macro](https://clojurescript.org/guides/promise-interop#using-promises-with-core-async)):

```clj
(ns my.ns
  (:require
    [cljs.core.async :as a]
    [cljs.core.async.interop :as ai]
    [clojure.edn :as edn]
    [com.potetm.dq :as dq]))


(def settings
  {::dq/read edn/read-string
   ::dq/write pr-str
   ::dq/db-name "testdb"
   ::dq/queues {:qname/local-sync {}}})


(a/go
  (ai/<p! (dq/push! settings
                    :qname/local-sync
                    {:foo :bar}))
  (let [msg (ai/<p! (dq/receive! settings
                                 :qname/local-sync))]
    (try
      (println msg)
      (ai/<p! (dq/ack! settings
                       :qname/local-sync
                       msg))
      (println "All done!")
      (catch js/Error e
        (ai/<p! (dq/fail! settings
                          :qname/local-sync
                          msg))))))
```

## Settings
* `::dq/read` -  A function that takes a serialized string and returns a Clojurescript data structure. The return value must implement `IMeta`.
* `::dq/write` - A function that takes a Clojurescript data structure and returns a serialized string.
* `::dq/db-name` - A string that will be the name of the IndexedDB Database.
* `::dq/queues` - A hashmap of queue-name -> settings
* Queue Settings
    * `::dq/tx-opts` - A Clojurescript hashmap of [Transaction Options](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction#options) (e.g. `{"durability" "strict"}` or `{"durability" "default"}`)

## Usage Notes
### Durability
The default behavior leaves a small-but-non-zero chance of data loss. You
probably don't need to worry about this, but if it's critical that _nothing_ is
lost, I have good news! You can use `::dq/tx-opts {"durability" "strict"}`. See
the [MDN site](https://developer.mozilla.org/en-US/docs/Web/API/IDBDatabase/transaction#options),
and the related [Chrome Status Feature](https://chromestatus.com/feature/5730701489995776) for details.

My informal testing shows that there's a ~20x performance improvement when using
relaxed durability vs strict durability. (About 1ms vs 20ms for a full push, receive,
ack cycle.) However, this will primarily affect _throughput_ rather than UI
latency, because the fsync to disk happens in the OS layer, not the browser. If
durability matters at all, you might as well try it with strict durability and
only relax it after the need becomes clear.

### Metadata
DQ uses metadata on messages returned from `receive!` to track an internal
identifier and retry counts. This means that you _must_ use the _original_
message for calls to `ack!` or `fail!`. While this is a bit of a "gotcha," in
practice, it's not at all onerous given the fact that Clojurescript data
structures are immutable and queue consumer code always follows a pattern of:

```clj
(while true
  (let [msg (receive!)]
    (do-stuff msg)
    (ack! msg)))
```

If you want to know how many times a message has been retried, you can do:

```clj
(::dq/try-num (meta msg))
```

### Consumer startup
When starting a consumer, it's recommended that you call `dq/fail-all!`
before dropping into your consumer loop. This will clear out any un-acked
messages from your previous window.
