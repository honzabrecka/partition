(ns partition.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [org.httpkit.client :as http]
            [clojure.tools.cli :as cli])
  (:gen-class)
  (:use clojure.test))

(defmacro != [& params]
  `(not (= ~@params)))

(deftest not-equal-test
  (is (= true
         (!= true false true)))
  (is (= false
         (!= true true true))))

(def artifact-url-default-pattern "nightwatch_[0-9]$")

(def default-time 10000)

(def artifacts-url "https://circleci.com/api/v1/project/%user%/%project%/latest/artifacts?filter=successful&circle-token=%access-token%")

(defn exit
  [status msg]
  (println msg)
  (System/exit status))

(defn partition-into
  [n col]
  (let [coll (reverse (sort-by second col))]
    (reduce (fn [cubes val]
              (let [ordered-cubes (sort-by #(apply + (second val) (map second %1)) cubes)]
                (cons (conj (first ordered-cubes) val) (rest ordered-cubes))))
            (map vector (take n coll))
            (drop n coll))))

(deftest partition-into-test
  (is (= [[[:file 5]
           [:file 4]
           [:file 2]]
          [[:file 8]
           [:file 2]]]
         (partition-into 2 [[:file 4]
                            [:file 5]
                            [:file 2]
                            [:file 8]
                            [:file 2]]))))

(defn parse-nightwatch-output
  [content]
  (->> content
       (re-seq #"(?s)\((.+?\.js)\).+?\(([\d]+)ms\)")
       (reduce (fn [acc [_ file time]]
                 (assoc acc (last (clojure.string/split file #"/")) (Integer/parseInt time)))
               {})))

(deftest parse-nightwatch-output-test
  (testing "empty input"
    (is (= {}
           (parse-nightwatch-output ""))))
  (testing "invalid input"
    (is (= {}
           (parse-nightwatch-output "abc"))))
  (testing "valid input"
    (is (= {"registrationValidation5.js" 9043
            "registrationValidation7.js" 6926}
           (parse-nightwatch-output "\n  User (/web/test/features1/registrationValidation5.js)\n    Should get error message\n\n      ✓ while register with no password (9043ms)\n\n  User (/web/test/features1/registrationValidation7.js)\n    Should get error message\n\n      ✓ while register with no values (6926ms)\n\n\n  25 passing (7m)\n\n")))))

(defn test-files
  [dir]
  (->> dir
       (io/file)
       (file-seq)
       (filter #(.isFile %))
       (reduce (fn [acc file]
                 (assoc acc (.getName file) default-time))
               {})))

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

(defn ok-response?
  [error status]
  (and (nil? error) (= status 200)))

(deftest ok-response?-test
  (is (= true
         (ok-response? nil 200)))
  (is (= false
         (ok-response? "err" 200)))
  (is (= false
         (ok-response? nil 500))))

(defn fetch-artifacts
  [user project access-token artifact-url-pattern]
  (let [url (-> artifacts-url
                (string/replace "%user%" user)
                (string/replace "%project%" project)
                (string/replace "%access-token%" access-token))
        {:keys [status body error]} @(http/get url {:as :text})]
    (if (ok-response? error status)
      (let [futures (->> (clojure.edn/read-string body)
                         (map :url)
                         (filter #(re-matches (re-pattern artifact-url-pattern) %))
                         (map #(http/get (str % "?circle-token=" access-token) {:as :text}))
                         (doall))]
        (->> futures
             (map deref)
             (filter (fn [{:keys [status error]}]
                       (ok-response? error status)))
             (map :body)
             (reduce str "")))
      "")))

(deftest fetch-artifacts-test
  (testing "an error"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:error "An error"}))]
      (is (= ""
             (fetch-artifacts "" "" "" "")))))
  (testing "bad status"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 500}))]
      (is (= ""
             (fetch-artifacts "" "" "" "")))))
  (testing "ok, but no suitable arifact url"
    (with-redefs [http/get (fn [_ _] (future (Thread/sleep 10)
                                             {:status 200 :body "[{:url \"whatever\"}]"}))]
      (is (= ""
             (fetch-artifacts "" "" "" "")))))
  (testing "ok, but arifact error"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:error "An error"}
                                                 {:status 200 :body "({:url \"a\"})"})))]
      (is (= ""
             (fetch-artifacts "" "" "" "[a-z]")))))
  (testing "ok, but arifact invalid status"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 500}
                                                 {:status 200 :body "({:url \"a\"})"})))]
      (is (= ""
             (fetch-artifacts "" "" "" "[a-z]")))))
  (testing "ok"
    (with-redefs [http/get (fn [url _] (future (Thread/sleep 10)
                                               (condp = url
                                                 "a?circle-token=" {:status 200 :body "foo"}
                                                 "b?circle-token=" {:status 200 :body "bar"}
                                                 {:status 200 :body "({:url \"a\"} {:url \"b\"})"})))]
      (is (= "foobar"
             (fetch-artifacts "" "" "" "[a-z]"))))))

(def cli-options
  [["-a" "--access-token ACCESS_TOKEN" "Access Token"]
   ["-u" "--user USER" "User"]
   ["-p" "--project PROJECT" "Project"]
   ["-r" "--regexp REGEXP" "Artifact url pattern"
    :default artifact-url-default-pattern]
   ["-c" "--count COUNT" "Count of workers"
    :parse-fn #(Integer/parseInt %)]])

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (cli/parse-opts args cli-options)]
    (cond
      (not= (count options) (count cli-options)) (exit 5 summary)
      (not= (count arguments) 2) (exit 1 summary)
      errors (exit 4 errors))
    (let [{:keys [user project access-token count regexp]} options
          [in out] arguments]
      (->> (fetch-artifacts user project access-token regexp)
           (parse-nightwatch-output)
           (safe-merge (test-files in))
           (partition-into count)
           (keep-indexed (copy-files in out))
           (dorun)))))