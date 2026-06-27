(ns com.yakread.work.account
  (:require
   [clojure.data.generators :as gen]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [tick.core :as tick]))

(fx/defmachine export-user-data
  :start
  (fn [{:keys [biff/job]}]
    (log/info ::start)
    (let [{:keys [user/id]} job]
      {:biff.fx/graph [:biff.fx/graph
                       {:entity {:user/id id}
                        :query [:user/id
                                :user/email
                                :user/timezone
                                :user.export/feed-subs
                                :user.export/bookmarks
                                :user.export/favorites]}]
       :biff.fx/temp-dir [:biff.fx/temp-dir {}]
       :biff.fx/next :upload}))

  :upload
  (fn [{:keys [biff/now biff.fx/graph biff.fx/temp-dir]}]
    (log/info ::upload)
    (let [{:user/keys [id email timezone]
           :user.export/keys [feed-subs bookmarks favorites]} graph
          timezone (or timezone "UTC")
          dir (io/file temp-dir (str "yakread-export-"
                                     (tick/format "yyyy-MM-dd" (tick/in now (tick/zone timezone)))
                                     "-" (inst-ms (tick/instant now)) "-" id))
          zipfile (str dir ".zip")
          s3-key (.getName (io/file zipfile))]
      [{:biff.fx/write [:biff.fx/write
                        (for [[basename content] [["feed-subscriptions.opml" feed-subs]
                                                  ["bookmarks.csv" bookmarks]
                                                  ["favorites.csv" favorites]]]
                          {:file (io/file dir basename) :content content})]}
       {:biff.fx/zip [:biff.fx/zip {:source dir
                                    :destination zipfile}]}
       {:biff.fx/delete-files [:biff.fx/delete-files dir]}
       {:biff.fx/s3 [:biff.fx/s3
                     {:config-ns 'yakread.s3.export
                      :key       s3-key
                      :method    "PUT"
                      :body      (io/file zipfile)
                      :headers   {"x-amz-acl"    "private"
                                  "content-type" "application/zip"}}]}
       {:biff.fx/delete-files [:biff.fx/delete-files zipfile]}
       {:biff.fx/s3-presigned-url [:biff.fx/s3-presigned-url
                                   {:config-ns 'yakread.s3.export
                                    :key s3-key
                                    :method "GET"
                                    :expires-at (tick/>> (tick/instant now) (tick/of-days 7))}]}
       {:biff.fx/next :send
        ::email email}]))

  :send
  (fn [{:keys [biff.fx/s3-presigned-url ::email]}]
    (log/info ::upload)
    {:biff.fx/email [:biff.fx/email
                     {:template :export
                      :to email
                      :download-url s3-presigned-url}]}))

(fx/defmachine delete-account
  :start
  (fn [{:keys [biff/job]}]
    (log/info "deleting account" job)
    (let [{:keys [user/id]} job]
      {:biff.fx/graph [:biff.fx/graph
                       {:entity {:user/id id}
                        :query [:user/id
                                [:? :user/email-username]
                                [:? {:user/subscriptions [:sub/id
                                                          [:? :sub/feed-id]]}]
                                [:? {:user/ad [:ad/id
                                               [:? :ad/customer-id]]}]
                                [:? :user/customer-id]]}]
       :biff.fx/next :delete}))

  :delete
  (fn [{:keys [biff.fx/graph biff/secret]}]
    (let [{:user/keys [id subscriptions ad customer-id email-username]} graph]
      [{:biff.fx/http [:biff.fx/http
                       (for [customer-id [customer-id (:ad/customer-id ad)]
                             :when customer-id]
                         {:method :delete
                          :url (str "https://api.stripe.com/v1/customers/" customer-id)
                          :basic-auth [(secret :stripe/api-key)]
                          :socket-timeout 10000
                          :connection-timeout 10000})]}
       {:biff.fx/sqlite [:biff.fx/sqlite
                         (concat
                          (for [{:keys [sub/id sub/feed-id]} subscriptions
                                :when feed-id]
                            {:delete-from :sub :where [:= :sub/id id]})
                          (when ad
                            [{:delete-from :ad :where [:= :ad/id (:ad/id ad)]}])
                          [{:delete-from :user :where [:= :user/id id]}]
                          (when email-username
                            [{:insert-into :deleted-user
                              :values [{:deleted-user/id (gen/uuid)
                                        :deleted-user/email-username-hash (lib.core/sha256 email-username)}]}]))]
        :biff.fx/next :delete-email-batch}]))

  :delete-email-batch
  (fn [{:keys [biff/query biff/job session ::email-ids]}]
    (let [{:keys [user/id]} job
          email-ids (or email-ids
                        (->> {:select :item/id
                              :from :sub
                              :where [:and
                                      [:= :sub/user-id (:uid session)]
                                      [:is-not :sub/email-from nil]]
                              :join [:item [:= :item/email-sub-id :sub/id]]}
                             (query)
                             (mapv :item/id)))
          batch (when (not-empty email-ids)
                  (query
                   {:select [:item/id :item/content-key :item/email-raw-content-key]
                    :from :item
                    :where [:in :item/id (take 500 email-ids)]}))
          remaining (drop 500 email-ids)]
      (when (not-empty batch)
        [{:biff.fx/s3 [:biff.fx/s3
                       (for [email batch
                             [k config-ns] [[:item/content-key 'yakread.s3.content]
                                            [:item/email-raw-content-key 'yakread.s3.emails]]
                             :when (get email k)]
                         {:key (str (get email k))
                          :config-ns config-ns
                          :method "DELETE"})]}
         {:biff.fx/sqlite [:biff.fx/sqlite
                           [{:delete-from :item
                             :where [:in :item/id (mapv :item/id batch)]}]]}
         {:biff.fx/next :delete-email-batch
          ::email-ids remaining}]))))

(def module
  {:queues [{:id :work.account/export-user-data
             :consumer #'export-user-data
             :n-threads 1}
            {:id :work.account/delete-account
             :consumer #'delete-account
             :n-threads 1}]})
