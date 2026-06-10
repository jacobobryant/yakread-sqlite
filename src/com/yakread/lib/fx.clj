(ns com.yakread.lib.fx
  (:require
   [cheshire.core :as cheshire]
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [com.biffweb :as biff]
   [com.biffweb.fx :as biff.fx]
   [com.biffweb.sqlite :as biff.sqlite]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.yakread.lib.s3 :as lib.s3]))

(defn safe-for-url? [s]
  (boolean (re-matches #"[a-zA-Z0-9-_.+!*]+" s)))

(defn- autogen-endpoint [ns* sym]
  (let [href (str "/_biff/api/" ns* "/" sym)]
    (doseq [segment [ns* sym]]
      (assert (safe-for-url? (str segment))
              (str "URL segment would contain invalid characters: " segment)))
    href))

(let [all-methods [:get :post :put :delete :head :options :trace :patch :connect]]
  (defn route* [uri route-name & {:as state->transition-fn}]
    (let [machine* (apply biff.fx/machine route-name (mapcat identity state->transition-fn))]
      [uri
       (into {:name route-name}
             (comp (filter state->transition-fn)
                   (map (fn [method]
                          [method machine*])))
             all-methods)])))

(defn wrap-result [f]
  (fn [ctx]
    (f ctx (:biff.fx/result ctx))))

(defn wrap-hiccup [f]
  (fn [& args]
    (let [result (apply f args)]
      (if (and (vector? result) (keyword? (first result)))
        {:body result}
        result))))

(defmacro defroute [sym & args]
  (let [[uri & kvs] (if (string? (first args))
                      args
                      (into [nil] args))
        uri (or uri (autogen-endpoint *ns* sym))
        route-name (keyword (str *ns*) (str sym))]
    `(def ~sym
       (let [[& {:as params#}] [~@kvs]]
         (route* ~uri
                 ~route-name
                 (-> params#
                     (update-vals wrap-hiccup)
                     (merge {:start (fn [{:keys [~'request-method]}]
                                      {:biff.fx/next ~'request-method})})))))))

(defmacro defroute-pathom [sym & args]
  (let [[uri query & kvs] (if (string? (first args))
                            args
                            (into [nil] args))
        uri (or uri (autogen-endpoint *ns* sym))
        route-name (keyword (str *ns*) (str sym))]
    `(def ~sym
       (let [query# ~query
             [& {:as params#}] [~@kvs]]
         (route* ~uri
                 ~route-name
                 (-> params#
                     (update-vals (comp wrap-hiccup wrap-result))
                     (merge {:start (fn [{:keys [~'request-method]}]
                                      {:biff.fx/result [:biff.fx/pathom query#]
                                       :biff.fx/next ~'request-method})})))))))

(defmacro defmachine [sym & args]
  (let [machine-name (keyword (str *ns*) (str sym))]
    `(def ~sym (biff.fx/machine ~machine-name ~@args))))

(defn call-js [{:biff/keys [secret] :as ctx} params]
  (let [{:keys [base-url fn-name input local catch-exceptions]}
        (merge (biff/select-ns-as ctx 'com.yakread.fx.js nil) params)]
    (try
      (if local
        (-> (biff/sh
             "node" "-e" "console.log(JSON.stringify(require('./main.js').main(JSON.parse(fs.readFileSync(0)))))"
             :dir (str "cloud-fns/packages/yakread/" fn-name)
             :in (cheshire/generate-string input))
            (cheshire/parse-string true)
            :body)
        (-> (str base-url fn-name)
            (http/post {:headers {"X-Require-Whisk-Auth" (secret :com.yakread.fx.js/secret)}
                        :as :json
                        :form-params input
                        :socket-timeout 10000
                        :connection-timeout 10000
                        :throw-exceptions (not catch-exceptions)})
            :body))
      (catch Exception e
        (if catch-exceptions
          {:exception e}
          (throw e))))))

(comment
  (for [local [true false]]
    (call-js @com.yakread/system
             {:fn-name "readability",
              :input {:url "https://example.com?foo=bar", :html "hello"}
              :local local})))

(defn- http* [request]
  (try
    (-> (http/request request)
        (assoc :url (:url request))
        (dissoc :http-client))
    (catch Exception e
      (if (get request :throw-exceptions true)
        (throw e)
        {:url (:url request)
         :exception e}))))

(def handlers
  {:biff.fx/http (fn [_ctx input]
                   (if (map? input)
                     (http* input)
                     (mapv http* input)))
   :biff.fx/email (fn [{:keys [biff/send-email] :as ctx} input]
                    ;; This can be used in cases where we want a generic email interface not tied
                    ;; to a particular provider. For sending digests we need mailersend-specific
                    ;; features, so we use :biff.pipe/http there instead.
                    (send-email ctx input))
   :biff.fx/sqlite (fn [ctx input]
                     (let [stmts (if (map? input) [input] input)]
                       (doseq [stmt stmts]
                         (biff.sqlite/execute ctx stmt))
                       nil))
   :biff.fx/pathom (fn [ctx input]
                     (let [{:keys [entity query]} (if (map? input)
                                                    input
                                                    {:query input})]
                       (p.eql/process ctx (or entity {}) query)))
   :biff.fx/slurp (fn [_ctx file]
                    (slurp file))
   :biff.fx/spit (fn [_ctx {:keys [file content]}]
                   (io/make-parents file)
                   (spit file content))
   :biff.fx/queue (fn [ctx {:keys [id job jobs wait-for-result]}]
                    (if jobs
                      (mapv (fn [[id job]]
                              (biff/submit-job ctx id job))
                            jobs)
                      (cond-> ((if wait-for-result
                                 biff/submit-job-for-result
                                 biff/submit-job)
                               ctx
                               id
                               job)
                        wait-for-result deref)))
   :biff.fx/s3 (fn [ctx input]
                 (if (map? input)
                   (lib.s3/request ctx input)
                   (mapv #(lib.s3/request ctx %) input)))
   :biff.fx/s3-presigned-url lib.s3/presigned-url
   :biff.fx/render (fn [ctx {:keys [route-sym request-method] :as extra-ctx}]
                     (let [[_ {handler request-method}] @(requiring-resolve route-sym)]
                       (handler (merge ctx extra-ctx))))
   :biff.fx/sleep (fn [_ctx ms]
                    (Thread/sleep (long ms)))
   :biff.fx/drain-queue (fn [{:biff/keys [job queue]} _]
                          (let [ll (java.util.LinkedList.)]
                            (.drainTo queue ll)
                            (into [job] ll)))
   :biff.fx/temp-dir (fn [_ctx {:keys [prefix] :or {prefix "biff"}}]
                       (.toFile (java.nio.file.Files/createTempDirectory
                                 prefix
                                 (into-array java.nio.file.attribute.FileAttribute []))))

   :biff.fx/write (fn [_ctx input]
                    (doseq [{:keys [file content]} (if (map? input) [input] input)]
                      (io/make-parents (io/file file))
                      (with-open [w (io/writer file)]
                        (if (string? content)
                          (.write w content)
                          (doseq [line content]
                            (.write w line)
                            (.write line "\n")))))
                    nil)
   :biff.fx/shell (fn [_ctx args]
                    (apply shell/sh args))
   :biff.fx/delete-files (fn [_ctx path]
                           (run! io/delete-file (reverse (file-seq (io/file path)))))
   :com.yakread.fx/js call-js})
