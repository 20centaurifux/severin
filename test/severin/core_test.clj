(ns severin.core-test
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :refer [instrument]]
            [clojure.test :refer :all]
            [clojure.test.check.generators :as gen]
            [severin.core :refer :all]))

(defrecord TestFactory []
  FactoryProtocol
  (-create!
    [_ uri]
    (let [u (java.net.URI. uri)]
      {:uri uri
       :host (.getHost u)
       :path (.getPath u)}))

  (-dispose!
    [_ resource])

  (-recycle!
    [this resource uri]
    (merge (-create! this uri)
           {:recycled resource}))

  (-valid?
    [_ resource]
    true)

  URI->KeyProtocol
  (-uri->key
    [_ uri]
    (-> uri
        java.net.URI.
        .getHost
        keyword)))

(s/def ::uri string?)
(s/def ::host string?)
(s/def ::path string?)
(s/def ::resource (s/keys :req-un [::uri ::host ::path]))

(instrument)

(defmethod make-factory "test"
  [uri]
  (TestFactory.))

(defn- generate-resources
  [f scheme host]
  (lazy-seq
   (cons (->> (str scheme
                   "://"
                   host
                   "/"
                   (apply str (gen/sample gen/char-alpha (inc (rand-int 32)))))
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
    (is (satisfies? FactoryProtocol
                    (make-factory "test://-")))
    (is (thrown? java.lang.IllegalArgumentException
                 (make-factory "file://-")))
    (is (thrown? java.lang.IllegalArgumentException
                 (make-factory (gen/generate gen/string-alphanumeric 32)))))
  (testing "create pool with max-size stored in meta data"
    (let [sizes (gen/sample (gen/choose 1 100) 100)
          pools (map #(make-pool :max-size %) sizes)]
      (is (every? (fn [[p s]] (and (instance? clojure.lang.Ref p)
                                   (= (:max-size (meta p)) s)))
                  (map vector pools sizes)))))
  (testing "disposed resources are stored in different queues"
    (let [p (make-pool :max-size 100)
          f (make-factory "test://-")]
      (doseq [r
              (take 50 (generate-resources f "test" "localhost"))]
        (dispose! p r))
      (doseq [r
              (take 100 (generate-resources f "test" "example.org"))]
        (dispose! p r))
      (is (= (count @p) 2))
      (is (= (count (:localhost @p)) 50))
      (is (= (count (:example.org @p)) 100))
      (testing "queue sizes are limited"
        (doseq [r
                (take 50 (generate-resources f "test" "localhost"))]
          (dispose! p (:uri r) r))
        (doseq [r
                (take 100 (generate-resources f "test" "example.org"))]
          (dispose! p (:uri r) r))
        (is (= (count @p) 2))
        (is (= (count (:localhost @p)) 100))
        (is (= (count (:example.org @p)) 100)))))
  (testing "create & dispose resources"
    (let [p (make-pool :max-size 50)
          resources (map #(create! p
                                   (format "test://192.168.0.1/foobar?q=%d" %))
                         (range 15))]
      (doseq [r (take 10 resources)]
        (is (s/valid? ::resource r))
        (dispose! p r))
      (let [resources' (doall (repeatedly 5 #(create! p "test://192.168.0.1/baz")))
            qs (map #(extract-q (:uri %)) (:192.168.0.1 @p))]
        (is (= 5 (count qs)))
        (is (every? (fn [[a b]] (= a b))
                    (map vector qs (range 4 0 -1))))
        (is (every? (fn [[a b]] (= a b))
                    (map vector
                         (map #(extract-q (get-in % [:recycled :uri]))
                              (take 5 resources'))
                         (range 9 4 -1)))))))
  (testing "with-pool creates and disposes resources"
    (let [p (make-pool)]
      (with-pool p [r "test://localhost"]
        (is (zero? (count @p)))
        (is (= (:uri r) "test://localhost")))
      (is (= (count @p) 1))))
  (testing "resource is disposed when Exception is thrown in with-pool body"
    (let [p (make-pool)]
      (is (thrown?
           Exception
           (with-pool p [r "test://localhost"]
             (/ 1 0))))
      (is (= (count @p) 1)))))
