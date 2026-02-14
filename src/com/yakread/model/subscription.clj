(ns com.yakread.model.subscription
  (:require
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.util.biff-staging :as biffs]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.serialize :as lib.serialize]
   [xtdb.api :as-alias xt]))

(defresolver user-subs [{:keys [biff/conn*]} {:keys [user/id]}]
  #::pco{:output [{:user/subscriptions [:sub/id]}
                  {:user/unsubscribed [:sub/id]}]}
  (let [{subbed false
         unsubbed true} (->> (biffs/q conn*
                                       {:select [:sub/id :sub/email-unsubscribed-at]
                                        :from :sub
                                        :where [:= :sub/user-id id]})
                              (group-by (comp some? :sub/email-unsubscribed-at)))]
    {:user/subscriptions (or subbed [])
     :user/unsubscribed (mapv #(select-keys % [:sub/id]) unsubbed)}))

(defresolver email-title [{:keys [sub/email-from]}]
  {:sub/title (str/replace email-from #"\s<.*>" "")})

(defresolver feed-sub-title [{:keys [biff/conn*]} {:keys [sub/feed-id]}]
  {::pco/input [:sub/feed-id]}
  (when feed-id
    (let [feed (first (biffs/q conn*
                               {:select [:feed/title :feed/url]
                                :from :feed
                                :where [:= :feed/id feed-id]}))]
      {:sub/title ((some-fn :feed/title :feed/url) feed)})))

(defresolver email-subtitle [{:keys [sub/latest-item]}]
  {::pco/input [{:sub/latest-item [:item/email-reply-to]}]}
  {:sub/subtitle (:item/email-reply-to latest-item)})

(defresolver feed-sub-subtitle [{:keys [biff/conn*]} {:keys [sub/feed-id]}]
  #::pco{:input [:sub/feed-id]}
  (when feed-id
    (let [feed (first (biffs/q conn*
                               {:select [:feed/url]
                                :from :feed
                                :where [:= :feed/id feed-id]}))]
      {:sub/subtitle (:feed/url feed)})))

(defresolver latest-email-item [{:keys [biff/conn*]} {:sub/keys [doc-type id]}]
  {::pco/output [{:sub/latest-item [:xt/id]}]}
  (when-some [item (when (= doc-type :sub/email)
                     (first (biffs/q conn*
                                     {:select :item/id
                                      :from :item
                                      :where [:= :item/email-sub-id id]
                                      :order-by [[:item/ingested-at :desc]]
                                      :limit 1})))]
    {:sub/latest-item (clojure.set/rename-keys item {:item/id :xt/id})}))

(defresolver sub-id->xt-id [{:keys [sub/id]}]
  {:xt/id id})

(defresolver xt-id->sub-id [{:keys [xt/id]}]
  {:sub/id id})

(defresolver sub-info [{:keys [sub/id sub/feed-id sub/email-from sub/record-type]}]
  #::pco{:input [:sub/id
                 (? :sub/feed-id)
                 (? :sub/email-from)
                 :sub/record-type]
         :output [:sub/source-id
                  :sub/doc-type]}
  (cond
    (= record-type :email)
    {:sub/source-id id
     :sub/doc-type :sub/email}

    feed-id
    {:sub/source-id feed-id
     :sub/doc-type :sub/feed}))

(defn- doc-type->source-key [doc-type]
  (case doc-type
    :sub/email :item/email-sub-id
    :sub/feed :item/feed-id))

(defresolver total [{:keys [biff/conn*]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type]
   ::pco/output [:sub/total]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (->> (biffs/q conn*
                              {:union-all
                               (for [[doc-type source-ids]  doc-type->source-ids
                                     :let [source-key (doc-type->source-key doc-type)]]
                                 {:select [[source-key :source-id]
                                           [:%count.* :total]]
                                  :from :item
                                  :where [:in source-key source-ids]
                                  :group-by [source-key]})})
                     (mapv #(-> % (assoc :sub/source-id (:source-id %) :sub/total (:total %))
                                  (dissoc :source-id :total))))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:keys [sub/source-id]}]
                              {:sub/source-id source-id
                               :sub/total 0}))))

(defresolver items-read [{:keys [biff/conn*]} inputs]
  {::pco/input [:sub/source-id
                :sub/doc-type
                {:sub/user [:xt/id]}]
   ::pco/output [:sub/items-read]
   ::pco/batch? true}
  (let [results (->> (biffs/q conn*
                              {:union
                               (for [[{user-id :xt/id} inputs] (group-by :sub/user inputs)
                                     [doc-type inputs] (group-by :sub/doc-type inputs)
                                     :let [source-ids (mapv :sub/source-id inputs)
                                           source-key (doc-type->source-key doc-type)]]
                                 {:select [[:user-item/user-id :user-id]
                                           [source-key :source-id]
                                           [[:count :user-item/item-id] :items-read]]
                                  :from :user-item
                                  :join [:item [:= :item/id :user-item/item-id]]
                                  :where [:and
                                          [:= :user-item/user-id user-id]
                                          [:in source-key source-ids]
                                          [:is-not [:coalesce
                                                    :user-item/viewed-at
                                                    :user-item/skipped-at
                                                    :user-item/favorited-at
                                                    :user-item/disliked-at
                                                    :user-item/reported-at]
                                           nil]]
                                  :group-by [:user-item/user-id source-key]})})
                     (mapv (fn [record]
                             {:sub/user (array-map :xt/id (:user-id record))
                              :sub/source-id (:source-id record)
                              :sub/items-read (:items-read record)})))]
    (lib.core/restore-order inputs
                            (juxt :sub/user :sub/source-id)
                            results
                            (fn [{:sub/keys [user source-id]}]
                              {:sub/user user
                               :sub/source-id source-id
                               :sub/items-read 0}))))

(defresolver items-unread [{:sub/keys [total items-read]}]
  {:sub/items-unread (- total items-read)
   ;; for backwards compat
   :sub/unread (- total items-read)})

(defresolver published-at [{:keys [biff/conn*]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type :sub/created-at]
   ::pco/output [:sub/published-at]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (->> (biffs/q conn*
                             {:union-all
                              (for [[doc-type source-ids]  doc-type->source-ids
                                    :let [source-key (doc-type->source-key doc-type)]]
                                {:select [[source-key :source-id]
                                          [[:max [:coalesce :item/published-at :item/ingested-at]]
                                           :published-at]]
                                 :from :item
                                 :where [:in source-key source-ids]
                                 :group-by [source-key]})})
                    (mapv #(-> % (assoc :sub/source-id (:source-id %) :sub/published-at (:published-at %))
                                 (dissoc :source-id :published-at))))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:sub/keys [source-id created-at]}]
                              {:sub/source-id source-id
                               :sub/published-at created-at}))))

(defresolver items [{:keys [biff/conn*]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type]
   ::pco/output [{:sub/items [:xt/id]}]
   ::pco/batch? true}
  (let [doc-type->source-ids (lib.core/group-by-to :sub/doc-type :sub/source-id inputs)
        results (->> (biffs/q conn*
                              {:union-all
                               (for [[doc-type source-ids]  doc-type->source-ids
                                     :let [source-key (doc-type->source-key doc-type)]]
                                 {:select [[source-key :source-id]
                                           :item/id]
                                  :from :item
                                  :where [:in source-key source-ids]})})
                     (group-by :source-id)
                     (mapv (fn [[source-id items]]
                             {:sub/source-id source-id
                              :sub/items (mapv #(select-keys % [:xt/id]) items)})))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results
                            (fn [{:sub/keys [source-id]}]
                              {:sub/source-id source-id
                               :sub/items []}))))

(defresolver latest-item [{:keys [biff/conn*]} inputs]
  {::pco/input [:sub/source-id :sub/doc-type :sub/published-at]
   ::pco/output [{:sub/latest-item [:xt/id]}]
   ::pco/batch? true}
  (let [doc-type->subs (group-by :sub/doc-type inputs)
        results (into []
                      (map (fn [{:keys [source-id xt/id]}]
                             {:sub/source-id source-id
                              :sub/latest-item {:xt/id id}}))
                      (biffs/q conn*
                               ;; TODO try [:coalesce :item/feed-id :item/email-sub-id]
                               {:union-all
                                (for [[doc-type subs*]  doc-type->subs
                                      :let [source-key (doc-type->source-key doc-type)]]
                                  {:select [[source-key :source-id]
                                            :item/id]
                                   :from :item
                                   :where (into [:or]
                                                (for [{:sub/keys [source-id published-at]} subs*]
                                                  [:and
                                                   [:= source-key source-id]
                                                   [:= [:coalesce :item/published-at :item/ingested-at]
                                                    published-at]]))})}))]
    (lib.core/restore-order inputs
                            :sub/source-id
                            results)))

(defresolver from-params [{:keys [biff/conn* session path-params params]} _]
  {::pco/output [{:params/sub [:sub/id {:sub/user [:user/id]}]}]}
  (let [sub-id (or (:sub/id params)
                   (lib.serialize/url->uuid (:sub-id path-params)))
        [sub] (when (some? sub-id)
                (biffs/q conn*
                         {:select [:sub/id [:sub/user-id :user-id]]
                          :from :sub
                          :where [:= :sub/id sub-id]}))]
    (when (and sub (= (:uid session) (:user-id sub)))
      {:params/sub (-> sub (assoc :sub/user (array-map :user/id (:user-id sub)))
                           (dissoc :user-id))})))

;; TODO turn from-params into a batch resolver and delete this
(defresolver params-checked [{:keys [biff/conn* session params]} _]
  #::pco{:output [{:params.checked/subscriptions [:sub/id]}]}
  (let [sub-ids (mapv #(some-> % name parse-uuid) (keys (:subs params)))
        subs* (when (not-empty sub-ids)
                (biffs/q conn*
                         {:select [:sub/id [:sub/user-id :user-id]]
                          :from :sub
                          :where [:in :sub/id sub-ids]}))]

    (when (and (= (count sub-ids) (count subs*))
               (every? #(= (:uid session) (:user-id %)) subs*))
      {:params.checked/subscriptions
       (mapv (fn [{:keys [sub/id user-id]}]
               {:sub/id id
                :sub/user {:user/id user-id}})
             subs*)})))

(defresolver unread-items [{:keys [biff/conn*]} subscriptions]
  #::pco{:input [{:sub/user [:xt/id]}
                 {:sub/items [:xt/id]}]
         :output [{:sub/unread-items [:xt/id]}]
         :batch? true}
  (let [user-item-pairs (for [{:sub/keys [user items]} subscriptions
                              item items]
                          [(:xt/id user) (:xt/id item)])
        results (biffs/q conn*
                         {:select [:user-item/user-id :user-item/item-id]
                          :from :user-item
                          :where [:and
                                  (into [:or]
                                        (for [[user-id item-id] user-item-pairs]
                                          [:and
                                           [:= :user-item/user-id user-id]
                                           [:= :user-item/item-id item-id]]))
                                  [:is-not
                                   [:coalesce
                                    :user-item/viewed-at
                                    :user-item/skipped-at
                                    :user-item/favorited-at
                                    :user-item/disliked-at
                                    :user-item/reported-at]
                                   nil]]})
        user->read-items (update-vals (group-by :user-item/user-id results)
                                      (fn [results]
                                        (into #{} (map :user-item/item-id) results)))]
    (mapv (fn [{:sub/keys [user items]}]
            (let [read-items (get user->read-items (:xt/id user) #{})
                  unread-items (filterv (complement (comp read-items :xt/id)) items)]
              {:sub/unread-items unread-items}))
          subscriptions)))

(defresolver mv [{:keys [biff/conn*]} subs*]
  {::pco/input  [:sub/id]
   ::pco/output [{:sub/mv [:mv-sub/id]}]
   ::pco/batch?  true}
  (let [results (mapv (fn [{:keys [mv-id sub-id]}]
                        {:sub/id sub-id
                         :sub/mv {:mv-sub/id mv-id}})
                      (biffs/q conn*
                               {:select [[:mv-sub/id :mv-id]
                                         [:mv-sub/sub-id :sub-id]]
                                :from :mv-sub
                                :where [:in :mv-sub/sub-id (mapv :sub/id subs*)]}))]
    (lib.core/restore-order subs* :sub/id results)))

(def module {:resolvers [user-subs
                         sub-info
                         sub-id->xt-id
                         xt-id->sub-id
                         email-title
                         feed-sub-title
                         items-unread
                         published-at
                         items
                         latest-item
                         from-params
                         params-checked
                         unread-items
                         feed-sub-subtitle
                         latest-email-item
                         email-subtitle
                         mv
                         total
                         items-read]})
