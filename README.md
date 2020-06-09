# severin

## Introduction

severin provides a Clojure API for implementing resource pools, such as
network and database connections.

## Installation

The library can be installed from Clojars using Leiningen:

[![Clojars Project](http://clojars.org/zcfux/severin/latest-version.svg)](https://clojars.org/zcfux/severin)


## Creating and releasing resources

Resources have an associated URI. They are created and placed back in pool
with create! and dispose!.

```
(defn pool (make-pool))

(let [r (create! pool "monger://localhost")]
  ; do something
  (dispose! pool r))
```

with-pool evaluates a body in a try expression. Created resources are bound
to names. The finally clause calls dispose! on each name.

```
(with-pool pool [db "monger://localhost"
                 file "file:///var/log/out"]
 ; do something
)
```

## Resource lifecycle

The lifecycle of every resource type is managed by a factory.

```
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
```

The ->factory multimethod creates a factory from a URI by dispatching on the
scheme.

```
(defmulti ->factory
  "Creates a factory from a URI by dispatching on the scheme."
  #(-> %
       java.net.URI.
       .getScheme))
```

## Pool internals

A pool is a Ref holding a map. It can be created with make-pool.

Disposed resources are pushed onto a queue. Queues are grouped by resource
URIs. This association can be customized by implementing the URI->KeyProtocol.

```
(defprotocol URI->KeyProtocol
  (-uri->key
    [this uri]
    "Converts a URI to a keyword."))
```

When implementing a pool for network connections like HTTP you might want to
group resources by hostname instead of their URI.

```
(defrecord HttpFactory

  [...]

  URI->KeyProtocol

  (-uri->key
    [this uri]
    (-> uri
        java.net.URI.
        .getHost
        keyword)))
```

## Factory example

In this example we implement a pool for file input streams.

```
(ns severin.example
  (:require [severin.core :refer :all]))

(defrecord FileReaderFactory
  []
  FactoryProtocol

  (-create!
    [this uri]
    (let [resource (clojure.java.io/make-input-stream uri {})]
      (.mark resource 0)
      resource))

 (-dispose!
    [this resource]
    (.close resource))

  (-recycle!
    [this resource uri]
    (.reset resource)
    resource)

  (-valid?
    [this resource]
    true))

(defmethod ->factory "file" ; this registers FileReaderFactory
  [uri]
  (FileReaderFactory.))
```

Creating a stream for the very first time a mark is set. The cursor is
positioned to the beginning of the file when a resource is recycled.

Let's create a pool and open a file.

```
=> (def pool (make-pool :max-size 5)) ; queues can grow up to 5 resources
=> (def s (create! pool "file:///tmp/some/file"))
```

Everything looks fine until you place back the resource in pool.

```
=> (dispose! pool s)
=> IllegalArgumentException Couldn't get URI from resource.
```

What happened here? As described before factories are created by dispatching
on the scheme. Therefore you have to specify the URI if it's not provided by
the resource itself.

```
=> (dispose! pool "file:///tmp/some/file" s)
```

You can fix this by adding a custom resource type which implements URIProtocol.

```
(ns severin.filereader
  (:gen-class
   :extends java.io.BufferedInputStream
   :init init
   :state state
   :constructors {[String][java.io.InputStream]})
   (:require [severin.core :refer [URIProtocol -uri]]))

(defn -init
  [uri]
  [[(-> uri
        java.net.URI.
        .getPath
        clojure.java.io/as-file
        java.io.FileInputStream.)]
   uri])

(extend severin.filereader
  URIProtocol
  {:-uri #(.state %)})
```

After updating the factory you can dispose resources without specifying the
URI.

```
=> (dispose! pool s)
```

If a resource doesn't implement URIProtocol severin tries to lookup :uri. This
makes it possible to use maps instead of custom types.

```
(defrecord FileReaderFactory
  []
  FactoryProtocol

  (-create!
    [this uri]
    (let [stream (clojure.java.io/make-input-stream uri {})]
      (.mark stream 0)
      {:stream stream :uri uri}))

  [...])
```
