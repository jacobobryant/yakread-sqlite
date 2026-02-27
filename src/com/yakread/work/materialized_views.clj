(ns com.yakread.work.materialized-views
  (:require
   [com.yakread.util.biff-staging :as biffs]
   [com.wsscode.pathom3.connect.operation :as pco :refer [?]]
   [com.yakread.lib.fx :as fx]
   [tick.core :as tick]))

(fx/defmachine update-views
  :start
  (fn [{:keys [biff/job]}]
    {:biff.fx/next (:view job)})

  :sub-affinity
  (fn [{:keys [biff/job]}]
    {:biff.fx/pathom {:entity {:sub/id (:sub/id job)}
                      :query [:sub/id
                              :sub/affinity-low*
                              :sub/affinity-high*
                              {(? :sub/mv) [(? :mv-sub/affinity-low)
                                            (? :mv-sub/affinity-high)]}]}
     :biff.fx/next :sub-affinity-update})

  :sub-affinity-update
  (fn [{:keys [biff.fx/pathom]}]
    (let [{:sub/keys [id affinity-low* affinity-high* mv]} pathom]
      (when (not= [affinity-low* affinity-high*]
                  [(:mv-sub/affinity-low mv) (:mv-sub/affinity-high mv)])
        {:biff.fx/tx [[:biff/upsert :mv-sub [:mv-sub/sub-id]
                       {:xt/id (biffs/gen-uuid id)
                        :mv-sub/sub-id id
                        :mv-sub/affinity-low  affinity-low*
                        :mv-sub/affinity-high affinity-high*}]]})))

  :current-item
  (fn [{:biff/keys [query job]}]
    (let [{:user-item/keys [user-id item-id viewed-at]} job

          {current-item :user-item/item-id
           current-item-viewed-at :user-item/viewed-at}
          (first
           (query
                    {:select [:user-item/item-id :user-item/viewed-at]
                     :from :mv-user
                     :join [:user-item [:= :user-item/item-id :mv-user/current-item-id]]
                     :where [:= :mv-user/user-id user-id]}))

          new-current-item (cond
                             (and viewed-at
                                  (or (not current-item-viewed-at)
                                      (tick/<= current-item-viewed-at viewed-at)))
                             item-id

                             (and (not viewed-at)
                                  (= item-id current-item))
                             ::remove)]
      (when new-current-item
        {:biff.fx/tx [{:biff/upsert :mv-user [:mv-user/user-id]
                       {:xt/id (biffs/gen-uuid user-id)
                        :mv-user/user-id user-id
                        :mv-user/current-item-id (when (not= new-current-item ::remove)
                                                new-current-item)}}]}))))

(fx/defmachine on-tx
  :start
  (fn [{:keys [biff/query record]}]
    {:biff.fx/queue
     {:jobs
      (for [job
            (distinct
             (cond
               (:user-item/user-id record)
               (concat
                (when-some [sub-id (-> (query
                                        {:select [[[:coalesce :item/email-sub-id :sub/id]
                                                   :sub-id]]
                                         :from :item
                                         :where [:= :item/id (:user-item/item-id record)]
                                         :left-join [:sub [:and
                                                           [:is-not :item/feed-id nil]
                                                           [:= :sub/feed-id :item/feed-id]
                                                           [:= :sub/user-id (:user-item/user-id record)]]]
                                         :limit 1})
                                       first
                                       :sub-id)]
                  [{:view :sub-affinity :sub/id sub-id}])
                [(merge record {:view :current-item})])

               (:skip/item-id record)
               (when-some [sub-id (-> (query
                                       {:select [[[:coalesce :item/email-sub-id :sub/id]
                                                  :sub-id]]
                                        :from :reclist
                                        :where [:= :reclist/id (:skip/reclist-id record)]
                                        :join [:item [:= :item/id (:skip/item-id record)]]
                                        :left-join [:sub [:and
                                                          [:is-not :item/feed-id nil]
                                                          [:= :sub/feed-id :item/feed-id]
                                                          [:= :sub/user-id :reclist/user-id]]]
                                        :limit 1})
                                      first
                                      :sub-id)]
                 [{:view :sub-affinity :sub/id sub-id}])))]
        [:work.materialized-views/update job])}}))

(def module {:on-tx (fn [ctx record] (on-tx (assoc ctx :record record)))
             :queues [{:id        :work.materialized-views/update
                       :consumer  #'update-views
                       :n-threads 2}]})
