(ns com.yakread.work.materialized-views
  (:require
   [com.yakread.lib.sqlite :as lib.sqlite]
   [clojure.data.generators :as gen]
   [com.biffweb.experimental :as biffx]
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
        {:biff.fx/tx [[:put-docs :mv-sub
                       {:mv-sub/id (biffx/prefix-uuid id (gen/uuid))
                        :mv-sub/sub-id id
                        :mv-sub/affinity-low  affinity-low*
                        :mv-sub/affinity-high affinity-high*}]]})))

  :current-item
  (fn [{:biff/keys [conn malli-opts job]}]
    (let [{:user-item/keys [user-id item-id viewed-at]} job

          {current-item :item-id
           current-item-viewed-at :viewed-at}
          (first
           (lib.sqlite/q conn malli-opts nil
                    {:select [:user_item.item_id :user_item.viewed_at]
                     :from :mv-user
                     :join [:user-item [:= :user_item.item_id :mv_user.current_item_id]]
                     :where [:= :mv_user.user_id user-id]}))

          new-current-item (cond
                             (and viewed-at
                                  (or (not current-item-viewed-at)
                                      (tick/<= current-item-viewed-at viewed-at)))
                             item-id

                             (and (not viewed-at)
                                  (= item-id current-item))
                             ::remove)]
      (when new-current-item
        {:biff.fx/tx [[:put-docs :mv-user
                       {:mv-user/id (biffx/prefix-uuid user-id (gen/uuid))
                        :mv-user/user-id user-id
                        :mv-user/current-item-id (when (not= new-current-item ::remove)
                                                   new-current-item)}]]}))))

(fx/defmachine on-tx
  :start
  (fn [{:keys [biff/conn biff/malli-opts record]}]
    {:biff.fx/queue
     {:jobs
      (for [job
            (distinct
             (cond
               (:user-item/user-id record)
               (concat
                (when-some [sub-id (-> (lib.sqlite/q
                                        conn malli-opts nil
                                        {:select [[[:coalesce :item.email_sub_id :sub.id]
                                                   :sub_id]]
                                         :from :item
                                         :where [:= :item.id (:user-item/item-id record)]
                                         :left-join [:sub [:and
                                                           [:is-not :item.feed_id nil]
                                                           [:= :sub.feed_id :item.feed_id]
                                                           [:= :sub.user_id (:user-item/user-id record)]]]
                                         :limit 1})
                                       first
                                       :sub-id)]
                  [{:view :sub-affinity :sub/id sub-id}])
                [(merge record {:view :current-item})])

               (:skip/item-id record)
               (when-some [sub-id (-> (lib.sqlite/q
                                       conn malli-opts nil
                                       {:select [[[:coalesce :item.email_sub_id :sub.id]
                                                  :sub_id]]
                                        :from :reclist
                                        :where [:= :reclist.id (:skip/reclist-id record)]
                                        :join [:item [:= :item.id (:skip/item-id record)]]
                                        :left-join [:sub [:and
                                                          [:is-not :item.feed_id nil]
                                                          [:= :sub.feed_id :item.feed_id]
                                                          [:= :sub.user_id :reclist.user_id]]]
                                        :limit 1})
                                      first
                                      :sub-id)]
                 [{:view :sub-affinity :sub/id sub-id}])))]
        [:work.materialized-views/update job])}}))

(def module {:on-tx (fn [ctx record] (on-tx (assoc ctx :record record)))
             :queues [{:id        :work.materialized-views/update
                       :consumer  #'update-views
                       :n-threads 2}]})
