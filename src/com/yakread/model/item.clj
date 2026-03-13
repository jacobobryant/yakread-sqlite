(ns com.yakread.model.item
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.s3 :as lib.s3]
   [com.yakread.lib.serialize :as lib.serialize]
   [com.yakread.lib.user-item :as lib.user-item]
   [com.yakread.routes :as routes]
   [rum.core :as rum]
   [tick.core :as tick])
  (:import
   (com.vdurmont.emoji EmojiParser)
   (org.jsoup Jsoup)))

(defresolver user-favorites [{:biff/keys [query now] :keys [params]} {:keys [user/id]}]
  #::pco{:output [{:user/favorites [:item/id
                                    {:item/user-item [:user-item/id]}]}
                  :user/more-favorites]}
  (let [{:keys [before]} (get-in params [:pathom-params :user/bookmarks])
        before (if (inst? before)
                 before
                 now)
        page-size 200
        results (into []
                      (map (fn [{:keys [user-item/id user-item/item-id]}]
                             {:item/id item-id
                              :item/user-item {:user-item/id id}}))
                      (query {:select [:user-item/id :user-item/item-id]
                              :from :user-item
                              :where [:and
                                      [:= :user-item/user-id id]
                                      [:< :user-item/favorited-at before]]
                              :order-by [[:user-item/favorited-at :desc]]
                              :limit page-size}))]

    {:user/favorites results
     :user/more-favorites (= page-size (count results))}))

(defresolver user-bookmarks [{:biff/keys [query now] :keys [params]} {:keys [user/id]}]
  #::pco{:output [{:user/bookmarks [:item/id
                                    {:item/user-item [:user-item/id]}]}
                  :user/more-bookmarks]}
  (let [{:keys [before]} (get-in params [:pathom-params :user/bookmarks])
        before (if (inst? before)
                 before
                 now)
        page-size 200
        results (into []
                      (map (fn [{:keys [user-item/id user-item/item-id]}]
                             {:item/id item-id
                              :item/user-item {:user-item/id id}}))
                      (query {:select [:user-item/id :user-item/item-id]
                              :from :user-item
                              :where [:and
                                      [:= :user-item/user-id id]
                                      [:< :user-item/bookmarked-at before]]
                              :order-by [[:user-item/bookmarked-at :desc]]
                              :limit page-size}))]
    {:user/bookmarks results
     :user/more-bookmarks (= page-size (count results))}))

(defresolver unread-bookmarks [{:user/keys [bookmarks]}]
  #::pco{:input [{:user/bookmarks [:item/id
                                   :item/unread]}]
         :output [{:user/unread-bookmarks [:item/id]}]}
  {:user/unread-bookmarks (filterv :item/unread bookmarks)})

(defresolver n-skipped [{:biff/keys [query] :keys [session]} items]
  #::pco{:input [:item/id]
         :output [:item/n-skipped]
         :batch? true}
  (let [results (->> (query {:select [:skip/item-id
                                      [[:count :skip/id] :n-skipped]]
                             :from :skip
                             :join [:reclist [:= :skip/reclist-id :reclist/id]]
                             :where [:and
                                     [:= :reclist/user-id (:uid session)]
                                     [:in :skip/item-id (mapv :item/id items)]]
                             :group-by :skip/item-id})
                     (mapv #(set/rename-keys % {:skip/item-id :item/id
                                                :n-skipped :item/n-skipped})))]
    (lib.core/restore-order items
                            :item/id
                            results
                            (fn [{:keys [item/id]}]
                              {:item/id id
                               :item/n-skipped 0}))))

(defresolver ad-n-skipped [{:biff/keys [query] :keys [session]} ads]
  #::pco{:input [:ad/id]
         :output [:item/n-skipped]
         :batch? true}
  (let [results (->> (query {:select [:skip/ad-id
                                      [[:count :skip/id] :n-skipped]]
                             :from :skip
                             :join [:reclist [:= :skip/reclist-id :reclist/id]]
                             :where [:and
                                     [:= :reclist/user-id (:uid session)]
                                     [:in :skip/ad-id (mapv :ad/id ads)]]
                             :group-by :skip/ad-id})
                     (mapv #(set/rename-keys % {:skip/ad-id :ad/id
                                                :n-skipped :item/n-skipped})))]
    (lib.core/restore-order ads
                            :ad/id
                            results
                            (fn [{:keys [ad/id]}]
                              {:ad/id id
                               :item/n-skipped 0}))))

(defresolver user-item [{:biff/keys [query] :keys [session]} items]
  #::pco{:input [:item/id]
         :output [{:item/user-item [:user-item/id :user-item/favorited-at]}]
         :batch? true}
  (let [item-ids (into #{} (map :item/id items))
        results (into []
                      (keep (fn [{:keys [user-item/id user-item/item-id user-item/favorited-at]}]
                             (when (item-ids item-id)
                               {:item/id item-id :item/user-item {:user-item/id id
                                                                   :user-item/favorited-at favorited-at}})))
                      (query {:select [:user-item/id :user-item/item-id :user-item/favorited-at]
                              :from :user-item
                              :where [:and
                                      [:= :user-item/user-id (:uid session)]
                                      [:in :user-item/item-id (vec item-ids)]]}))]
    (lib.core/restore-order items :item/id results
                            (fn [{:keys [item/id]}]
                              {:item/id id :item/user-item {}}))))

(defresolver image-from-feed [{:biff/keys [query]} items]
  #::pco{:input [(? :item/feed-url)
                 {(? :item/feed) [:feed/image-url]}]
         :output [:item/image-url]
         :batch? true}
  (let [feed-urls (keep :item/feed-url items)
        url->image (into {}
                         (map (juxt :feed/url :feed/image-url))
                         (when (not-empty feed-urls)
                           (query {:select [:feed/url :feed/image-url]
                                   :from :feed
                                   :where [:in :feed/url feed-urls]})))]
    (mapv (fn [{:keys [item/feed item/feed-url]}]
            (if-some [image (or (:feed/image-url feed)
                                (url->image feed-url))]
              {:item/image-url image}
              {}))
          items)))

(defresolver unread [{:keys [item/user-item]}]
  #::pco{:input [{(? :item/user-item) [(? :user-item/viewed-at)
                                       (? :user-item/skipped-at)
                                       (? :user-item/favorited-at)
                                       (? :user-item/disliked-at)
                                       (? :user-item/reported-at)]}]}
  {:item/unread (not (lib.user-item/read? user-item))})

(defresolver history-items [{:biff/keys [query] :as ctx}
                            {:keys [session/user
                                    params/paginate-after]}]
  #::pco{:input [{:session/user [:user/id]}
                 (? :params/paginate-after)]
         :output [{:user/history-items [:item/id
                                        {:item/user-item [:user-item/id]}]}]}
  (let [{:keys [batch-size] :or {batch-size 100}} (pco/params ctx)]
    {:user/history-items
     (->> (query {:select [:user-item/id
                           :user-item/item-id
                           :user-item/viewed-at
                           :user-item/favorited-at
                           :user-item/disliked-at
                           :user-item/reported-at]
                  :from :user-item
                  :where [:= :user-item/user-id (:user/id user)]})
          (keep (fn [usit]
                  (when-some [t (some->> [:user-item/viewed-at
                                          :user-item/favorited-at
                                          :user-item/disliked-at
                                          :user-item/reported-at]
                                         (keep usit)
                                         not-empty
                                         (apply tick/max))]
                    (assoc usit :t t))))
          (sort-by :t #(compare %2 %1))
          (drop-while (fn [{:keys [user-item/item-id]}]
                        (and paginate-after
                             (not= item-id paginate-after))))
          (remove (comp #{paginate-after} :user-item/item-id))
          (take batch-size)
          (mapv (fn [{:keys [user-item/id user-item/item-id]}]
                  {:item/id item-id
                   :item/user-item {:user-item/id id}})))}))

(defresolver current-item [{:biff/keys [query]} {:keys [user/id]}]
  #::pco{:input [:user/id]
         :output [{:user/current-item [:item/id :item/rec-type]}]}
  (when-some [{:user-item/keys [item-id]}
              (first (query {:select [:user-item/item-id]
                             :from :user-item
                             :where [:and
                                     [:= :user-item/user-id id]
                                     [:is-not :user-item/viewed-at nil]]
                             :order-by [[:user-item/viewed-at :desc]]
                             :limit 1}))]
    {:user/current-item
     {:item/id item-id
      :item/rec-type :item.rec-type/current}}))

(defresolver source [{:keys [item/email-sub-id item/feed-id]}]
  {::pco/input [(? :item/email-sub-id)
                (? :item/feed-id)]
   ::pco/output [{:item/source [:xt/id]}]}
  (when-some [source-id (or email-sub-id feed-id)]
    {:item/source {:xt/id source-id}}))

(defresolver email-sub [{:keys [item/email-sub-id]}]
  {::pco/input [:item/email-sub-id]
   ::pco/output [{:item/sub [:sub/id]}]}
  {:item/sub {:sub/id email-sub-id}})

(defresolver feed-sub [{:biff/keys [query] :keys [session]} inputs]
  {::pco/input [:item/feed-id]
   ::pco/output [{:item/sub [:sub/id]}]
   ::pco/batch? true}
  (let [feed-ids (into [] (map :item/feed-id) inputs)
        feed->sub (into {}
                        (map (juxt :sub/feed-id :sub/id))
                        (when (not-empty feed-ids)
                          (query {:select [:sub/id :sub/feed-id]
                                  :from :sub
                                  :where [:and
                                          [:= :sub/user-id (:uid session)]
                                          [:in :sub/feed-id feed-ids]]})))]
    (mapv (fn [{:keys [item/feed-id]}]
            {:item/sub {:sub/id (get feed->sub feed-id)}})
          inputs)))

(defresolver from-params-unsafe [{:keys [path-params params]} _]
  #::pco{:output [{:params/item-unsafe [:item/id]}]}
  (when-some [item-id (or (some-> (:item-id path-params) lib.serialize/url->uuid)
                          (:item/id params))]
    {:params/item-unsafe {:item/id item-id}}))

(defresolver from-params [{:keys [session yakread.model/item-candidate-ids] :biff/keys [query]}
                          {:keys [params/item-unsafe]}]
  #::pco{:input [{:params/item-unsafe [:item/id]}]
         :output [{:params/item [:item/id]}]}
  (let [item-id (:item/id item-unsafe)]
    (when (or (contains? item-candidate-ids item-id)
              (seq (query {:select [:user-item/id]
                           :from :user-item
                           :where [:and
                                   [:= :user-item/user-id (:uid session)]
                                   [:= :user-item/item-id item-id]]
                           :limit 1}))
              (seq (query {:select [:sub/id]
                           :from :sub
                           :where [:and
                                   [:= :sub/user-id (:uid session)]
                                   [:or
                                    [:in :sub/id {:select [:item/email-sub-id]
                                                  :from :item
                                                  :where [:= :item/id item-id]}]
                                    [:in :sub/feed-id {:select [:item/feed-id]
                                                       :from :item
                                                       :where [:= :item/id item-id]}]]]
                           :limit 1})))
      {:params/item {:item/id item-id}})))

(defresolver item-id [{:keys [xt/id item/ingested-at]}]
  {:item/id id})

(defresolver xt-id [{:keys [item/id]}]
  {:xt/id id})

(defresolver content [ctx {:item/keys [content-key url]}]
  #::pco{:input [(? :item/content-key)
                 (? :item/url)]
         :output [:item/content]}
  (cond
    content-key
    {:item/content (:body
                    (lib.s3/request ctx {:config-ns 'yakread.s3.content
                                         :method "GET"
                                         :key (str content-key)}))}

    url
    {:item/content (rum/render-static-markup [:a {:href url} url])}))

(defresolver clean-html [{:item/keys [content]}]
  {:item/clean-html
   (let [doc (Jsoup/parse content)]
     (-> doc
         (.select "a[href]")
         (.attr "target" "_blank"))
     (doseq [img (.select doc "img[src^=http://]")]
       (.attr img "src" (str/replace (.attr img "src")
                                     #"^http://"
                                     "https://")))
     (.outerHtml doc))})

(defresolver doc-type [{:keys [item/feed-id
                               item/email-sub-id]}]
  #::pco{:input [(? :item/feed-id)
                 (? :item/email-sub-id)]
         :output [:item/doc-type]}
  (cond
    feed-id {:item/doc-type :item/feed}
    email-sub-id {:item/doc-type :item/email}
    :else {:item/doc-type :item/direct}))

(defresolver digest-url [{:biff/keys [base-url href-safe]} {:item/keys [id url rec-type]}]
  {::pco/input [:item/id
                (? :item/url)
                (? :item/rec-type)]}
  {:item/digest-url
   (fn [{user-id :user/id}]
     (str base-url
          (href-safe routes/click-item
                     (merge {:action   :action/click-item
                             :user/id  user-id
                             :item/id  id}
                            (when (and url (= rec-type :item.rec-type/discover))
                              {:redirect true
                               :item/url url})))))})

(defresolver clean-title [{:keys [item/title]}]
  {:item/clean-title (str/trim (EmojiParser/removeAllEmojis title))})

(defresolver digest-sends [{:biff/keys [query] :keys [session]} items]
  {::pco/input [:item/id]
   ::pco/output [:item/n-digest-sends]
   ::pco/batch? true}
  (let [results (->> (query {:union
                             [{:select [:digest/ad-id
                                        [[:count :digest/id] :n-sends]]
                               :from :digest
                               :where [:and
                                       [:= :digest/user-id (:uid session)]
                                       [:in :digest/ad-id (mapv :item/id items)]]
                               :group-by :digest/ad-id}
                              {:select [:digest-item/item-id
                                        [[:count :digest-item/digest-id] :n-sends]]
                               :from :digest-item
                               :join [:digest [:= :digest-item/digest-id :digest/id]]
                               :where [:and
                                       [:= :digest/user-id (:uid session)]
                                       ;; TODO seems like it might be faster without this assuming #
                                       ;; digests is << # candidate items
                                       [:in :digest-item/item-id (mapv :item/id items)]]
                               :group-by :digest-item/item-id}]})
                     (mapv #(set/rename-keys % {:digest/ad-id :item/id
                                                :n-sends :item/n-digest-sends})))]
    (lib.core/restore-order items
                            :item/id
                            results
                            (fn [{:keys [item/id]}]
                              {:item/id id
                               :item/n-digest-sends 0}))))

(def module
  {:resolvers [user-favorites
               user-bookmarks
               clean-html
               content
               doc-type
               from-params
               from-params-unsafe
               image-from-feed
               item-id
               email-sub
               feed-sub
               unread
               user-item
               xt-id
               n-skipped
               ad-n-skipped
               unread-bookmarks
               history-items
               current-item
               source
               digest-url
               clean-title
               digest-sends]})
