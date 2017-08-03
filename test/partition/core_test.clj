(ns partition.core-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [partition.core :as core]))

(deftest nil-safe-test
  (is (= "" ((core/nil-safe clojure.string/replace) nil "foo" "bar")))
  (is (= "nabaro" ((core/nil-safe clojure.string/replace) "nafooo" #"foo" "bar"))))

(deftest tap-test
  (is (= 1 @(core/tap #(vswap! % inc) (volatile! 0)))))

(deftest log-test
  (with-redefs [println identity]
    (is (nil? ((core/log 10 0) "whatever")))
    (is (= "whatever" ((core/log 0 10) "whatever")))))

(deftest partition-into-test
  (testing "partitioning"
    (are [expected input count]
      (= expected (core/partition-into identity count input))
      [[1]] [1] 1
      [[7 1]] [7 1] 1
      [[7] [1]] [7 1] 2
      [[5 4 2] [8 2]] [4 5 2 8 2] 2
      [[2 2 1] [3 1 1] [5]] [5 2 3 2 1 1 1] 3))
  (testing "complex data structure, not just int"
    (is (= [[[:file 3]
             [:file 1]]
            [[:file 2]
             [:file 2]]]
           (core/partition-into second 2 [[:file 1]
                                          [:file 2]
                                          [:file 2]
                                          [:file 3]])))))


(deftest safe-merge-test
  (is (= {:a 10 :b 0 :c 15}
         (core/safe-merge {:a 0 :b 0 :c 0}
                          {:a 10 :c 15 :x 20}))))

(deftest copy-files-test
  (let [calls (volatile! [])]
    (with-redefs [io/file identity
                  io/copy (fn [a b] (vswap! calls conj [a b]))
                  io/make-parents (fn [_])]
      ((core/copy-files "foo" "bar") 1 [["a" 10]
                                        ["b" 10]])
      (is (= [["foo/a" "bar1/a"]
              ["foo/b" "bar1/b"]]
             @calls)))))

(deftest delete-files-test
  (let [calls (volatile! [])
        index 0
        all-files {"a" 10 "b" 10 "c" 10}
        cube [["b" 10]]
        in "foo"]
    (with-redefs [io/delete-file (fn [a _ _] (vswap! calls conj a))]
      (testing "index does not match node-index -> do nothing"
        (vreset! calls [])
        ((core/delete-files index all-files in) 55 cube)
        (is (= [] @calls)))
      (testing "index does match node-index -> delete files not included in cube"
        (vreset! calls [])
        ((core/delete-files index all-files in) 0 cube)
        (is (= ["foo/a" "foo/c"] @calls))))))

(deftest ok-response?-test
  (is (true? (core/ok-response? nil 200)))
  (is (false? (core/ok-response? "err" 200)))
  (is (false? (core/ok-response? nil 500))))

(deftest artifacts-url-test
  (is (= core/artifacts-url-template
         (core/artifacts-url {})))
  (is (= "https://circleci.com/api/v1/project/abc/xyz/latest/artifacts?branch=master&filter=successful&circle-token=token"
         (core/artifacts-url {:user    "abc"
                              :project "xyz"
                              :branch  "master"
                              :token   "token"})))
  (is (= core/artifacts-url-template
         (core/artifacts-url {:whatever 2}))))

(deftest fetch-artifacts-test
  (testing "an error"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:error "An error"}))]
      (is (= {}
             (core/fetch-artifacts (fn [_]) {})))))
  (testing "bad status"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 500}))]
      (is (= {}
             (core/fetch-artifacts (fn [_]) {})))))
  (testing "ok, but no suitable artifact url"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 200
                                              :body   "[{:url \"whatever\"}]"}))]
      (is (= {}
             (core/fetch-artifacts (fn [_]) {})))))
  (testing "ok, but artifact error"
    (with-redefs [core/parsers {"test" {:url-pattern "[a-z]"
                                        :parser      identity}}
                  http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:error "An error"}
                                                 {:status 200
                                                  :body   "({:url \"a\"})"})))]
      (is (= {}
             (core/fetch-artifacts (fn [_]) {})))))
  (testing "ok, but artifact invalid status"
    (with-redefs [core/parsers {"test" {:url-pattern "[a-z]"
                                        :parser      identity}}
                  http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 500}
                                                 {:status 200
                                                  :body   "({:url \"a\"})"})))]
      (is (= {}
             (core/fetch-artifacts (fn [_]) {})))))
  (testing "ok"
    (with-redefs [core/parsers {"test" {:url-pattern "[a-z]"
                                        :parser      (fn [s] {s 10})}}
                  http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 200
                                                                    :body   "foo"}
                                                 "b?circle-token=" {:status 200
                                                                    :body   "bar"}
                                                 {:status 200
                                                  :body   "({:url \"a\"} {:url \"b\"})"})))]
      (is (= {"bar" 10
              "foo" 10}
             (core/fetch-artifacts (fn [_]) {:regexp "[a-z]"}))))))

(deftest parsers-xunit-test
  (is (= {"faq.js" 5000.0}
         ((get-in core/parsers ["xunit" :parser])
           "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"Mocha Tests\" time=\"5.7\" tests=\"1\" failures=\"0\">\n  <testsuite name=\"Root Suite\" timestamp=\"2017-01-06T19:26:23\" tests=\"0\" failures=\"0\" time=\"0\">\n  </testsuite>\n  <testsuite name=\"FAQ (/cypress/integration/faq.js)\" timestamp=\"2017-01-06T19:26:23\" tests=\"1\" failures=\"0\" time=\"5\">\n    <testcase name=\"FAQ (/cypress/integration/faq.js) cy.should - assert that &lt;title&gt; is Vivus FAQ\" time=\"5.7\" classname=\"cy.should - assert that &lt;title&gt; is Vivus FAQ\">\n    </testcase>\n  </testsuite>\n</testsuites>")))
  (is (= {"" 5000.0}
         ((get-in core/parsers ["xunit" :parser])
           "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"Mocha Tests\" time=\"5.7\" tests=\"1\" failures=\"0\">\n  <testsuite name=\"Root Suite\" timestamp=\"2017-01-06T19:26:23\" tests=\"0\" failures=\"0\" time=\"0\">\n  </testsuite>\n  <testsuite name=\"FAQ\" timestamp=\"2017-01-06T19:26:23\" tests=\"1\" failures=\"0\" time=\"5\">\n    <testcase name=\"FAQ cy.should - assert that &lt;title&gt; is Vivus FAQ\" time=\"5.7\" classname=\"cy.should - assert that &lt;title&gt; is Vivus FAQ\">\n    </testcase>\n  </testsuite>\n</testsuites>"))))
