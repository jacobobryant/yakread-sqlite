(ns com.yakread
  (:require
   [cld.core :as cld]
   [clojure.data.generators :as gen]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as tn-repl]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.biffweb.experimental.auth :as biffx-auth]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.planner :as pcp]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.email :as lib.email]
   [com.yakread.lib.sqlite :as lib.sqlite]
   [com.yakread.model.schema :as sqlite-schema]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.pathom :as lib.pathom]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.smtp :as lib.smtp]
   [com.yakread.lib.spark :as lib.spark]
   [com.yakread.lib.ui :as ui]
   [com.yakread.modules :as modules]
   [com.yakread.routes :as routes]
   [com.yakread.smtp :as smtp]
   [com.yakread.util.biff-staging :as biffs]
   [honey.sql :as sql]
   [malli.core :as malli]
   [malli.experimental.time :as malli.t]
   [malli.registry :as malr]
   [malli.util :as malli.u]
   [next.jdbc :as jdbc]
   [nrepl.cmdline :as nrepl-cmd]
   [reitit.ring :as reitit-ring]
   [taoensso.telemere :as tel]
   [taoensso.telemere.tools-logging :as tel.tl]
   [tick.core :as tick]
   [time-literals.read-write :as time-literals])
  (:import
   [com.zaxxer.hikari HikariConfig HikariDataSource])
  (:gen-class))

(defn use-sqlite
  "Biff component that starts a HikariCP connection pool for SQLite
   and puts it in the :biff/conn key."
  [{:biff.sqlite/keys [db-path]
    :or {db-path "storage/sqlite/main.db"}
    :as ctx}]
  (let [datasource (HikariDataSource.
                    (doto (HikariConfig.)
                      (.setJdbcUrl (str "jdbc:sqlite:" db-path))
                      (.setConnectionInitSql
                       (str/join ";" ["PRAGMA journal_mode=WAL"
                                      "PRAGMA busy_timeout = 5000"
                                      "PRAGMA foreign_keys = ON"
                                      "PRAGMA synchronous = NORMAL"]))))]
    (-> ctx
        (assoc :biff/conn datasource)
        (update :biff/stop conj #(.close datasource)))))

(defn sqlite-get-user-id
  "Look up user ID by email from SQLite. The first argument is ignored
   (the auth module passes the XTDB node, but we use conn* instead)."
  [conn* _node email]
  (-> (biffs/q conn*
        {:select :user/id
         :from :user
         :where [:= :user/email email]})
      first
      :user/id))

(def modules
  (concat modules/modules
          [(biffx-auth/module
            #:biff.auth{:app-path (href routes/for-you)
                        :check-state false})]))

(def router (reitit-ring/router
             [["" {:middleware lib.mid/default-site-middleware}
               (keep :routes modules)]
              ["" {:middleware [biff/wrap-api-defaults]}
               (keep :api-routes modules)]]))

(def handler (-> (biff/reitit-handler {:router router})
                 biff/wrap-base-defaults
                 lib.mid/wrap-stripe-event
                 lib.mid/wrap-monitoring
                 lib.mid/wrap-internal-error))

(def static-pages (apply biff/safe-merge (map :static modules)))

(defn generate-assets! [_]
  (biff/export-rum static-pages "target/resources/public")
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]}))

(defn on-save [sys]
  (biff/add-libs)
  (biffs/generate-modules-file!
   {:output-file "src/com/yakread/modules.clj"
    :search-dirs ["src/com/yakread/app"
                  "src/com/yakread/model"
                  "src/com/yakread/ui_components"
                  "src/com/yakread/work"]})
  (when-not (:clojure.tools.namespace.reload/error (biff/eval-files! sys))
    (generate-assets! sys)
    ;(test/run-all-tests #"com.yakread.*-test")
    (time ((requiring-resolve 'com.yakread.lib.test/run-examples!)
           {:ext "materialized_views_test.edn"}
           ))
    (log/info :done)))

(def malli-opts
  {:registry (apply malr/composite-registry
                    (malli/default-schemas)
                    (malli.t/schemas)
                    (malli.u/schemas)
                    (keep :schema modules))})

(def malli-opts*
  {:registry (malr/composite-registry
              (malli/default-schemas)
              (malli.t/schemas)
              (malli.u/schemas)
              sqlite-schema/schema)})

;; TODO pull into a lib function
(doseq [schema-map (keep :schema modules)
        k (keys schema-map)
        :let [ex (try
                   (malli/schema k malli-opts)
                   nil
                   (catch Exception e
                     e))]]
  (assert (nil? ex)
          (str "Schema for " k " is invalid: " (pr-str (ex-data ex)))))

(def pathom-env (pci/register (->> (mapcat :resolvers modules)
                                   (concat (lib.sqlite/sqlite-resolvers malli-opts*))
                                   (mapv lib.pathom/wrap-debug))))

(defn merge-context [{:keys [yakread/model
                             biff/jwt-secret]
                      :as ctx}]
  (let [;snapshots (biff/index-snapshots ctx)
        ]
    (-> ctx
        ;;(biff/assoc-db)
        (merge pathom-env
               (some-> model deref)
               {:biff/router router
                :biff.fx/handlers fx/handlers
                ;:biff/db (:biff/db snapshots)
                ;:biff.index/snapshots snapshots
                :biff/now (tick/in (tick/zoned-date-time) "UTC")
                :com.yakread/sign-redirect (fn [url]
                                             {:redirect url
                                              :redirect-sig (biffs/signature (jwt-secret) url)})
                :biff/href-safe (partial lib.route/href-safe ctx)})
        (pcp/with-plan-cache (atom {})))))

;; TODO use a lib.pipe thing for this
(defn- handle-error [{:keys [biff/send-email biff/domain biff.error-reporting/state] :as ctx} signal]
  (when (= (:level signal) :error)
    (let [max-errors 50
          rate-limit-seconds (* 60 5)
          now-seconds (/ (System/nanoTime) (* 1000 1000 1000.0))
          {:keys [batch]} (swap! state
                                 (fn [{:keys [errors last-sent-at] :as state}]
                                   (let [errors (conj errors signal)]
                                     (if-let [batch (and (< rate-limit-seconds (- now-seconds last-sent-at))
                                                         (not-empty (subvec errors (max 0 (- (count errors) max-errors)))))]

                                       {:batch batch
                                        :errors []
                                        :last-sent-at now-seconds}
                                       (-> state
                                           (assoc :errors errors)
                                           (dissoc :batch))))))]
      (when (not-empty batch)
        (send-email ctx
                    {:template :alert
                     :subject (str domain " error")
                     :rum [:pre
                           (str/join "\n\n\n\n"
                                     (for [error batch]
                                       ((tel/format-signal-fn {}) error)))]})))))

(defn use-error-reporting [{:keys [biff.error-reporting/enabled] :as ctx}]
  (if-not enabled
    ctx
    (let [ctx (assoc ctx :biff.error-reporting/state (atom {:errors []
                                                            :last-sent-at 0}))]
      (tel/add-handler! :biff/error-reporting (fn [signal] (handle-error ctx signal)))
      (update ctx :biff/stop conj #(tel/remove-handler! :biff/error-reporting)))))

(defn- translate-xtdb-query-for-sqlite
  "Translate an XTDB-style query (used by external modules like biff auth)
   to SQLite-native format. Handles table renaming, column renaming, and
   value coercion."
  [query]
  (let [;; Translate :from — symbol or keyword
        from-raw (:from query)
        from-kw (cond
                  (symbol? from-raw) (keyword from-raw)
                  (keyword? from-raw) from-raw
                  :else from-raw)
        sqlite-tbl (biffs/sqlite-table from-kw)
        ;; Translate :select
        translate-key (fn [k]
                        (if (keyword? k)
                          (biffs/rename-key k from-kw)
                          k))
        new-select (cond
                     (keyword? (:select query)) (translate-key (:select query))
                     (vector? (:select query)) (mapv translate-key (:select query))
                     :else (:select query))
        ;; Translate :where
        translate-where (fn translate-where [clause]
                          (if (vector? clause)
                            (mapv (fn [x]
                                    (cond
                                      (keyword? x) (translate-key x)
                                      (vector? x) (translate-where x)
                                      (uuid? x) (biffs/coerce-sqlite-value x)
                                      :else x))
                                  clause)
                            clause))
        new-where (when (:where query) (translate-where (:where query)))]
    (cond-> (assoc query :from sqlite-tbl :select new-select)
      new-where (assoc :where new-where))))

(defn- untranslate-result-keys
  "Map SQLite result keys back to XTDB-style keys for external modules."
  [table-kw row]
  (let [sqlite->xt (set/map-invert biffs/xt->sqlite-key)
        sqlite-tbl (biffs/sqlite-table table-kw)
        id-key (keyword (name sqlite-tbl) "id")]
    (into {}
          (map (fn [[k v]]
                 (let [xt-k (cond
                              (= k id-key) :xt/id
                              (contains? sqlite->xt k) (get sqlite->xt k)
                              :else k)]
                   [xt-k v])))
          row)))

(defn use-sqlite-auth
  "Biff component that overrides biffx/submit-tx and biffx/q to use SQLite,
   and provides a SQLite-based get-user-id for the auth module.
   This ensures the auth module (which hardcodes biffx/submit-tx and biffx/q)
   writes to and reads from SQLite instead of XTDB."
  [{:keys [biff/conn*] :as ctx}]
  (let [original-submit-tx biffx/submit-tx
        original-q biffx/q]
    ;; Override biffx/submit-tx to route through our SQLite submit-tx
    (alter-var-root #'biffx/submit-tx
      (constantly (fn [ctx & [tx]]
                    (biffs/submit-tx ctx tx))))
    ;; Override biffx/q to route through our SQLite q, translating XTDB-style queries
    (alter-var-root #'biffx/q
      (constantly (fn [_conn query]
                    (let [from-raw (:from query)
                          from-kw (cond (symbol? from-raw) (keyword from-raw)
                                        (keyword? from-raw) from-raw
                                        :else from-raw)
                          translated (translate-xtdb-query-for-sqlite query)
                          results (biffs/q conn* translated)]
                      (mapv (partial untranslate-result-keys from-kw) results)))))
    ;; Provide SQLite-based get-user-id
    (-> ctx
        (assoc :biff.auth/get-user-id (partial sqlite-get-user-id conn*))
        (update :biff/stop conj
                (fn []
                  (alter-var-root #'biffx/submit-tx (constantly original-submit-tx))
                  (alter-var-root #'biffx/q (constantly original-q)))))))

(defonce system (atom {}))

(def components
  [biff/use-aero-config
   use-error-reporting
   biffx/use-xtdb2
   lib.sqlite/use-sqlite
   use-sqlite-auth
   lib.spark/use-spark
   biff/use-queues
   ;biffx/use-xtdb2-listener
   biff/use-jetty
   biff/use-chime
   biff/use-beholder
   lib.smtp/use-server])

(def initial-system {:biff/modules #'modules
                     :biff/merge-context-fn #'merge-context
                     :biff/after-refresh `start
                     :biff/handler #'handler
                     :biff/malli-opts (lib.core/->DerefMap #'malli-opts)
                     :biff/malli-opts* (lib.core/->DerefMap #'malli-opts*)
                     :biff/router router
                     :biff/send-email #'lib.email/send-email
                     :biff.beholder/on-save #'on-save
                     :biff.fx/handlers fx/handlers
                     ;;:biff.xtdb/tx-fns biff/tx-fns
                     :com.yakread/home-feed-cache (atom {})
                     lib.pathom/plan-cache-kw (atom {})
                     :biff.smtp/accept? #'smtp/accept?
                     :biff.smtp/deliver #'smtp/deliver*
                     :biff/components components
                     :biff.middleware/on-error #'ui/on-error
                     :com.yakread/pstats (atom {})})

(defn start []
  (try
    (let [new-system (reduce (fn [system component]
                               (log/info "starting:" (str component))
                               (component system))
                             initial-system
                             components)]
      (reset! system new-system)
      (generate-assets! new-system)
      (log/info "System started.")
      (log/info "Go to" (:biff/base-url new-system))
      new-system)
    (catch Exception e
      (log/error e)
      ;; Give the error handler some time to report
      (Thread/sleep 5000)
      (throw e))))

(defn -main [& args]
  (tel.tl/tools-logging->telemere!)
  ;; TODO probably move this into config
  (tel/set-ns-filter! {:disallow ["org.apache.spark.*"
                                  "org.sparkproject.*"
                                  "org.apache.http.client.*"]})
  (log/info "heap size:" (/ (.maxMemory (Runtime/getRuntime)) (* 1024 1024)))
  (cld/default-init!)
  (time-literals/print-time-literals-clj!)
  (alter-var-root #'gen/*rnd* (constantly (java.util.Random. (inst-ms (java.time.Instant/now)))))
  (let [{:keys [biff.nrepl/args]} (start)]
    #_(future
        (biff/catchall-verbose
         (migrate.xtdb1/export node "storage/migrate-export")
         (log/info "done exporting")))
    (apply nrepl-cmd/-main args)))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start)
  :done)
