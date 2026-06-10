(ns tasks
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [com.biffweb.tasks :as tasks]
            [com.biffweb.tasks.lazy.com.biffweb.config :as config]
            [com.biffweb.tasks.lazy.babashka.process :as process]))

(def config (delay (config/use-aero-config {:biff.config/skip-validation true})))

;; modified version of prod-repl task that uses autossh (restarts the ssh connection automatically when it dies)
(defn prod-repl
  "Opens an SSH tunnel so you can connect to the server via nREPL."
  []
  (let [{:keys [biff.tasks/server biff.nrepl/port]} @config
        cmd (if (process/shell {} "which" "autossh")
              "autossh"
              "ssh")]
    (println "Connect to nrepl port" port)
    (spit ".nrepl-port" port)
    (try
      (process/shell {} cmd "-NL" (str port ":localhost:" port) (str "root@" server))
      (catch Exception e
        (prn e))))
  (Thread/sleep 1)
  (recur))

(defn test-stripe []
  (println "Run `stripe login` if this fails")
  (process/shell {} "stripe" "listen" "--forward-to" "localhost:8080/stripe/webhook"))

(defn fn-deploy []
  (process/shell {} "doctl" "serverless" "deploy" "cloud-fns"))

(defn- install-if-missing [path url]
  (when-not (.exists (io/file path))
    (println "Installing" (.getName (io/file path)) "to" path)
    (let [{:keys [exit err]} (sh/sh "curl" "-fsSL" "-o" path url)]
      (when-not (zero? exit)
        (throw (ex-info (str "Failed to download " url) {:exit exit :err err}))))
    (let [{:keys [exit err]} (sh/sh "chmod" "+x" path)]
      (when-not (zero? exit)
        (throw (ex-info (str "Failed to chmod " path) {:exit exit :err err}))))))

(defn- minio-ready? []
  (= "200"
     (:out (sh/sh "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                  "http://localhost:9000/minio/health/live"))))

(defn- ensure-minio []
  (install-if-missing "/usr/local/bin/minio"
                      "https://dl.min.io/server/minio/release/linux-amd64/minio")
  (install-if-missing "/usr/local/bin/mc"
                      "https://dl.min.io/client/mc/release/linux-amd64/mc")
  (when-not (minio-ready?)
    (println "Starting MinIO on :9000")
    (.mkdirs (io/file "/tmp/minio-data"))
    (let [{:keys [exit err]} (sh/sh "bash" "-lc"
                                    (str "MINIO_ROOT_USER=minioadmin "
                                         "MINIO_ROOT_PASSWORD=minioadmin "
                                         "nohup /usr/local/bin/minio server /tmp/minio-data "
                                         "--address :9000 >/tmp/minio.log 2>&1 &"))]
      (when-not (zero? exit)
        (throw (ex-info "Failed to start MinIO" {:exit exit :err err}))))
    (loop [n 0]
      (when-not (minio-ready?)
        (when (<= 30 n)
          (throw (ex-info "MinIO did not become ready" {})))
        (Thread/sleep 1000)
        (recur (inc n)))))
  (let [cfg @config
        origin (:biff.s3/origin cfg)
        access-key (:biff.s3/access-key cfg)
        secret-key ((:biff/secret cfg) :biff.s3/secret-key)
        buckets (remove nil? [(:yakread.s3.content/bucket cfg)
                              (:yakread.s3.emails/bucket cfg)
                              (:yakread.s3.images/bucket cfg)
                              (:yakread.s3.export/bucket cfg)
                              (:yakread.s3.errors/bucket cfg)])]
    (println "Configuring MinIO buckets")
    (let [{:keys [exit err]} (sh/sh "/usr/local/bin/mc" "alias" "set" "local"
                                    origin access-key secret-key)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to configure MinIO alias" {:exit exit :err err}))))
    (doseq [bucket buckets]
      (let [{:keys [exit err]} (sh/sh "/usr/local/bin/mc" "mb" "--ignore-existing"
                                      (str "local/" bucket))]
        (when-not (zero? exit)
          (throw (ex-info (str "Failed to create bucket " bucket)
                          {:exit exit :err err})))))))

(defn dev []
  (ensure-minio)
  (tasks/dev))

(defn deploy []
  (tasks/deploy))

;; Tasks should be vars (#'hello instead of hello) so that `clj -Mdev help` can
;; print their docstrings.
(def custom-tasks
  {"prod-repl"   #'prod-repl
   "test-stripe" #'test-stripe
   "dev"         #'dev
   "deploy"      #'deploy
   "fn-deploy"   #'fn-deploy})

(def tasks (merge tasks/tasks custom-tasks))
