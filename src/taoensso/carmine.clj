(ns taoensso.carmine
  "Simple, high-performance Redis (2.0+) client for Clojure."
  {:author "Peter Taoussanis"}
  (:refer-clojure :exclude [time get set keys type sync sort eval])
  (:require [clojure.string :as str]
            [taoensso.carmine
             (protocol    :as protocol)
             (connections :as conns)
             (commands    :as commands)])
  (:import [org.apache.commons.pool.impl GenericKeyedObjectPool]
           [taoensso.carmine.connections ConnectionPool]
           [taoensso.carmine.protocol    Preserved]))

;;;; Connections

(defn make-conn-pool
  "For option documentation see http://goo.gl/EiTbn"
  [& options]
  (let [;; Defaults adapted from Jedis
        defaults {:test-while-idle?              true
                  :num-tests-per-eviction-run    -1
                  :min-evictable-idle-time-ms    60000
                  :time-between-eviction-runs-ms 30000}]
    (ConnectionPool.
     (reduce conns/set-pool-option
             (GenericKeyedObjectPool. (conns/make-connection-factory))
             (merge defaults (apply hash-map options))))))

(defn make-conn-spec
  [& {:keys [host port password timeout db]
      :or   {host "127.0.0.1" port 6379 password nil timeout 0 db 0}}]
  {:host host :port port :password password :timeout timeout :db db})

(defmacro with-conn
  "Evaluates body in the context of a thread-bound pooled connection to a Redis
  server. Sends Redis commands to server as pipeline and returns the server's
  response. Releases connection back to pool when done.

  Use `make-conn-pool` and `make-conn-spec` to generate the required arguments."
  [connection-pool connection-spec & body]
  `(try
     (let [pool# (or ~connection-pool conns/non-pooled-connection-pool)
           spec# (or ~connection-spec (make-conn-spec))
           conn# (conns/get-conn pool# spec#)]
       (try
         (let [response# (protocol/with-context conn# ~@body)]
           (conns/release-conn pool# conn#) response#)
         (catch Exception e# (conns/release-conn pool# conn# e#) (throw e#))))
     (catch Exception e# (throw e#))))

;;;; Misc

(defmacro with-parser
  "Wraps body so that replies to any wrapped Redis commands will be parsed with
  (parser-fn reply)."
  [parser-fn & body]
  `(binding [protocol/*parser* ~parser-fn] ~@body))

(defmacro with-mparser
  "Wraps body so that multi-bulk (vector) replies to any wrapped Redis commands
  will be parsed with (parser-fn reply)."
  [parser-fn & body]
  `(with-parser
     (fn [multi-bulk-reply#] (vec (map ~parser-fn multi-bulk-reply#)))
     ~@body))

(defmacro skip-replies
  [& body] `(with-parser (constantly :taoensso.carmine.protocol/skip-reply)
              ~@body))

;;; Note (number? x) checks for backwards compatibility with pre-v0.11 Carmine
;;; versions that auto-serialized simple number types
(defn as-long   [x] (when x (if (number? x) (long   x) (Long/parseLong     x))))
(defn as-double [x] (when x (if (number? x) (double x) (Double/parseDouble x))))
(defn as-bool   [x] (cond (or (true? x) (false? x))            x
                          (or (= x "false") (= x "0") (= x 0)) false
                          (or (= x "true")  (= x "1") (= x 1)) true
                          :else nil))

(defmacro parse-long     [& body] `(with-parser as-long    ~@body))
(defmacro parse-double   [& body] `(with-parser as-double  ~@body))
(defmacro parse-bool     [& body] `(with-parser as-bool    ~@body))
(defmacro parse-keyword  [& body] `(with-parser keyword    ~@body))

(defmacro parse-longs    [& body] `(with-mparser as-long   ~@body))
(defmacro parse-doubles  [& body] `(with-mparser as-double ~@body))
(defmacro parse-bools    [& body] `(with-mparser as-bool   ~@body))
(defmacro parse-keywords [& body] `(with-mparser keyword   ~@body))

(defn make-keyfn
  "Returns a function that joins keywords and strings to form an idiomatic
  compound Redis key name.

  (let [k (make-keyfn :account)]
    (k \"1j4nhg7\" :email-address)) => \"account:1j4nhg7:email-address\"

  Recommended key naming style:
    * \"category:subcategory:id:field\" basic form.
    * Singular category names (\"account\" rather than \"accounts\").
    * Dashes for long names (\"email-address\" rather than \"emailAddress\", etc.)."
  [& prefix-parts]
  (let [join-parts (fn [parts] (str/join ":" (map name (filter identity parts))))
        prefix     (when (seq prefix-parts) (str (join-parts prefix-parts) ":"))]
    (fn [& parts] (str prefix (join-parts parts)))))

(comment ((make-keyfn :foo :bar) :baz "qux")
         ((make-keyfn) :foo :bar)
         ((make-keyfn) nil "foo"))

(defn preserve
  "Forces argument of any type (including simple number and binary types) to be
  subject to automatic de/serialization."
  [x]
  (protocol/Preserved. x))

;;;; Standard commands

(commands/defcommands) ; This kicks ass - big thanks to Andreas Bielk!

;;;; Helper commands

(defn remember
  "Special command that takes any value and returns it unchanged as part of
  an enclosing `with-conn` pipeline response."
  [value]
  (let [temp-key (str "carmine:temp:remember")]
    (skip-replies (setex temp-key "60" value))
    (get temp-key)))

(def hash-script
  (memoize
   (fn [script]
     (org.apache.commons.codec.digest.DigestUtils/shaHex (str script)))))

(defn evalsha*
  "Like `evalsha` but automatically computes SHA1 hash for script."
  [script numkeys key & args]
  (apply evalsha (hash-script script) numkeys key args))

(defn eval*
  "Optimistically tries to send `evalsha` command for given script. In the event
  of a \"NOSCRIPT\" reply, reattempts with `eval`. Returns the final command's
  reply."
  [script numkeys key & args]
  (remember
   (try (apply evalsha* script numkeys key args)
        (protocol/get-one-reply!)
        (catch Exception e
          (if (= (.substring (.getMessage e) 0 8) "NOSCRIPT")
            (do (apply eval script numkeys key args)
                (protocol/get-one-reply!))
            (throw e))))))

(def ^:private interpolate-script
  "Substitutes indexed KEYS[]s and ARGV[]s for named variables in Lua script.

  (interpolate-script \"return redis.call('set', _:my-key, _:my-val)\"
                      {:my-key \"foo\"} {:my-val \"bar\"})
  => {:script \"return redis.call('set', KEYS[1], ARGV[1])\"
      :eval-args [\"1\" \"foo\" \"bar\"]}"
  (memoize
   (fn [script key-vars-map arg-vars-map]
     (let [key-vars-map (into (sorted-map) key-vars-map)
           arg-vars-map (into (sorted-map) arg-vars-map)

           ;; {match replacement} e.g. {"_:my-var" "ARRAY-NAME[1]"}
           subst-map (fn [vars array-name]
                       (zipmap (map #(str "_" %) vars)
                               (map #(str array-name "[" % "]")
                                    (map inc (range)))))]

       {:script
        (reduce (fn [s [match replacement]] (str/replace s match replacement))
                (str script)
                (merge (subst-map (clojure.core/keys key-vars-map) "KEYS")
                       (subst-map (clojure.core/keys arg-vars-map) "ARGV")))

        :eval-args (-> [(str (count key-vars-map))]
                       (into (vals key-vars-map))
                       (into (vals arg-vars-map)))}))))

(comment
  (interpolate-script "return redis.call('set', _:my-key, _:my-val)"
                      {:my-key "foo"} {:my-val "bar"})

  (interpolate-script "Hello _:k1 _:k1 _:k2 _:k3 _:a1 _:a2 _:a3"
                      {:k3 "k3" :k1 "k1" :k2 "k2"}
                      {:a3 "a3" :a1 "a1" :a2 "a2"}))

(defn lua-script
  "All singing, all dancing Lua script helper. Like `eval*` but allows script
  to use \"_:my-var\"-style named keys and args."
  [script key-vars-map arg-vars-map]
  (let [{:keys [script eval-args]}
        (interpolate-script script key-vars-map arg-vars-map)]
    (apply eval* script eval-args)))

(comment
  (wc (lua-script "redis.call('set', _:my-key, _:my-val)
                   return redis.call('get', 'foo')"
                  {:my-key "foo"}
                  {:my-val "bar"})))

(defn hgetall*
  "Like `hgetall` but automatically coerces reply into a hash-map."
  [key] (with-parser #(apply hash-map %) (hgetall key)))

(defn info*
  "Like `info` but automatically coerces reply into a hash-map."
  []
  (with-parser (fn [reply] (->> reply str/split-lines
                               (map #(str/split % #":"))
                               (filter #(= (count %) 2))
                               (into {})))
    (info)))

(defn zinterstore*
  "Like `zinterstore` but automatically counts keys."
  [dest-key source-keys & options]
  (apply zinterstore dest-key
         (count source-keys) (concat source-keys options)))

(defn zunionstore*
  "Like `zunionstore` but automatically counts keys."
  [dest-key source-keys & options]
  (apply zunionstore dest-key
         (count source-keys) (concat source-keys options)))

;; Adapted from redis-clojure
(defn- parse-sort-args [args]
  (loop [out [] remaining-args (seq args)]
    (if-not remaining-args
      out
      (let [[type & args] remaining-args]
        (case type
          :by (let [[pattern & rest] args]
                (recur (conj out "BY" pattern) rest))
          :limit (let [[offset count & rest] args]
                   (recur (conj out "LIMIT" offset count) rest))
          :get (let [[pattern & rest] args]
                 (recur (conj out "GET" pattern) rest))
          :mget (let [[patterns & rest] args]
                  (recur (into out (interleave (repeat "GET")
                                               patterns)) rest))
          :store (let [[dest & rest] args]
                   (recur (conj out "STORE" dest) rest))
          :alpha (recur (conj out "ALPHA") args)
          :asc   (recur (conj out "ASC")   args)
          :desc  (recur (conj out "DESC")  args)
          (throw (Exception. (str "Unknown sort argument: " type))))))))

(defn sort*
  "Like `sort` but supports idiomatic Clojure arguments: :by pattern,
  :limit offset count, :get pattern, :mget patterns, :store destination,
  :alpha, :asc, :desc."
  [key & sort-args] (apply sort key (parse-sort-args sort-args)))

(defmacro atomically
  "Executes all Redis commands in body as a single transaction and returns
  server response vector or the empty vector if transaction failed.

  Body may contain a (discard) call to abort transaction."
  [watch-keys & body]
  `(try
     (skip-replies ; skip "OK" and "QUEUED" replies
      (when (seq ~watch-keys) (apply watch ~watch-keys))
      (multi)
      ~@body)
     (with-parser #(if (instance? Exception %) [] %) (exec))))

;;;; Persistent stuff (monitoring, pub/sub, etc.)

;; Once a connection to Redis issues a command like `p/subscribe` or `monitor`
;; it enters an idiosyncratic state:
;;
;;     * It blocks while waiting to receive a stream of special messages issued
;;       to it (connection-local!) by the server.
;;     * It can now only issue a subset of the normal commands like
;;       `p/un/subscribe`, `quit`, etc. These do NOT issue a normal server reply.
;;
;; To facilitate the unusual requirements we define a Listener to be a
;; combination of persistent, NON-pooled connection and threaded message
;; handler:

(defrecord Listener [connection handler state])

(defmacro with-new-listener
  "Creates a persistent connection to Redis server and a thread to listen for
  server messages on that connection.

  Incoming messages will be dispatched (along with current listener state) to
  binary handler function.

  Evaluates body within the context of the connection and returns a
  general-purpose Listener containing:

      1. The underlying persistent connection to facilitate `close-listener` and
         `with-open-listener`.
      2. An atom containing the function given to handle incoming server
         messages.
      3. An atom containing any other optional listener state.

  Useful for pub/sub, monitoring, etc."
  [connection-spec handler initial-state & body]
  `(let [handler-atom# (atom ~handler)
         state-atom#   (atom ~initial-state)
         conn# (conns/make-new-connection (assoc ~connection-spec
                                            :listener? true))
         in#   (conns/in-stream conn#)]

     ;; Create a thread to actually listen for and process messages from
     ;; server. Thread will close when connection closes.
     (-> (bound-fn [] (@handler-atom# (protocol/get-basic-reply! in#) @state-atom#))
         repeatedly doall future)

     (protocol/with-context conn# ~@body)
     (Listener. conn# handler-atom# state-atom#)))

(defmacro with-open-listener
  "Evaluates body within the context of given listener's preexisting persistent
  connection."
  [listener & body]
  `(protocol/with-context (:connection ~listener) ~@body))

(defn close-listener [listener] (conns/close-conn (:connection listener)))

(defmacro with-new-pubsub-listener
  "A wrapper for `with-new-listener`. Creates a persistent connection to Redis
  server and a thread to listen for published messages from channels that we'll
  subscribe to using `p/subscribe` commands in body.

  While listening, incoming messages will be dispatched by source (channel or
  pattern) to the given message-handler functions:

       {\"channel1\" (fn [message] (prn \"Channel match: \" message))
        \"user*\"    (fn [message] (prn \"Pattern match: \" message))}

  SUBSCRIBE message:  `[\"message\" channel payload]`
  PSUBSCRIBE message: `[\"pmessage\" pattern matching-channel payload]`

  Returns the Listener to allow manual closing and adjustments to
  message-handlers."
  [connection-spec message-handlers & subscription-commands]
  `(with-new-listener (assoc ~connection-spec :pubsub-listener? true)

     ;; Message handler (fn [message state])
     (fn [[_# source-channel# :as incoming-message#] msg-handlers#]
       (when-let [f# (clojure.core/get msg-handlers# source-channel#)]
         (f# incoming-message#)))

     ~message-handlers ; Initial state
     ~@subscription-commands))

;;;; Dev/tests

(comment
  (do (def pool (make-conn-pool))
      (def spec (make-conn-spec))
      (def conn (conns/get-conn pool spec))
      (defmacro wc [& body] `(with-conn pool spec ~@body)))

  ;;; Basic connections
  (conns/conn-alive? conn)
  (conns/release-conn pool conn) ; Return connection to pool (don't close)
  (conns/close-conn conn)        ; Actually close connection

  ;;; Basic requests
  (with-conn nil nil (ping)) ; Degenerate pool, default spec
  (with-conn (make-conn-pool) (make-conn-spec) (ping))
  (with-conn pool spec (ping))
  (with-conn pool spec "invalid" (ping) (ping)))