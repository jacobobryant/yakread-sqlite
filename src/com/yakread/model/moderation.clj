(ns com.yakread.model.moderation
  (:require
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
   [com.yakread.util.biff-staging :as biffs]))

(defresolver next-batch [{:keys [biff/conn* yakread.model/all-liked-items]} _]
  {::pco/output [{:admin.moderation/next-batch [:xt/id :item/n-likes]}
                 :admin.moderation/remaining
                 :admin.moderation/approved
                 :admin.moderation/blocked
                 :admin.moderation/ingest-failed]}
  (let [direct-items (biffs/q conn*
                              {:select [:item/id :item/url :item/record-type :item/direct-candidate-status]
                               :from :item
                               :where [:= :item/record-type [:lift :item.record-type/direct]]})
        url->direct-item (into {} (map (juxt :item/url identity)) direct-items)
        item->url (into {}
                        (map (juxt :xt/id :item/url))
                        (biffs/q conn*
                                 {:select [:item/id :item/url]
                                  :from :item
                                  :where [:in :item/id (mapv :item/id all-liked-items)]}))
        direct-item-id->likes (->> all-liked-items
                                   (mapv (fn [{:keys [item/id item/n-likes]}]
                                           (when-some [id (-> id item->url url->direct-item :xt/id)]
                                             {id n-likes})))
                                   (apply merge-with +))
        liked-direct-items (->> direct-items
                                (into []
                                      (comp (map #(assoc % :item/n-likes (direct-item-id->likes (:xt/id %))))
                                            (filter :item/n-likes)
                                            ;; remove the ones that have already been approved or
                                            ;; blocked.
                                            (remove :item/direct-candidate-status)))
                                (sort-by :item/n-likes >)
                                vec)
        statuses (into {}
                       (map (juxt :item/direct-candidate-status :count))
                       (biffs/q conn*
                                {:select [:item/direct-candidate-status
                                          [[:count :item/id] :count]]
                                 :from :item
                                 :where [:is-not :item/direct-candidate-status nil]
                                 :group-by [:item/direct-candidate-status]}))]
    {:admin.moderation/remaining (count liked-direct-items)
     :admin.moderation/approved (get statuses :item.direct-candidate-status/approved 0)
     :admin.moderation/blocked (get statuses :item.direct-candidate-status/blocked 0)
     :admin.moderation/ingest-failed (get statuses :item.direct-candidate-status/ingest-failed 0)
     :admin.moderation/next-batch (vec (take 50 liked-direct-items))}))

(def module {:resolvers [next-batch]})
