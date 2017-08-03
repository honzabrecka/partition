(ns partition.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [org.httpkit.client :as http]
            [clojure.tools.cli :as cli]
            [clojure.xml :as xml]
            [clojure.zip :as zip])
  (:gen-class)
  (:use clojure.test))

(defmacro flip
  [f a b]
  `(~f ~b ~a))

(defn nil-safe
  [f]
  (fn [a & args]
    (apply f (cons (or a "") args))))

(def default-branch "master")

(def default-time 10000)

(def artifacts-url-template "https://circleci.com/api/v1/project/%user%/%project%/latest/artifacts?branch=%branch%&filter=successful&circle-token=%access-token%")

(defn tap
  [f v]
  (f v)
  v)

(deftest tap-test
  (is (= 1 @(tap #(vswap! % inc) (volatile! 0)))))

(defn log
  [minimal-verbosity-level actual-verbosity-level]
  (fn [message]
    (when (>= actual-verbosity-level minimal-verbosity-level)
      (println message))))

(deftest log-test
  (with-redefs [println identity]
    (is (nil? ((log 10 0) "whatever")))
    (is (= "whatever" ((log 0 10) "whatever")))))

(defn exit
  [status msg]
  (println msg)
  (System/exit status))

(defn partition-into
  [select-fn n col]
  (let [coll (reverse (sort-by select-fn col))]
    (reduce (fn [cubes val]
              (let [ordered-cubes (sort-by #(apply + (select-fn val) (map select-fn %1)) cubes)]
                (cons (conj (first ordered-cubes) val) (rest ordered-cubes))))
            (map vector (take n coll))
            (drop n coll))))

(deftest partition-into-test
  (testing "partitioning"
    (are [expected input count]
      (= expected (partition-into identity count input))
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
           (partition-into second 2 [[:file 1]
                                     [:file 2]
                                     [:file 2]
                                     [:file 3]])))))

(defn zip-str
  [s]
  (->> s
       .getBytes
       java.io.ByteArrayInputStream.
       xml/parse
       zip/xml-zip))

(def parsers
  {"xunit" {:url-pattern "^.+?[xj]unit\\/.+?\\.xml$"
            :parser      (fn [s]
                           (into {}
                                 (comp (filter #(and (= (:tag %) :testsuite)
                                                     (not= (:content %) nil)))
                                       (map #(vector (->> (get-in % [:attrs :name])
                                                          (re-matches #"^.+?\((.+?)\)$")
                                                          (second)
                                                          (flip (nil-safe string/split) #"/")
                                                          (last))
                                                     (->> (get-in % [:attrs :time])
                                                          (Float/parseFloat)
                                                          (* 1000)))))
                                 (-> s
                                     zip-str
                                     zip/children)))}})

(deftest parsers-xunit-test
  (is (= {"faq.js" 5000.0}
         ((get-in parsers ["xunit" :parser])
           "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"Mocha Tests\" time=\"5.7\" tests=\"1\" failures=\"0\">\n  <testsuite name=\"Root Suite\" timestamp=\"2017-01-06T19:26:23\" tests=\"0\" failures=\"0\" time=\"0\">\n  </testsuite>\n  <testsuite name=\"FAQ (/cypress/integration/faq.js)\" timestamp=\"2017-01-06T19:26:23\" tests=\"1\" failures=\"0\" time=\"5\">\n    <testcase name=\"FAQ (/cypress/integration/faq.js) cy.should - assert that &lt;title&gt; is Vivus FAQ\" time=\"5.7\" classname=\"cy.should - assert that &lt;title&gt; is Vivus FAQ\">\n    </testcase>\n  </testsuite>\n</testsuites>")))
  (is (= {"" 5000.0}
         ((get-in parsers ["xunit" :parser])
           "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites name=\"Mocha Tests\" time=\"5.7\" tests=\"1\" failures=\"0\">\n  <testsuite name=\"Root Suite\" timestamp=\"2017-01-06T19:26:23\" tests=\"0\" failures=\"0\" time=\"0\">\n  </testsuite>\n  <testsuite name=\"FAQ\" timestamp=\"2017-01-06T19:26:23\" tests=\"1\" failures=\"0\" time=\"5\">\n    <testcase name=\"FAQ cy.should - assert that &lt;title&gt; is Vivus FAQ\" time=\"5.7\" classname=\"cy.should - assert that &lt;title&gt; is Vivus FAQ\">\n    </testcase>\n  </testsuite>\n</testsuites>"))))

(defn test-files
  [dir]
  (into {}
        (comp (filter #(.isFile %))
              (map #(.getName %))
              (map #(vector % default-time)))
        (file-seq (io/file dir))))

(defn safe-merge
  [a b]
  (reduce (fn [acc [key value]]
            (if (get acc key)
              (assoc acc key value)
              acc))
          a b))

(deftest safe-merge-test
  (is (= {:a 10 :b 0 :c 15}
         (safe-merge {:a 0 :b 0 :c 0}
                     {:a 10 :c 15 :x 20}))))

(defn copy-files
  [in out]
  (fn [index cube]
    (doseq [row cube]
      (let [[file _] row
            source (str in "/" file)
            target (str out index "/" file)]
        (io/make-parents target)
        (io/copy (io/file source)
                 (io/file target))))))

(deftest copy-files-test
  (let [calls (volatile! [])]
    (with-redefs [io/file identity
                  io/copy (fn [a b] (vswap! calls conj [a b]))
                  io/make-parents (fn [_])]
      ((copy-files "foo" "bar") 1 [["a" 10]
                                   ["b" 10]])
      (is (= [["foo/a" "bar1/a"]
              ["foo/b" "bar1/b"]]
             @calls)))))

(defn delete-files
  [node-index all-files in]
  (fn [index cube]
    (when (= index node-index)
      (let [files-in-cube (into #{} (map first) cube)
            all-files (into [] (map first) all-files)
            files-to-delete (filter #(not (contains? files-in-cube %)) all-files)]
        (doseq [file files-to-delete]
          (io/delete-file (str in "/" file) :silently true))))))

(deftest delete-files-test
  (let [calls (volatile! [])
        index 0
        all-files {"a" 10 "b" 10 "c" 10}
        cube [["b" 10]]
        in "foo"]
    (with-redefs [io/delete-file (fn [a _ _] (vswap! calls conj a))]
      (testing "index does not match node-index -> do nothing"
        (vreset! calls [])
        ((delete-files index all-files in) 55 cube)
        (is (= [] @calls)))
      (testing "index does match node-index -> delete files not included in cube"
        (vreset! calls [])
        ((delete-files index all-files in) 0 cube)
        (is (= ["foo/a" "foo/c"] @calls))))))

(defn ok-response?
  [error status]
  (and (nil? error) (= status 200)))

(deftest ok-response?-test
  (is (true? (ok-response? nil 200)))
  (is (false? (ok-response? "err" 200)))
  (is (false? (ok-response? nil 500))))

(defn artifacts-url
  [options]
  (reduce (fn [url [key value]]
            (string/replace url (str "%" (name key) "%") (str value)))
          artifacts-url-template
          options))

(deftest artifacts-url-test
  (is (= artifacts-url-template
         (artifacts-url {})))
  (is (= "https://circleci.com/api/v1/project/abc/xyz/latest/artifacts?branch=master&filter=successful&circle-token=token"
         (artifacts-url {:user    "abc"
                         :project "xyz"
                         :branch  "master"
                         :token   "token"})))
  (is (= artifacts-url-template
         (artifacts-url {:whatever 2}))))

(defn fetch-artifacts
  [log {:keys [token] :as options}]
  (let [{:keys [status body error]} @(http/get (artifacts-url options) {:as :text})]
    (if (ok-response? error status)
      (let [futures (->> (clojure.edn/read-string body)
                         (map (fn [{:keys [url]}]
                                (when-let [{:keys [parser]} (->> parsers
                                                                 vals
                                                                 (some #(when (re-matches (re-pattern (:url-pattern %)) url)
                                                                          %)))]
                                  [parser
                                   (http/get (str url "?circle-token=" token) {:as :text})])))
                         (doall))]
        (->> futures
             (map (fn [[parser future]]
                    (when future
                      (let [{:keys [status error body]} (deref future)]
                        (when (ok-response? error status)
                          (parser body))))))
             (reduce merge {})))
      (do (log (str "Fetch artifacts error: (" status ") " error))
          {}))))

(deftest fetch-artifacts-test
  (testing "an error"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:error "An error"}))]
      (is (= {}
             (fetch-artifacts (fn [_]) {})))))
  (testing "bad status"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 500}))]
      (is (= {}
             (fetch-artifacts (fn [_]) {})))))
  (testing "ok, but no suitable artifact url"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 200
                                              :body   "[{:url \"whatever\"}]"}))]
      (is (= {}
             (fetch-artifacts (fn [_]) {})))))
  (testing "ok, but artifact error"
    (with-redefs [parsers {"test" {:url-pattern "[a-z]"
                                   :parser      identity}}
                  http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:error "An error"}
                                                 {:status 200
                                                  :body   "({:url \"a\"})"})))]
      (is (= {}
             (fetch-artifacts (fn [_]) {})))))
  (testing "ok, but artifact invalid status"
    (with-redefs [parsers {"test" {:url-pattern "[a-z]"
                                   :parser      identity}}
                  http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 500}
                                                 {:status 200
                                                  :body   "({:url \"a\"})"})))]
      (is (= {}
             (fetch-artifacts (fn [_]) {})))))
  (testing "ok"
    (with-redefs [parsers {"test" {:url-pattern "[a-z]"
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
             (fetch-artifacts (fn [_]) {:regexp "[a-z]"}))))))

(defn cli-options
  []
  [["-h" "--help" "Help"]
   ["-t" "--token ACCESS_TOKEN" "Access token to artifacts."]
   ["-u" "--user USER" "User. Default to CIRCLE_PROJECT_USERNAME env variable."
    :default (System/getenv "CIRCLE_PROJECT_USERNAME")]
   ["-p" "--project PROJECT" "Project. Default to CIRCLE_PROJECT_REPONAME env variable."
    :default (System/getenv "CIRCLE_PROJECT_REPONAME")]
   ["-b" "--branch BRANCH" "Branch"
    :default default-branch]
   ["-c" "--node-total NODE_TOTAL" "Count of nodes (workers). Default to CIRCLE_NODE_TOTAL env variable."
    :default (Integer/parseInt (or (System/getenv "CIRCLE_NODE_TOTAL") "1"))
    :parse-fn #(Integer/parseInt %)]
   ["-i" "--node-index NODE_INDEX" "Index of current node (worker). Default to CIRCLE_NODE_INDEX env variable."
    :default (Integer/parseInt (or (System/getenv "CIRCLE_NODE_INDEX") "0"))
    :parse-fn #(Integer/parseInt %)]
   (let [valid-options #{"copy" "delete"}]
     ["-m" "--mode MODE" "Mode. Whether delete or copy files."
      :default "delete"
      :validate [#(contains? valid-options %)
                 (str "Must be one of " valid-options)]])
   ["-v" nil "Verbosity level"
    :id :verbosity
    :default 0
    :assoc-fn (fn [m k _] (update-in m [k] inc))]])

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args (cli-options))]
    (cond
      (:help options)
        (exit 1 summary)
      (and (> (:node-total options) 1)
           (not (:token options)))
        (exit 1 "Access token (--token option) is required when count of nodes (workers) > 1")
      (= (count arguments) 0)
        (exit 1 "Path to tests is missing")
      errors
        (exit 1 errors))
    (let [[in out] arguments
          test-files# (test-files in)]
      (->> (when (> (:node-total options) 1)
             (fetch-artifacts (log 0 (:verbosity options)) options))
           (safe-merge test-files#)
           (partition-into second (:node-total options))
           (tap (log 1 (:verbosity options)))
           (keep-indexed (if (= (:mode options) "copy")
                           (copy-files in (or out in))
                           (delete-files (:node-index options) test-files# in)))
           (dorun)))))
