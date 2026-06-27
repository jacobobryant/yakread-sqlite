(ns com.yakread.work.digest
  (:require
   [cheshire.core :as cheshire]
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [tick.core :as tick]))

(defn in-send-time-window? [{:keys [biff/now user]}]
  (let [{:user/keys [digest-days send-digest-at timezone]} user
        digest-days (or digest-days #{:sunday :monday :tuesday :wednesday :thursday :friday :saturday})
        send-digest-at (or send-digest-at "08:00")
        timezone (or timezone "US/Pacific")

        timezone (java.time.ZoneId/of timezone)
        now-date (tick/date (tick/in now timezone))
        send-at-begin (-> now-date
                          (tick/at (tick/time send-digest-at))
                          (tick/in timezone))
        send-at-begin (cond-> send-at-begin
                        (tick/<= now send-at-begin)
                        (tick/<< (tick/of-days 1)))
        send-at-end (tick/>> send-at-begin (tick/of-hours 2))
        now-day (tick/day-of-week (tick/in now timezone))]
    (and (tick/<= send-at-begin now send-at-end)
         (contains? digest-days (keyword (str/lower-case now-day))))))

(defn send-digest? [{:keys [biff/now user]}]
  (and (tick/<= (tick/of-hours 18)
                (tick/between (or (:user/digest-last-sent user) lib.core/epoch) now))
       (in-send-time-window? {:biff/now now :user user})
       (not (:user/suppressed-at user))))

(fx/defmachine queue-prepare-digest
  :start
  (fn [{:keys [biff/query biff/queues yakread.work.digest/enabled yakread.work.digest/job-limit biff/now]}]
    ;; There is a small race condition where the queue could be empty even though the
    ;; :work.digest/prepare-digest consumer(s) are still processing jobs, in which case the
    ;; corresponding users could receive two digests. To deal with that, we could
    ;; have the :work.digest/send-digest queue consumer check for the most recently sent digest for
    ;; each user and make sure it isn't within the past e.g. 6 hours. Probably doesn't matter
    ;; though. Queues should probably expose the number of in-progress jobs.
    (when (and enabled (= 0 (.size (:work.digest/prepare-digest queues))))
      (let [users (->> (query
                        {:select [:user/id
                                  :user/email
                                  :user/digest-last-sent
                                  :user/suppressed-at
                                  :user/digest-days
                                  :user/send-digest-at
                                  :user/timezone]
                         :from :user})
                       (filterv #(send-digest? {:biff/now now :user %}))
                       (sort-by :user/email))
            users (cond->> users
                    job-limit (take job-limit))]
        (when (not-empty users)
          (log/info "Sending digest to" (count users) "users"))
        (if (= enabled :dry-run)
          (run! #(log/info (:user/email %)) users)
          {:biff.fx/queue [:biff.fx/queue
                           {:jobs (for [user users]
                                    [:work.digest/prepare-digest user])}]})))))

(fx/defmachine prepare-digest
  :start
  (fn [{user :biff/job}]
    {:biff.fx/graph [:biff.fx/graph
                     {:entity {:user/id (:user/id user)}
                      :query  [[:? :digest/payload]
                               {[:? :digest/subject-item] [:item/id
                                                           :item/title]}
                               {[:? :user/ad-rec] [:ad/id]}
                               {[:? :user/icymi-recs] [:item/id]}
                               {[:? :user/digest-discover-recs] [:item/id]}]}]
     :biff.fx/next :end
     ;; hack until model code is refactored to not use session.
     :session {:uid (:user/id user)}})

  :end
  (fn [{:keys [biff.fx/graph biff/now] user :biff/job}]
    (when (:digest/payload graph)
      (let [digest-id (gen/uuid)
            digest-items (for [[k kind] [[:user/icymi-recs :digest-item.kind/icymi]
                                         [:user/digest-discover-recs :digest-item.kind/discover]]
                               item (get graph k)]
                           {:digest-item/id (gen/uuid)
                            :digest-item/digest-id digest-id
                            :digest-item/item-id (:item/id item)
                            :digest-item/kind [:lift kind]})
            sqlite (concat
                    [{:update :user
                      :set {:user/digest-last-sent now}
                      :where [:= :user/id (:user/id user)]}
                     {:insert-into :digest
                      :values [(into {:digest/id       digest-id
                                      :digest/user-id  (:user/id user)
                                      :digest/sent-at  now}
                                     (filter (comp lib.core/something? val))
                                     {:digest/subject-id  (get-in graph [:digest/subject-item :item/id])
                                      :digest/ad-id       (get-in graph [:user/ad-rec :ad/id])})]}]
                    (when (not-empty digest-items)
                      [{:insert-into :digest-item
                        :values (vec digest-items)}]))]
        [{:biff.fx/sqlite [:biff.fx/sqlite sqlite]}
         {:biff.fx/queue [:biff.fx/queue
                          {:id :work.digest/send-digest
                           :job {:user/email (:user/email user)
                                 :digest/id digest-id
                                 :digest/payload (:digest/payload graph)}}]}]))))

(def default-payload-size-limit (* 50 1000 1000))
(def default-n-emails-limit 50)

(fx/defmachine send-digest
  :start
  (fn [{:keys [biff/queues ::n-emails-limit]
        :or {n-emails-limit default-n-emails-limit}}]
    (cond
      (= 0 (.size (:work.digest/prepare-digest queues)))
      ;; Wait in case the last jobs are still being processed.
      [{:biff.fx/sleep [:biff.fx/sleep 10000]}
       {:biff.fx/drain-queue [:biff.fx/drain-queue nil]
        :biff.fx/next :start*}]

      (<= n-emails-limit (.size (:work.digest/send-digest queues)))
      {:biff.fx/drain-queue [:biff.fx/drain-queue nil]
       :biff.fx/next :start*}

      :else
      {:biff.fx/sleep [:biff.fx/sleep 5000]
       :biff.fx/next :start}))

  :start*
  (fn [{jobs :biff.fx/drain-queue
        :biff/keys [secret]
        ::keys [payload-size-limit n-emails-limit]
        :or {payload-size-limit default-payload-size-limit
             n-emails-limit default-n-emails-limit}}]
    (let [jobs* (map #(assoc % :digest/payload-str (cheshire/generate-string (:digest/payload %)))
                     jobs)
          last-job (->> jobs*
                        (map-indexed vector)
                        (reductions (fn [{:keys [size]} [i job]]
                                      (assoc job
                                             :size (+ size (count (:digest/payload-str job)))
                                             :index i))
                                    {:size 0})
                        rest
                        ;; Mailersend limits bulk requests to 50 MB / 500 email objects.
                        ;; https://developers.mailersend.com/api/v1/email.html#send-bulk-emails
                        (take-while #(and (< (:size %) payload-size-limit)
                                          (< (:index %) n-emails-limit)))
                        last)
          n-jobs       (inc (get last-job :index -1))
          requeue-jobs (drop n-jobs jobs)
          jobs         (take n-jobs jobs*)
          body         (str "[" (str/join "," (mapv :digest/payload-str jobs)) "]")]
      (log/info "Bulk sending to" (count jobs) "users")
      (doseq [{:keys [user/email]} jobs]
        (log/info "Sending to" email))
      {:biff.fx/queue [:biff.fx/queue
                       {:jobs (for [job requeue-jobs]
                                [:work.digest/send-digest job])}]
       :biff.fx/http [:biff.fx/http
                      {:method :post
                       :url "https://api.mailersend.com/v1/bulk-email"
                       :oauth-token (secret :mailersend/api-key)
                       :content-type :json
                       :as :json
                       :body body}]
       :biff.fx/next :record-bulk-send
       ::payload-size (count body)
       ::digest-ids (mapv :digest/id jobs)}))

  :record-bulk-send
  (fn [{:keys [biff/now ::digest-ids ::payload-size biff.fx/http]}]
    (let [bulk-send-id (gen/uuid)]
      [{:biff.fx/sqlite [:biff.fx/sqlite
                         [{:insert-into :bulk-send
                           :values [{:bulk-send/id bulk-send-id
                                     :bulk-send/sent-at now
                                     :bulk-send/payload-size payload-size
                                     :bulk-send/mailersend-id (get-in http [:body :bulk_email_id])
                                     :bulk-send/digests [:lift digest-ids]}]}
                          {:update :digest
                           :set {:digest/bulk-send-id bulk-send-id}
                           :where [:in :digest/id digest-ids]}]]}
       ;; Mailersend limits bulk request to 15 / minute.
       ;; https://developers.mailersend.com/api/v1/email.html#send-bulk-emails
       {:biff.fx/sleep [:biff.fx/sleep (long (+ (/ 60000 9) 1000))]}])))

(def module
  {:tasks [{:task #'queue-prepare-digest
            :schedule (lib.core/every-n-minutes 30)}]
   :queues [{:id :work.digest/prepare-digest
             :consumer #'prepare-digest
             :n-threads 4}
            {:id :work.digest/send-digest
             :consumer #'send-digest
             :n-threads 1}]})

(comment
  ;; integration test
  (repl/with-context
    (fn [{:keys [biff/query] :as ctx}]
      (doseq [user (query {:select [:user/id :user/email]
                           :from :user
                           :where [:in :user/email []]})]
        (biff/submit-job ctx :work.digest/prepare-digest user)))))
