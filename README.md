# Severin

## Introduction

Severin provides a Clojure API for implementing pools of resources like network
or database connections.

## Resource lifecycle

For managing the lifecycle of a pooled resource Severin offers a protocol:

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

## Creating and releasing resources

Resources are created and placed back in pool with the create! and dispose!
functions. Internally a factory is created by dispatching the scheme from the
given URI.

A pool is a Ref holding a map. It can be created with the make-pool function.

Disposed resources are pushed to a queue associated by their URI. This
association can be customized by implementing the URI->KeyProtocol:

```
(defprotocol URI->KeyProtocol
  (-uri->key
    [this uri]
    "Converts a URI to a keyword."))
```

Implementing a pool for network connections like HTTP you might want to group
the objects by the remote server's hostname and not by their URI.  Therefore
implement the URI->KeyProtocol protocol in your factory:

```
URI->KeyProtocol

(-uri->key
  [this uri]
  (-> uri
      java.net.URI.
      .getHost
      keyword)))
```

Don't forget to update the URI of recycled connection objects when you've
implemented your own -uri->key function.

## Example

In this example we implement a pool for input streams of local files.

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

(defmethod factory "file"
  [uri]
  (FileReaderFactory.))
```

Creating a BufferedInputStream for the very first time a mark is set.
Recycling a stream the cursor is positioned to the beginning of the file.

Let's create a pool and a BufferedInputStream instance:

```
=> (def p (make-pool))
=> (def uri "file:///tmp/some/file")
=> (def s (create! p uri))
```

Everything looks fine until you place back the resource in pool:

```
=> (dispose! p s)
=> IllegalArgumentException Couldn't get URI from resource.  severin.core/dispose! (core.clj:87)
```

What happened here? As described before the internal used factory is
created by dispatching the scheme from the URI. Therefore you have to specify
the URI if it cannot be looked up from the resource.

```
=> (dispose! p uri s)
```

Alternatively you can extend the BufferedInputStream class and implement the
clojure.lang.ILookup interface to return the URI of a resource:

```
(ns severin.filereader
  (:gen-class
   :extends java.io.BufferedInputStream
   :implements [clojure.lang.ILookup]
   :init init
   :state state
   :constructors {[String][java.io.InputStream]}))

(defn -init
  [uri]
  [[(-> uri
        java.net.URI.
        .getPath
        clojure.java.io/as-file
        java.io.FileInputStream.)]
   uri])

(defn -valAt
 ([this k]
  (-valAt this k nil))
 ([this k nv]
  (if (= k :uri)
    (.state this)
    nv)))
```

Don't forget to update the factory:

```
(-create!
  [this uri]
  (let [resource (severin.filereader. uri)]
    (.mark resource 0)
    resource))
```

Now you can dispose resources without specifying the URI:

```
=> (dispose! p s)
```
