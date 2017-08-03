(ns partition.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.edn :as edn]
            [org.httpkit.client :as http]
            [clojure.tools.cli :as cli]
            [clojure.xml :as xml]
            [clojure.zip :as zip])
  (:gen-class))

(defmacro flip
  [f a b]
  `(~f ~b ~a))

(defn nil-safe
  [f]
  (fn [a & args]
    (apply f (or a "") args)))

(def default-branch "master")

(def default-time 10000)

(def artifacts-url-template "https://circleci.com/api/v1/project/%user%/%project%/latest/artifacts?branch=%branch%&filter=successful&circle-token=%token%")

(defn tap
  [f v]
  (f v)
  v)

(defn log
  [minimal-verbosity-level actual-verbosity-level]
  (fn [message]
    (when (>= actual-verbosity-level minimal-verbosity-level)
      (println message))))

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

(defn delete-files
  [node-index all-files in]
  (fn [index cube]
    (when (= index node-index)
      (let [files-in-cube (into #{} (map first) cube)
            all-files (into [] (map first) all-files)
            files-to-delete (filter #(not (contains? files-in-cube %)) all-files)]
        (doseq [file files-to-delete]
          (io/delete-file (str in "/" file) :silently true))))))

(defn ok-response?
  [error status]
  (and (nil? error) (= status 200)))

(defn artifacts-url
  [options]
  (reduce (fn [url [key value]]
            (string/replace url (str "%" (name key) "%") (str value)))
          artifacts-url-template
          options))

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
