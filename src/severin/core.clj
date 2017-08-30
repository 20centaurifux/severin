(ns severin.core)

(defprotocol FactoryProtocol
  (-create!
    [this uri])

  (-dispose!
    [this resource])

  (-valid?
    [this resource]))

(defprotocol URI->KeyProtocol
  (-uri->key
    [this uri]))

(defn- uri->key
  [f uri]
  (if (satisfies? URI->KeyProtocol f)
    (-uri->key f uri)
    (keyword uri)))

(defmulti factory
  #(-> %
       java.net.URI.
       .getScheme))

(defn- pool-pop!
  [pool-ref k]
  (dosync
    (when-let [v (peek (k @pool-ref))]
      (alter pool-ref update-in [k] pop)
      v)))

(defn- pool-push!
  [pool-ref k resource max-size]
  (dosync
    (if (> max-size (count (k @pool-ref)))
      (some? (alter pool-ref update-in [k] conj resource))
     false)))

(defn make-pool
  [&{:keys [max-size] :or {max-size 10}}]
  {:pre [(pos? max-size)]}
  (ref {} :meta {:max-size max-size}))

(defn create!
  [pool-ref uri]
  (let [f (factory uri)]
    (if-let [r (pool-pop! pool-ref (uri->key f uri))]
      (if-not (-valid? f r)
        (do
          (-dispose! f r)
          (create! pool-ref uri))
        r)
      (-create! f uri))))

(defn dispose!
  ([pool-ref uri resource]
   (let [f (factory uri)]
     (if-not (and (-valid? f resource)
                  (pool-push! pool-ref (uri->key f uri) resource (:max-size (meta pool-ref))))
       (-dispose! f resource))))
  ([pool-ref resource]
    (if-let [uri (:uri resource)]
      (dispose! pool-ref uri resource)
      (throw (IllegalArgumentException. "Couldn't get:tab URI from resource.")))))
