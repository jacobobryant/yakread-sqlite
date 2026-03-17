(ns com.yakread.model.digest
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.routes :as routes]
   [tick.core :as tick]))

;; TODO rethink what things should be in here vs model.recommend

(def ^:private in-clause-batch-size
  "Max number of IDs per IN clause to stay within SQLite's statement length limit."
  500)

(defn recent-items [{:biff/keys [query now]
                     :user/keys [digest-last-sent]
                     :keys [all-item-ids]}]
  (let [t0 (cond-> (tick/<< now (tick/new-period 2 :weeks))
             digest-last-sent (tick/max digest-last-sent))]
    (->> (partition-all in-clause-batch-size all-item-ids)
         (mapcat (fn [batch]
                   (query {:select [:item/id :item/ingested-at]
                           :from :item
                           :where [:and
                                   [:in :item/id batch]
                                   [:< t0 :item/ingested-at]]})))
         (sort-by :item/ingested-at #(compare %2 %1))
         (take 50)
         (mapv #(select-keys % [:item/id])))))

(defresolver digest-sub-items [{:biff/keys [query now]} {:user/keys [digest-last-sent subscriptions]}]
  {::pco/input [(? :user/digest-last-sent)
                {:user/subscriptions [{:sub/items [:item/id]}]}]
   ::pco/output [{:user/digest-sub-items [:item/id]}]}
  {:user/digest-sub-items
   (recent-items
    {:biff/query query
     :biff/now now
     :user/digest-last-sent digest-last-sent
     :all-item-ids (mapv :item/id (mapcat :sub/items subscriptions))})})

(defresolver digest-bookmarks [{:biff/keys [query now]} {:user/keys [digest-last-sent bookmarks]}]
  {::pco/input [(? :user/digest-last-sent)
                {:user/bookmarks [:item/id]}]
   ::pco/output [{:user/digest-bookmarks [:item/id]}]}
  ;; TODO bookmark recency should be based on :user-item/bookmarked-at, not :item/ingested-at
  {:user/digest-bookmarks
   (recent-items
    {:biff/query query
     :biff/now now
     :user/digest-last-sent digest-last-sent
     :all-item-ids (mapv :item/id bookmarks)})})

(defresolver settings-info [{:user/keys [digest-days send-digest-at]}]
  {:digest.settings/freq-text (case (count digest-days)
                                7 "daily"
                                1 "weekly"
                                (str (count digest-days) "x/week"))
   :digest.settings/time-text (tick/format "h:mm a" (tick/time send-digest-at))})

(defresolver subject-item [{:keys [user/digest-discover-recs]}]
  {::pco/input [{:user/digest-discover-recs [:item/id
                                             (? :item/title)]}]
   ::pco/output [{:digest/subject-item [:item/id]}]}
  (when-some [item (->> digest-discover-recs
                        (filter :item/title)
                        first)]
    {:digest/subject-item item}))

(defresolver mailersend-payload [{:mailersend/keys [from reply-to]}
                                 {:user/keys [email from-the-sample]
                                  :digest/keys [html
                                                text
                                                subject-item]}]
  {::pco/input [:user/email
                (? :user/from-the-sample)
                (? :digest/html)
                (? :digest/text)
                {(? :digest/subject-item) [:item/id
                                           :item/title]}]
   ::pco/output [:digest/payload]}
  (when html
    {:digest/payload {:from {:email from
                             :name (if from-the-sample
                                     "Yakread (formerly The Sample)"
                                     "Yakread")}
                      :reply_to {:email reply-to :name "Yakread"}
                      :to [{:email email}]
                      :subject (-> subject-item
                                   (get :item/title "Your reading digest")
                                   ;; turns out spam filters don't like this in subject lines
                                   (str/replace #"(?i)dark web" ""))
                      :html html
                      :text text
                      :precedence_bulk true}}))

(defresolver unsubscribe-url [{:biff/keys [base-url href-safe]}
                               {:keys [user/id]}]
  {:digest/unsubscribe-url
   (str base-url (href-safe routes/unsubscribe {:action :action/unsubscribe
                                                :user/id id}))})

(def module
  {:resolvers [digest-sub-items
               digest-bookmarks
               settings-info
               subject-item
               mailersend-payload
               unsubscribe-url]})
