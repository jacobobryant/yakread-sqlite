(ns com.yakread.model.subscription
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.serialize :as lib.serialize]))

(defresolver user-subs [{:biff/keys [query]} {:keys [user/id]}]
  #::pco{:output [{:user/subscriptions [:sub/id]}
                  {:user/unsubscribed [:sub/id]}]}
  (let [{subbed false
         unsubbed true} (->> (query {:select [:sub/id :sub/email-unsubscribed-at]
                                     :from :sub
                                     :where [:= :sub/user-id id]})
                              (group-by (comp some? :sub/email-unsubscribed-at)))]
    {:user/subscriptions (mapv #(select-keys % [:sub/id]) (or subbed []))
     :user/unsubscribed (mapv #(select-keys % [:sub/id]) (or unsubbed []))}))

(defresolver email-title [{:keys [sub/email-from]}]
  #::pco{:input [:sub/email-from]}
  {:sub/title (str/replace email-from #"\s<.*>" "")})

(defresolver feed-sub-title [{:keys [sub/feed]}]
  {::pco/input [{:sub/feed [(? :feed/title)
                             :feed/url]}]}
  {:sub/title ((some-fn :feed/title :feed/url) feed)})

(defresolver email-subtitle [{:biff/keys [query]} {:sub/keys [id record-type]}]
  {::pco/input [:sub/id :sub/record-type]
   ::pco/output [:sub/subtitle]}
  (when (= record-type :sub.record-type/email)
    (when-some [{:item/keys [email-reply-to]}
                (first (query {:select :item/email-reply-to
                               :from :item
                               :where [:= :item/email-sub-id id]
                               :order-by [[:item/ingested-at :desc]]
                               :limit 1}))]
      {:sub/subtitle email-reply-to})))

(defresolver feed-sub-subtitle [{:keys [sub/feed]}]
  #::pco{:input [{:sub/feed [:feed/url]}]}
  {:sub/subtitle (:feed/url feed)})

(defresolver latest-email-item [{:biff/keys [query]} {:sub/keys [record-type id]}]
  {::pco/input [:sub/id :sub/record-type]
   ::pco/output [{:sub/latest-item [:item/id]}]}
  (when (= record-type :sub.record-type/email)
    (when-some [item (first (query {:select :item/id
                                    :from :item
                                    :where [:= :item/email-sub-id id]
                                    :order-by [[:item/ingested-at :desc]]
                                    :limit 1}))]
      {:sub/latest-item item})))

(defresolver sub-id->xt-id [{:keys [sub/id]}]
  {:xt/id id})

(defn- record-type->source-key [record-type]
  (case record-type
    :sub.record-type/email :item/email-sub-id
    :sub.record-type/feed :item/feed-id))

(defn- sub-source-id
  "For feed subs, the source-id is the feed-id.
   For email subs, the source-id is the sub-id."
  [{:sub/keys [record-type id feed-id]}]
  (case record-type
    :sub.record-type/feed feed-id
    :sub.record-type/email id))

(defn- row-source-id
  "Extract the source-id from a query result row."
  [row]
  (or (:item/email-sub-id row) (:item/feed-id row)))

(defresolver total [{:biff/keys [query]} inputs]
  {::pco/input [:sub/id :sub/record-type (? :sub/feed-id)]
   ::pco/output [:sub/total]
   ::pco/batch? true}
  (let [source->sub-id (into {} (map (juxt sub-source-id :sub/id)) inputs)
        results (->> (into []
                           cat
                           (for [[record-type subs*] (group-by :sub/record-type inputs)
                                 :let [source-key (record-type->source-key record-type)
                                       source-ids (mapv sub-source-id subs*)]]
                             (query {:select [source-key
                                              [:%count.* :sub/total]]
                                     :from :item
                                     :where [:in source-key source-ids]
                                     :group-by source-key})))
                     (mapv (fn [row]
                             {:sub/id (source->sub-id (row-source-id row))
                              :sub/total (:sub/total row)})))]
    (lib.core/restore-order inputs
                            :sub/id
                            results
                            (fn [{:keys [sub/id]}]
                              {:sub/id id
                               :sub/total 0}))))

(defresolver items-read [{:biff/keys [query]} inputs]
  {::pco/input [:sub/id :sub/user-id :sub/record-type (? :sub/feed-id)]
   ::pco/output [:sub/items-read]
   ::pco/batch? true}
  (let [source->sub-id (into {} (map (juxt sub-source-id :sub/id)) inputs)
        results (->> (into []
                           cat
                           (for [[user-id subs-by-user] (group-by :sub/user-id inputs)
                                 [record-type subs*] (group-by :sub/record-type subs-by-user)
                                 :let [source-key (record-type->source-key record-type)
                                       source-ids (mapv sub-source-id subs*)]]
                             (query {:select [source-key
                                              [[:count :user-item/id] :sub/items-read]]
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
                                     :group-by [source-key]})))
                     (mapv (fn [row]
                             {:sub/id (source->sub-id (row-source-id row))
                              :sub/items-read (:sub/items-read row)})))]
    (lib.core/restore-order inputs
                            :sub/id
                            results
                            (fn [{:keys [sub/id]}]
                              {:sub/id id
                               :sub/items-read 0}))))

(defresolver items-unread [{:sub/keys [total items-read]}]
  {:sub/items-unread (- total items-read)
   ;; for backwards compat
   :sub/unread (- total items-read)})

(defresolver published-at [{:biff/keys [query]} inputs]
  {::pco/input [:sub/id :sub/record-type :sub/created-at (? :sub/feed-id)]
   ::pco/output [:sub/published-at]
   ::pco/batch? true}
  (let [source->sub-id (into {} (map (juxt sub-source-id :sub/id)) inputs)
        results (->> (into []
                           cat
                           (for [[record-type subs*] (group-by :sub/record-type inputs)
                                 :let [source-key (record-type->source-key record-type)
                                       source-ids (mapv sub-source-id subs*)]]
                             (query {:select [source-key
                                              [[:max [:coalesce :item/published-at :item/ingested-at]]
                                               :item/published-at]]
                                     :from :item
                                     :where [:in source-key source-ids]
                                     :group-by source-key})))
                     (mapv (fn [row]
                             {:sub/id (source->sub-id (row-source-id row))
                              :sub/published-at (:item/published-at row)})))]
    (lib.core/restore-order inputs
                            :sub/id
                            results
                            (fn [{:sub/keys [id created-at]}]
                              {:sub/id id
                               :sub/published-at created-at}))))

(defresolver items [{:biff/keys [query]} inputs]
  {::pco/input [:sub/id :sub/record-type (? :sub/feed-id)]
   ::pco/output [{:sub/items [:item/id]}]
   ::pco/batch? true}
  (let [source->sub-id (into {} (map (juxt sub-source-id :sub/id)) inputs)
        results (->> (into []
                           cat
                           (for [[record-type subs*] (group-by :sub/record-type inputs)
                                 :let [source-key (record-type->source-key record-type)
                                       source-ids (mapv sub-source-id subs*)]]
                             (query {:select [source-key
                                              :item/id]
                                     :from :item
                                     :where [:in source-key source-ids]})))
                     (group-by (comp source->sub-id row-source-id))
                     (mapv (fn [[sub-id items]]
                             {:sub/id sub-id
                              :sub/items (mapv #(select-keys % [:item/id]) items)})))]
    (lib.core/restore-order inputs
                            :sub/id
                            results
                            (fn [{:keys [sub/id]}]
                              {:sub/id id
                               :sub/items []}))))

(defresolver latest-item [{:biff/keys [query]} inputs]
  {::pco/input [:sub/id :sub/record-type :sub/published-at (? :sub/feed-id)]
   ::pco/output [{:sub/latest-item [:item/id]}]
   ::pco/batch? true}
  (let [source->sub-id (into {} (map (juxt sub-source-id :sub/id)) inputs)
        results (->> (into []
                           cat
                           (for [[record-type subs*] (group-by :sub/record-type inputs)
                                 :let [source-key (record-type->source-key record-type)]]
                             (query {:select [source-key
                                              :item/id]
                                     :from :item
                                     :where [:in
                                             [:composite source-key [:coalesce
                                                                     :item/published-at
                                                                     :item/ingested-at]]
                                             (for [sub subs*]
                                               [:composite (sub-source-id sub) (:sub/published-at sub)])]})))
                     (mapv (fn [row]
                             {:sub/id (source->sub-id (row-source-id row))
                              :sub/latest-item {:item/id (:item/id row)}})))]
    (lib.core/restore-order inputs
                            :sub/id
                            results)))

(defresolver from-params [{:biff/keys [query] :keys [session path-params params]} _]
  {::pco/output [{:params/sub [:sub/id :sub/user-id]}]}
  (let [sub-id (or (:sub/id params)
                   (lib.serialize/url->uuid (:sub-id path-params)))
        [sub] (when (some? sub-id)
                (query {:select [:sub/id :sub/user-id]
                        :from :sub
                        :where [:= :sub/id sub-id]}))]
    (when (and sub (= (:uid session) (:sub/user-id sub)))
      {:params/sub sub})))

;; TODO turn from-params into a batch resolver and delete this
(defresolver params-checked [{:biff/keys [query] :keys [session params]} _]
  #::pco{:output [{:params.checked/subscriptions [:sub/id]}]}
  (let [sub-ids (mapv #(some-> % name parse-uuid) (keys (:subs params)))
        subs* (when (not-empty sub-ids)
                (query {:select [:sub/id :sub/user-id]
                        :from :sub
                        :where [:in :sub/id sub-ids]}))]

    (when (and (= (count sub-ids) (count subs*))
               (every? #(= (:uid session) (:sub/user-id %)) subs*))
      {:params.checked/subscriptions
       (mapv #(select-keys % [:sub/id]) subs*)})))

(defresolver unread-items [{:biff/keys [query]} subscriptions]
  #::pco{:input [:sub/user-id
                 {:sub/items [:item/id]}]
         :output [{:sub/unread-items [:item/id]}]
         :batch? true}
  (let [all-item-ids (into [] (comp (mapcat :sub/items) (map :item/id)) subscriptions)
        results (when (not-empty all-item-ids)
                  (query {:select [:user-item/user-id :user-item/item-id]
                          :from :user-item
                          :where [:and
                                  [:in :user-item/item-id all-item-ids]
                                  [:is-not [:coalesce
                                            :user-item/viewed-at
                                            :user-item/skipped-at
                                            :user-item/favorited-at
                                            :user-item/disliked-at
                                            :user-item/reported-at]
                                   nil]]}))
        user->read-items (update-vals (group-by :user-item/user-id (or results []))
                                      (fn [results]
                                        (into #{} (map :user-item/item-id) results)))]
    (mapv (fn [{:sub/keys [user-id items]}]
            (let [read-items (get user->read-items user-id #{})
                  unread-items (filterv (complement (comp read-items :item/id)) items)]
              {:sub/unread-items unread-items}))
          subscriptions)))

(defresolver mv [{:biff/keys [query]} subs*]
  {::pco/input  [:sub/id]
   ::pco/output [{:sub/mv [:mv-sub/id]}]
   ::pco/batch?  true}
  (let [sub-ids (mapv :sub/id subs*)
        results (mapv (fn [{:mv-sub/keys [id sub-id]}]
                        {:sub/id sub-id
                         :sub/mv {:mv-sub/id id}})
                      (query {:select [:mv-sub/id :mv-sub/sub-id]
                              :from :mv-sub
                              :where [:in :mv-sub/sub-id sub-ids]}))]
    (lib.core/restore-order subs* :sub/id results)))

(def module {:resolvers [user-subs
                         sub-id->xt-id
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
                         total
                         items-read
                         mv]})
