(ns com.yakread
  (:require
   [cld.core :as cld]
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.repl :as tn-repl]
   [com.biffweb :as biff]
   [com.biffweb.core :as biff.core]
   [com.biffweb.sqlite :as biff.sqlite]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.planner :as pcp]
   [com.yakread.lib.auth :as lib.auth]
   [com.yakread.lib.email :as lib.email]
   [com.yakread.lib.s3 :as lib.s3]
   [com.yakread.lib.sqlite :as lib.sqlite]
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
   [nrepl.cmdline :as nrepl-cmd]
   [reitit.ring :as reitit-ring]
   [taoensso.telemere :as tel]
   [taoensso.telemere.tools-logging :as tel.tl]
   [tick.core :as tick]
   [time-literals.read-write :as time-literals])
  (:gen-class))

(def modules
  (concat modules/modules
          [(lib.auth/module
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

(defn on-save [sys]
  (biff/add-libs)
  (biffs/generate-modules-file!
   {:output-file "src/com/yakread/modules.clj"
    :search-dirs ["src/com/yakread/app"
                  "src/com/yakread/model"
                  "src/com/yakread/ui_components"
                  "src/com/yakread/work"]})
  (when-not (:clojure.tools.namespace.reload/error (biff/eval-files! sys))
    ;(test/run-all-tests #"com.yakread.*-test")
    (time ((requiring-resolve 'com.yakread.lib.test/run-examples!) {}))
    (log/info :done)))

(def columns
  (apply merge (keep :biff.sqlite/columns modules)))

(def pathom-env (pci/register (->> (mapcat :resolvers modules)
                                   (concat (lib.sqlite/sqlite-resolvers columns))
                                   (mapv lib.pathom/wrap-debug))))

(defn merge-context [{:keys [yakread/model
                             biff/jwt-secret]
                      :as ctx}]
  (-> ctx
      (merge pathom-env
             {:yakread.model/get-candidates (constantly {})
              :yakread.model/item-candidate-ids #{}}
             (some-> model deref)
             {:biff/router router
              :biff.fx/handlers fx/handlers
              :biff/now (tick/instant)
              :com.yakread/sign-redirect (fn [url]
                                           {:redirect url
                                            :redirect-sig (biffs/signature (jwt-secret) url)})
              :biff/href-safe (partial lib.route/href-safe ctx)
              :biff/query  (partial biff.sqlite/execute ctx)})
      (pcp/with-plan-cache (atom {}))))

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
        (let [error-text (str/join "\n\n\n\n"
                                   (for [error batch]
                                     ((tel/format-signal-fn {}) error)))
              preview (subs error-text 0 (min 1000 (count error-text)))
              s3-key (str "errors/" (inst-ms (tick/instant)) ".txt")]
          (try
            (lib.s3/request ctx {:config-ns 'yakread.s3.errors
                                 :key s3-key
                                 :method "PUT"
                                 :body error-text
                                 :headers {"x-amz-acl" "private"
                                           "content-type" "text/plain"}})
            (let [url (lib.s3/presigned-url ctx {:config-ns 'yakread.s3.errors
                                                 :key s3-key
                                                 :method "GET"
                                                 :expires-at (tick/>> (tick/instant) (tick/of-days 7))})]
              (send-email ctx
                          {:template :alert
                           :subject (str domain " error")
                           :text (str "Error preview:\n\n" preview "\n\nView full log: " url)
                           :rum [:div
                                 [:p "Preview: " [:code preview]]
                                 [:p [:a {:href url} "View full error log (expires in 7 days)"]]]}))
            (catch Exception e
              ;; Fall back to inline error text if S3 upload fails
              (send-email ctx
                          {:template :alert
                           :subject (str domain " error")
                           :text error-text
                           :rum [:pre error-text]}))))))))

(defn use-error-reporting [{:keys [biff.error-reporting/enabled] :as ctx}]
  (if-not enabled
    ctx
    (let [ctx (assoc ctx :biff.error-reporting/state (atom {:errors []
                                                            :last-sent-at 0}))]
      (tel/add-handler! :biff/error-reporting (fn [signal] (handle-error ctx signal)))
      (update ctx :biff/stop conj #(tel/remove-handler! :biff/error-reporting)))))

(defonce system (atom {}))

(def components
  [biff/use-aero-config
   use-error-reporting
   lib.sqlite/use-sqlite
   lib.spark/use-spark
   biff/use-queues
   biff/use-jetty
   biff/use-chime
   biff/use-beholder
   lib.smtp/use-server])

(def initial-system {:biff/modules #'modules
                     :biff/merge-context-fn #'merge-context
                     :biff/after-refresh `start
                     :biff/handler #'handler
                     :biff.sqlite/columns columns
                     :biff/router router
                     :biff/send-email #'lib.email/send-email
                     :biff.beholder/on-save #'on-save
                     :biff.fx/handlers fx/handlers
                     :com.yakread/home-feed-cache (atom {})
                     lib.pathom/plan-cache-kw (atom {})
                     :biff.smtp/accept? #'smtp/accept?
                     :biff.smtp/deliver #'smtp/deliver*
                     :biff/components components
                     :biff.middleware/on-error #'ui/on-error
                     :com.yakread/pstats (atom {})})

(defn start []
  (try
    (let [new-system (biff.core/start
                      initial-system
                      #'modules
                      components)]
      (reset! system new-system)
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
    (apply nrepl-cmd/-main args)))

(defn refresh []
  (biff.core/stop @system)
  (tn-repl/refresh :after `start)
  :done)
