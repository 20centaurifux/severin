(ns severin.core
  "A library for implementing resource pools.")

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

(defn- uri->key
  "Converts a URI to a keyword. If factory f implements the URI->KeyProtocol
  protocol, the result is (-uri->key f uri). Otherwise (keyword uri) is
  returned."
  [f uri]
  (if (satisfies? URI->KeyProtocol f)
    (-uri->key f uri)
    (keyword uri)))

(defmulti factory
  "Creates a factory from a URI by dispatching on the scheme."
  #(-> %
       java.net.URI.
       .getScheme))

(defn- pool-pop!
  "Pops the first available resource from a pool which is associated
  to the key k."
  [pool k]
  (dosync
    (when-let [v (peek (k @pool))]
      (alter pool update-in [k] pop)
      v)))

(defn- pool-push!
  "Places a resource back in the pool and associates it to the key k, if the
  number of already stored resources doesn't exceed max-size."
  [pool k resource max-size]
  (dosync
    (if (> max-size (count (k @pool)))
      (some? (alter pool update-in [k] conj resource))
     false)))

(defn make-pool
  "Returns a new Ref with an empty hash map as initial value."
  [&{:keys [max-size] :or {max-size 10}}]
  {:pre [(pos? max-size)]}
  (ref {} :meta {:max-size max-size}))

(defn create!
  "Tries to retrieve and recycle an already created resource from the pool. If
  no resource can be found a new one is created."
  [pool uri]
  (let [f (factory uri)]
    (if-let [r (pool-pop! pool (uri->key f uri))]
      (if-not (-valid? f r)
        (do
          (-dispose! f r)
          (create! pool uri))
        (-recycle! f r uri))
      (-create! f uri))))

(defn dispose!
  "Places a resource back in the pool or disposes it when maximum size is reached."
  ([pool uri resource]
   (let [f (factory uri)]
     (if-not (and (-valid? f resource)
                  (pool-push! pool (uri->key f uri) resource (:max-size (meta pool))))
       (-dispose! f resource))))
  ([pool resource]
    (if-let [uri (:uri resource)]
      (dispose! pool uri resource)
      (throw (IllegalArgumentException. "Couldn't get URI from resource.")))))
