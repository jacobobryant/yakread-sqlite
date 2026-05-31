(ns tasks
  (:require [com.biffweb.tasks :as tasks]
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

(defn dev []
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
