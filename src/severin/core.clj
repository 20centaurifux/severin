(ns severin.core
  "A library for implementing resource pools."
  (:require [clojure.spec.alpha :as s]))

(defprotocol FactoryProtocol
  (-create!
    [this uri]
    "Creates a new resource from a URI.")

  (-dispose!
    [this resource]
    "Disposes a resource.")

  (-recycle!
    [this resource uri]
    "Recycles an existing resource and assigns a URI.")

  (-valid?
    [this resource]
    "Tests if a resource is still valid."))

(defprotocol URI->KeyProtocol
  (-uri->key
    [this uri]
    "Converts a URI to a keyword."))

(defprotocol URIProtocol
  (-uri
    [this]
    "Returns the URI of a resource."))

(s/def ::pool (s/and map? (s/every-kv keyword?
                                      (s/coll-of any? :kind list?))))

(s/def ::pool-ref (s/and #(instance? clojure.lang.Ref %)
                         #(s/valid? ::pool @%)))

(s/def ::max-size pos?)

(s/fdef make-pool
  :args (s/cat :kwargs (s/keys* :opt-un [::max-size]))
  :ret ::pool-ref)

(defn make-pool
  "Returns a new Ref with an empty hash map as initial value."
  [& {:keys [max-size] :or {max-size 10}}]
  (ref {} :meta {:max-size max-size}))

(defmulti make-factory
  "Creates a factory from a URI by dispatching on the scheme."
  #(-> %
       java.net.URI.
       .getScheme))

(defn- uri->key
  [f uri]
  (if (satisfies? URI->KeyProtocol f)
    (-uri->key f uri)
    (keyword uri)))

; Pops the first available resource from `pool` associated to `k`.
(defn- pool-pop!
  [pool k]
  (dosync
   (when-let [v (peek (k @pool))]
     (alter pool update-in [k] pop)
     v)))

(s/def ::uri (s/and string?
                    #(try
                       (.getScheme (java.net.URI. %))
                       (catch Exception _))))

(s/fdef create!
  :args (s/cat :pool ::pool-ref :uri ::uri)
  :ret any?)

(defn create!
  "Tries to retrieve and recycle an already created resource from `pool`. If
  no resource can be found a new one is created."
  [pool uri]
  (let [f (make-factory uri)]
    (if-let [r (pool-pop! pool (uri->key f uri))]
      (if (-valid? f r)
        (-recycle! f r uri)
        (do
          (-dispose! f r)
          (create! pool uri)))
      (-create! f uri))))

(defn- uri
  [resource]
  (cond
    (satisfies? URIProtocol resource) (-uri resource)
    (instance? clojure.lang.ILookup resource) (:uri resource)))

; Places a resource back in `pool` and associates it to `k` if number of
; already stored resources doesn't exceed `max-size`.
(defn- pool-push!
  [pool k resource max-size]
  (dosync
   (if (> max-size (count (k @pool)))
     (some? (alter pool update-in [k] conj resource))
     false)))

(s/fdef dispose!
  :args (s/alt :with-uri (s/cat :pool ::pool-ref :uri ::uri :resource any?)
               :without-uri (s/cat :pool ::pool-ref :resource any?))
  :ret any?)

(defn dispose!
  "Places a resource back in `pool` or disposes it when maximum size is
  reached."
  ([pool uri resource]
   (let [f (make-factory uri)]
     (if-not (and (-valid? f resource)
                  (pool-push! pool
                              (uri->key f uri)
                              resource
                              (:max-size (meta pool))))
       (-dispose! f resource))))
  ([pool resource]
   (if-let [uri (uri resource)]
     (dispose! pool uri resource)
     (throw (IllegalArgumentException. "Couldn't get URI from resource.")))))

(s/fdef with-pool
  :args (s/cat :pool any?
               :bindings (s/and vector?
                                (s/* (s/cat :symbols simple-symbol?
                                            :uris ::uri)))
               :body (s/* any?)))

(defmacro with-pool
  "binding => [name uri ...]
  Evaluates `body` in a try expression with names bound to the values of the
  created resources. The finally clause calls `dispose!` on each name."
  [pool bindings & body]
  (let [poolsym (gensym)]
    `(let ~(into [poolsym pool]
                 (mapcat (fn [[name uri]] [name `(create! ~poolsym ~uri)])
                         (partition 2 bindings)))
       (try
         (do ~@body)
         (finally ~(cons 'do (map (fn [name] `(dispose! ~poolsym ~name))
                                  (take-nth 2 bindings))))))))
