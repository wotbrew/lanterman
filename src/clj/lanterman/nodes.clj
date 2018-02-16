(ns lanterman.nodes
  "Defines the node datastructures that make up the log."
  (:require [clojure.edn :as edn]
            [clojure.data.fressian :as fress]
            [riverford.durable-ref.core :as dref]
            [riverford.durable-ref.format.fressian]
            [lanterman.nodes.persist :as persist]
            [clojure.java.io :as io])
  (:import (java.nio ByteBuffer)
           (java.util LinkedHashMap)
           (java.io File)))

(def ^:private slab-cache
  (proxy [LinkedHashMap] []
    (removeEldestEntry [eldest]
      (> (.size this) 64))))

(def ^:private tree-cache
  (proxy [LinkedHashMap] []
    (removeEldestEntry [eldest]
      (> (.size this) 128))))

(def ^:private tail-cache
  (proxy [LinkedHashMap] []
    (removeEldestEntry [eldest]
      (> (.size this) 64))))

(defn- intern-tree-ref
  [dref]
  (locking tree-cache
    (.put tree-cache (str (dref/uri dref)) dref))
  dref)

(defn- intern-tail-ref
  [dref]
  (locking tail-cache
    (.put tail-cache (str (dref/uri dref)) dref))
  dref)

(defn- intern-slab-ref
  [dref]
  (locking slab-cache
    (.put slab-cache (str (dref/uri dref)) dref))
  dref)

(defn- sum-bytes
  [& nodes]
  (transduce (keep :node/byte-count) + 0 nodes))

(defn sum-length
  [& nodes]
  (transduce (keep :node/length) + 0 nodes))

(defn- empty-node?
  [node]
  (zero? (sum-length node)))

(defn- buffer?
  [n]
  (= :node.type/buffer (:node/type n)))

(defn- node?
  [x]
  (some? (:node/type x)))

(defn- fressian-bytes
  [x]
  (.array ^ByteBuffer (fress/write x)))

(defn buffer
  "Returns an buffer node for 'x', the type of node will depend on 'x'. A buffer node always has a byte payload
  and a len in bytes.

  The buffer indirection allows you to store other node pointers in slabs."
  [x]
  (cond
    (buffer? x) x

    (bytes? x) {:node/type :node.type/buffer
                :node/byte-count (+ persist/buffer-overhead (alength ^bytes x))
                :node/length 1

                :buffer/type :bytes
                :buffer/payload x}

    (node? x) (let [bytes (persist/node-array x)]
                {:node/type :node.type/buffer
                 :node/byte-count (+ persist/buffer-overhead (alength bytes))
                 :node/length (sum-length x)

                 :buffer/type :node
                 :buffer/payload bytes})

    (string? x) (let [bytes (.getBytes ^String x "UTF-8")]
                  {:node/type :node.type/buffer
                   :node/byte-count (+ persist/buffer-overhead (alength bytes))
                   :node/length 1
                   :buffer/type :string
                   :buffer/payload bytes})

    :else (let [bytes (fressian-bytes x)]
            {:node/type :node.type/buffer
             :node/byte-count (+ persist/buffer-overhead (alength bytes))
             :node/length 1
             :buffer/type :fressian
             :buffer/payload bytes})))

(defmulti ^{:arglists '([node])} buffer-iterable
  "Returns an iterable of the buffers in the node"
  :node/type)

(defmethod buffer-iterable :node.type/buffer
  [msg]
  (let [{:keys [:buffer/type
                :buffer/payload]} msg]
    [msg]))

(let [xf (fn [rf]
           (fn
             ([] (rf))
             ([ret] (rf ret))
             ([ret x] (case (:buffer/type x)
                        :node (reduce rf ret (buffer-iterable (fress/read (:buffer/payload x))))
                        (rf ret x)))))]
  (defmethod buffer-iterable :node.type/tail
    [{:keys [:tail/nodes
             :tail/buffers]}]
    (eduction cat [(eduction (mapcat buffer-iterable) nodes)
                   (eduction xf buffers)])))

(defmethod buffer-iterable :node.type/pointer
  [{:keys [:pointer/node]}]
  (buffer-iterable node))

(defmulti ^{:arglists '([node])} message-iterable
  "Returns an iterable of messages in the node."
  :node/type)

(defmethod message-iterable :default
  [node]
  (eduction (mapcat message-iterable) (buffer-iterable node)))

(defmethod message-iterable :node.type/buffer
  [msg]
  (let [{:keys [:buffer/type
                :buffer/payload]} msg]
    (case type
      :fressian [(fress/read payload)]
      :bytes [payload]
      :utf8-string (String. ^bytes payload "UTF-8")
      :node (message-iterable (persist/read-node (ByteBuffer/wrap payload) {})))))

(declare add-node-to-tail)
(defn- add-entry-to-tail
  [tail buffer]
  (let [{:keys [:tail/nodes
                :tail/buffers
                :tail/inline-bytes
                :tail/max-inline-bytes]} tail]
    (cond
      ;; entry too big, introduce indirection
      (< max-inline-bytes (alength ^bytes (:buffer/payload buffer)))
      (add-node-to-tail tail
                        {:node/type :node.type/slab
                         :node/length (sum-length buffer)
                         :node/byte-count (+ persist/slab-overhead (sum-bytes buffer))
                         :slab/buffers [buffer]})

      ;; buffer will not fit in remaining inline bytes, shift tail
      (< max-inline-bytes (+ inline-bytes (alength ^bytes (:buffer/payload buffer))))
      {:node/type :node.type/tail
       :node/byte-count (+ persist/tail-overhead (sum-bytes tail buffer))
       :node/length (sum-length tail buffer)

       :tail/inline-bytes (+ persist/tail-overhead (sum-bytes buffer))
       :tail/max-inline-bytes max-inline-bytes
       :tail/nodes [tail]
       :tail/buffers [buffer]}

      ;; we have room, can just append buffer
      :else
      {:node/type :node.type/tail
       :node/byte-count (sum-bytes tail buffer)
       :node/length (sum-length tail buffer)

       :tail/inline-bytes (+ inline-bytes (sum-bytes buffer))
       :tail/max-inline-bytes max-inline-bytes
       :tail/nodes nodes
       :tail/buffers (conj (vec buffers) buffer)})))

(defn- add-node-to-tail
  [tail node]
  (cond
    (empty-node? node) tail
    (buffer? node) (add-entry-to-tail tail node)
    ;; if we can fit all messages into inline-bytes, do so.
    (< (+ (sum-bytes node) (:tail/inline-bytes tail)) (:tail/max-inline-bytes tail))
    (reduce add-entry-to-tail tail (buffer-iterable node))
    :else
    (let [{:keys [:tail/nodes
                  :tail/buffers
                  :tail/inline-bytes
                  :tail/max-inline-bytes]} tail
          empty? (empty-node? tail)]
      {:node/type :node.type/tail
       :node/byte-count (+ (if empty? 0 persist/tail-overhead) (sum-bytes tail node))
       :node/length (sum-length tail node)

       :tail/inline-bytes persist/tail-overhead
       :tail/max-inline-bytes max-inline-bytes
       :tail/nodes (if empty?
                     [node]
                     [tail node])
       :tail/buffers []})))

(defn- empty-tail
  [max-inline-bytes]
  {:node/type :node.type/tail
   :node/byte-count persist/tail-overhead
   :node/length 0

   :tail/inline-bytes persist/tail-overhead
   :tail/max-inline-bytes max-inline-bytes
   :tail/nodes []
   :tail/buffers []})

(defn- add-to-tail
  ([tail x]
   (if (node? x)
     (add-node-to-tail tail x)
     (add-entry-to-tail tail (buffer x))))
  ([tail x & more]
   (reduce add-to-tail (add-to-tail tail x) more)))

(defn empty-tree
  [branching-factor]
  (assert (< 1 branching-factor) "Branching factor must be greater than 1.")
  {:node/type :node.type/tree
   :node/length 0
   :node/byte-count persist/tree-overhead

   :tree/branching-factor branching-factor
   :tree/elements []})

(defn- ensure-vec [x]
  (if (vector? x) x (vec x)))

(defn- push-slab
  [tree slab]
  (let [{:keys [:tree/elements
                :tree/branching-factor]} tree
        nodes (ensure-vec elements)]

    (cond

      (empty? nodes)
      {:node/type :node.type/tree
       :node/length (sum-length slab)
       :node/byte-count (+ persist/tree-overhead persist/tree-el-overhead (sum-bytes slab))


       :tree/branching-factor branching-factor
       :tree/elements [{:offset 0
                        :bytes (+ persist/tree-el-overhead (sum-bytes slab))
                        :length (sum-length slab)
                        :nslabs 1
                        :value slab}]}

      (apply = (map :nslabs nodes))
      (if (< (count nodes) branching-factor)
        {:node/type :node.type/tree
         :node/length (sum-length tree slab)
         :node/byte-count (+ persist/tree-el-overhead (sum-bytes tree slab))

         :tree/branching-factor branching-factor
         :tree/elements (conj nodes
                              {:offset (sum-length tree)
                               :bytes (+ persist/tree-el-overhead (sum-bytes slab))
                               :length (sum-length slab)
                               :nslabs 1
                               :value slab})}
        (push-slab
          {:node/type :node.type/tree
           :node/length (sum-length tree)
           :node/byte-count (+ persist/tree-overhead persist/tree-el-overhead (sum-bytes tree))

           :tree/branching-factor branching-factor
           :tree/elements [{:offset 0
                            :bytes (+ persist/tree-el-overhead (sum-bytes tree))
                            :length (sum-length tree)
                            :nslabs (reduce + (map :nslabs nodes))
                            :value tree}]}
          slab))

      :else

      {:node/type :node.type/tree
       :node/length (sum-length tree slab)
       :node/byte-count (+ persist/tree-el-overhead (sum-bytes tree slab))

       :tree/branching-factor branching-factor
       :tree/elements (conj (pop nodes)
                            (let [n (peek nodes)
                                  child (:value n)
                                  ref? (= (:node/type child) :node.type/ref)
                                  reftype (:ref/node-type child)
                                  child (if (= reftype :node.type/tree)
                                          (dref/value (intern-tree-ref (dref/reference (:ref/uri child))))
                                          child)]
                              (case (:node/type child)
                                :node.type/tree
                                (let [new-tree (push-slab child slab)]
                                  {:offset (:offset n)
                                   :bytes (+ persist/tree-el-overhead (sum-bytes new-tree))
                                   :length (sum-length new-tree)
                                   :nslabs (inc (:nslabs n))
                                   :value new-tree})

                                (:node.type/slab :node.type/ref)
                                (let [new-tree (-> (empty-tree branching-factor)
                                                   (push-slab child)
                                                   (push-slab slab))]
                                  {:offset (:offset n)
                                   :bytes (+ persist/tree-el-overhead (sum-bytes new-tree))
                                   :length (sum-length new-tree)
                                   :nslabs 2
                                   :value new-tree}))))})))


(defn node->slab
  "Takes a node and copies all of its buffers into a slab."
  [node]
  (let [buffers (vec (buffer-iterable node))]
    {:node/type :node.type/slab
     :node/byte-count (+ persist/slab-overhead (sum-bytes node))
     :node/length (sum-length node)
     :slab/buffers buffers}))

(defn- unref
  [node]
  (let [{:keys [:node/type
                :ref/node-type
                :ref/uri]} node]
    (if (= type :node.type/ref)
      (dref/value
        ((case node-type
           :node.type/tree intern-tree-ref
           :node.type/tail intern-tail-ref
           :node.type/slab intern-slab-ref
           identity)
          (dref/reference uri)))
      node)))

(defn append
  "Appends to the log node, returning a new log node.
  Accepted Inputs:
   a message in bytes
   a clojure value (will be encoded as fressian)
   another node (e.g another log)."
  ([log x]
   (if-not (node? x)
     (append log (buffer x))
     (let [{:keys [:log/tail
                   :log/root
                   :log/optimal-slab-bytes]} log]
       (if (<= optimal-slab-bytes (sum-bytes tail))
         (let [newslab (node->slab tail)]
           (append
             (assoc log
               :log/root (push-slab (unref root) newslab)
               :log/tail (empty-tail (:tail/max-inline-bytes tail)))
             x))
         {:node/type :node.type/log
          :node/byte-count (sum-bytes log x)
          :node/length (sum-length log x)

          :log/root root
          :log/tail (add-to-tail tail x)
          :log/optimal-slab-bytes optimal-slab-bytes}))))
  ([log x & more]
   (reduce append (append log x) more)))

(defmethod buffer-iterable :node.type/log
  [{:keys [:log/root
           :log/tail]}]
  (eduction cat [(buffer-iterable root)
                 (buffer-iterable tail)]))

(defmethod buffer-iterable :node.type/tree
  [{:keys [:tree/elements]}]
  (eduction (mapcat (comp buffer-iterable :value)) elements))

(defmethod buffer-iterable :node.type/slab
  [{:keys [:slab/buffers]}]
  buffers)

(defmethod buffer-iterable :node.type/ref
  [{:keys [:ref/uri :ref/node-type]}]
  (buffer-iterable (dref/value
                     ((case node-type
                        :node.type/slab intern-slab-ref
                        :node.type/tail intern-tail-ref
                        :node.type/tree intern-tree-ref
                        identity)

                       (dref/reference uri)))))

(defn summarise
  "Returns a simplified version of the log for inspection and tests at REPL."
  [node]
  (case (:node/type node)

    :node.type/ref {:ref (:ref/uri node)
                    :l (sum-length node)
                    :b (sum-bytes node)}

    :node.type/log {:root (summarise (:log/root node))
                    :tail (summarise (:log/tail node))}

    :node.type/tree (mapv (fn [x]
                            {:nslabs (:nslabs x)
                             :value (summarise (:value x))}) (:tree/elements node))

    {:l (sum-length node)
     :b (sum-bytes node)}))

(def memory-storage
  "Use with `persist` to persist the log to memory."
  {:log-storage/slab-base-uri "mem://lanterman"
   :log-storage/tree-base-uri "mem://lanterman"
   :log-storage/tail-base-uri "mem://lanterman"
   :log-storage/log-base-uri "mem://lanterman"})

(defn file-storage
  [dir]
  (let [buri (str (.toURI ^File (io/file dir)))]
    {:log-storage/slab-base-uri buri
     :log-storage/tree-base-uri buri
     :log-storage/tail-base-uri buri
     :log-storage/log-base-uri buri}))

(defn persist-tree
  "Persists any unpersisted tree/tail nodes to storage. Does not persist the root."
  [node storage-spec]
  (let [{:keys [:log-storage/slab-base-uri
                :log-storage/tree-base-uri
                :log-storage/tail-base-uri
                :log-storage/log-base-uri]} storage-spec
        do-persist (fn [kind obj]
                     (str
                       (dref/uri
                         ((case kind
                            :slab intern-slab-ref
                            :tree intern-tree-ref
                            :tail intern-tail-ref
                            identity)
                           (dref/persist (case kind
                                           :slab slab-base-uri
                                           :tree tree-base-uri
                                           :tail tail-base-uri
                                           :log log-base-uri)
                                         obj
                                         {:as (case kind
                                                :slab "slab"
                                                :tree "tree"
                                                :tail "tail"
                                                :log "log")})))))]
    ((fn persist
       ([node] (persist node false))
       ([node log-root?]
        (case (:node/type node)
          :node.type/log (-> node
                             (update :log/root #(future (persist % log-root?)))
                             (update :log/tail #(future (persist % log-root?)))
                             (update :log/root deref)
                             (update :log/tail deref))
          :node.type/tree
          (if (empty? (:tree/elements node))
            node
            (persist/add-ref-overhead
              {:node/type :node.type/ref
               :node/byte-count (sum-bytes node)
               :node/length (sum-length node)

               :ref/node-type :node.type/tree
               :ref/uri (do-persist
                          :tree
                          (update node :tree/elements
                                  (fn [nodes]
                                    (vec (pmap #(update % :value persist) nodes)))))}))

          :node.type/slab (persist/add-ref-overhead
                            {:node/type :node.type/ref
                             :node/byte-count (sum-bytes node)
                             :node/length (sum-length node)

                             :ref/node-type :node.type/slab
                             :ref/uri (do-persist :slab node)})

          :node.type/tail (if log-root?
            (update node :tail/nodes (partial mapv persist))
            (persist/add-ref-overhead
              {:node/type :node.type/ref
               :node/byte-count (sum-bytes node)
               :node/length (sum-length node)

               :ref/node-type :node.type/tail
               :ref/uri (do-persist :tail node)}))
          node)))
      node
      true)))


(defmulti do-fetch (fn [node offset] (:node/type node)))

(defmethod do-fetch :default
  [node offset]
  (drop offset (message-iterable node)))

(defmethod do-fetch :node.type/tree
  [node offset]
  (let [{:keys [:tree/elements]} node]
    (loop [nodes elements]
      (if (seq nodes)
        (if (<= (:offset (first nodes)) offset)
          (concat (do-fetch (:value (first nodes)) (- offset (:offset (first nodes))))
                  (mapcat message-iterable (rest nodes)))
          (recur (rest nodes)))
        []))))

(defn fetch
  "Fetches messages from the node from the offset. Returns a seq of messages."
  [node offset]
  (do-fetch node offset))

(defn empty-log
  "Returns an empty log node.

  Options:

  :branching-factor (default 2048) (min 2)
  The maximum width of tree nodes before a new parent node needs to be allocated.

  :max-inline-bytes (default 4KB) (min 512B)
  How many bytes of messages are allowed to be left 'inline' in the tail, for example
  if you wanted to use dynamodb to store log roots, you couldn't have more than say 400k bytes inline
  as there is an item size limit.


  :optimal-slab-bytes (default 512KB) (min 1KB)
  How many bytes of messages would you like your slabs to be ideally, this is approximate as messages are never split it could
  be higher or (slightly) lower. A good starting point would be say 8k for filesystems, but maybe 1MB+ for remote storage
  like S3. "
  ([] (empty-log {}))
  ([opts]
   (let [{:keys [branching-factor
                 optimal-slab-bytes
                 max-inline-bytes]} opts
         root (empty-tree (max 2 (or branching-factor 2048)))
         tail (empty-tail (max 512 (or max-inline-bytes 4096)))]
     {:node/type :node.type/log
      :node/byte-count (+ persist/log-overhead (sum-bytes root tail))
      :node/length 0

      ;;todo parse byte counts
      :log/root root
      :log/tail tail
      :log/optimal-slab-bytes (max 1024 (or optimal-slab-bytes (* 512 1024)))})))