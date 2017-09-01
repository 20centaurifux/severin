(ns severin.core-test
  (:require [clojure.test :refer :all]
            [clojure.test.check.generators :as gen]
            [severin.core :refer :all]))

(defrecord TestFactory
  []

  FactoryProtocol

  (-create!
    [this uri]
    (let [u (java.net.URI. uri)]
      {:uri uri
       :host (.getHost u)
       :path (.getPath u)}))

 (-dispose!
    [this resource])

  (-recycle!
    [this resource uri]
    (merge (-create! this uri)
    {:recycled resource}))

  (-valid?
    [this resource]
    true)

  URI->KeyProtocol

  (-uri->key
    [this uri]
    (-> uri
        java.net.URI.
        .getHost
        keyword)))

(defmethod factory "test"
  [uri]
  (TestFactory.))

(defn- generate-resources
  [f scheme host]
  (lazy-seq
    (cons (->> (str scheme "://" host "/" (apply str (gen/sample gen/char-alpha (inc (rand-int 32)))))
               (-create! f))
          (generate-resources f scheme host))))

(defn- extract-q
  [uri]
  (-> uri
      java.net.URI.
      .getQuery
      (subs 2)
      Integer/parseInt))

(deftest severin-test
  (testing "factory implements FactoryProtocol"
   (is (satisfies? FactoryProtocol (factory "test://-")))
   (is (thrown? java.lang.IllegalArgumentException (factory "file://-")))
   (is (thrown? java.lang.IllegalArgumentException (factory (gen/generate gen/string-alphanumeric 32)))))
  (testing "create pool"
   (let [sizes (gen/sample (gen/choose 1 100) 100)
         pools (map #(make-pool :max-size %) sizes)]
     ; test meta data and type of created pools:
     (is (every? (fn [[p s]] (and (instance? clojure.lang.Ref p) (= (:max-size (meta p)) s)))
                 (map vector pools sizes)))))
  (testing "maximum size"
   (let [p (make-pool :max-size 100)
         f (factory "test://-")]
     ; add example resources to test pool
     (doseq [uri (take 50 (generate-resources f "test" "localhost"))] (dispose! p uri))
     (doseq [uri (take 100 (generate-resources f "test" "example.org"))] (dispose! p uri))
     (is (= (count @p) 2))
     (is (= (count (:localhost @p)) 50))
     (is (= (count (:example.org @p)) 100))
     ; fill pool until maximum-size is reached:
     (doseq [uri (take 50 (generate-resources f "test" "localhost"))] (dispose! p (:uri uri) uri))
     (doseq [uri (take 100 (generate-resources f "test" "example.org"))] (dispose! p (:uri uri) uri))
     (is (= (count @p) 2))
     (is (= (count (:localhost @p)) 100))
     (is (= (count (:example.org @p)) 100))))
  (testing "create & dispose"
   (let [p (make-pool :max-size 50)
         uris (map #(create! p (format "test://192.168.0.1/foobar?q=%d" %)) (range 15))]
     (doseq [uri (take 10 uris)] (dispose! p uri))
     (let [uris' (doall (repeatedly 5 #(create! p "test://192.168.0.1/baz")))
           qs (map #(extract-q (:uri %)) (:192.168.0.1 @p))]
       (is (= 5 (count qs)))
       (is (every? (fn [[a b]] (= a b)) (map vector qs (range 4 0 -1))))
       (is (every? (fn [[a b]] (= a b))
                   (map vector (map #(extract-q (get-in % [:recycled :uri])) (take 5 uris'))
                   (range 9 4 -1))))))))
