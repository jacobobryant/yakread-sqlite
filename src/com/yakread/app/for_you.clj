(ns com.yakread.app.for-you
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff]
   [com.wsscode.pathom3.connect.operation :refer [?]]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(defn- skip-tx [{:keys [biff/query t]
                 skip-items :skip-items
                 skip-ads :skip-ads
                 user-id :user/id
                 rec-id :rec/id}]
  (let [new-skips (concat (for [id skip-items] {:skip/item-id id})
                          (for [id skip-ads] {:skip/ad-id id}))]
    (when (and (seq new-skips) t)
      (let [reclist (first (query {:select [:reclist/id :reclist/clicked]
                                   :from :reclist
                                   :where [:and
                                           [:= :reclist/user-id user-id]
                                           [:= :reclist/created-at t]]}))
            existing-skips (when (:reclist/id reclist)
                             (query {:select [:skip/id :skip/item-id :skip/ad-id]
                                     :from :skip
                                     :where [:= :skip/reclist-id (:reclist/id reclist)]}))

            skip-id (fn [skip]
                      (or (:skip/item-id skip) (:skip/ad-id skip)))
            old-clicked (:reclist/clicked reclist #{})
            new-clicked (conj old-clicked rec-id)
            delete-skips (into []
                               (comp (filter (comp new-clicked skip-id))
                                     (map :skip/id))
                               existing-skips)
            existing-ids (into #{} (map skip-id) existing-skips)
            create-skips (into []
                               (remove (fn [skip]
                                         (or (new-clicked (skip-id skip))
                                             (existing-ids (skip-id skip)))))
                               new-skips)
            reclist-id (or (:reclist/id reclist)
                           (gen/uuid))]
        (when (not= old-clicked new-clicked)
          (concat
           [{:insert-into :reclist
             :values [{:reclist/id reclist-id
                       :reclist/user-id user-id
                       :reclist/created-at t
                       :reclist/clicked new-clicked}]
             :on-conflict [:reclist/user-id :reclist/created-at]
             :do-update-set {:fields [:clicked]}}]

           (when (not-empty delete-skips)
             [{:delete-from :skip
               :where [:in :skip/id delete-skips]}])

           (when (not-empty create-skips)
             [{:insert-into :skip
               :values (vec (for [skip create-skips]
                              (merge {:skip/id (gen/uuid)
                                      :skip/reclist-id reclist-id}
                                     skip)))
               :on-conflict [:skip/reclist-id :skip/item-id :skip/ad-id]
               :do-update-set {:skip/reclist-id :skip/reclist-id}}])))))))
(fx/defroute record-item-click
  :post
  (fn [{:biff/keys [query safe-params now]}]
    (let [{:keys [action t skip-items skip-ads] item-id :item/id user-id :user/id} safe-params]
      (if (not= action :action/click-item)
        (ui/on-error {:status 400})
        {:status 204
         :biff.fx/sqlite (concat
                      (skip-tx {:biff/query query
                                :user/id user-id
                                :rec/id item-id
                                :skip-items skip-items
                                :skip-ads skip-ads
                                :t t})
                      (when (empty? (query
                                     {:select 1
                                      :from :user-item
                                      :where [:and
                                              [:= :user-item/user-id user-id]
                                              [:= :user-item/item-id item-id]
                                              [:is-not :user-item/viewed-at nil]]
                                      :limit 1}))
                        [{:insert-into :user-item
                          :values [{:user-item/id (gen/uuid)
                                    :user-item/user-id user-id
                                    :user-item/item-id item-id
                                    :user-item/viewed-at now}]
                          :on-conflict [:user-item/user-id :user-item/item-id]
                          :do-update-set {:fields [:viewed-at]}}]))}))))

(fx/defroute record-ad-click
  :post
  (fn [{:biff/keys [query safe-params now]}]
    (let [{:keys [action skip-items skip-ads t ad/click-cost ad.click/source]
           ad-id :ad/id
           user-id :user/id} safe-params]
      (if (not= action :action/click-ad)
        (ui/on-error {:status 400})
        {:status 204
         :biff.fx/sqlite (concat
                      (skip-tx {:biff/query query
                                :user/id user-id
                                :rec/id ad-id
                                :skip-items skip-items
                                :skip-ads skip-ads
                                :t t})
                      (when (empty?
                             (query
                              {:select 1
                               :from :ad-click
                               :where [:and
                                       [:= :ad-click/user-id user-id]
                                       [:= :ad-click/ad-id ad-id]]
                               :limit 1}))
                        [{:insert-into :ad-click
                          :values [{:ad-click/id (gen/uuid)
                                    :ad-click/user-id user-id
                                    :ad-click/ad-id ad-id
                                    :ad-click/created-at now
                                    :ad-click/cost click-cost
                                    :ad-click/source [:lift (or source :ad-click.source/web)]}]
                          :on-conflict [:ad-click/user-id :ad-click/ad-id]
                          :do-update-set {:fields [:created-at :cost :source]}}]))}))))

(fx/defroute-pathom page-content-route "/for-you/content"
  [{(? :session/user)
    [{(? :user/current-item)
      [:item/ui-read-more-card]}
     {:user/for-you-recs
      [(? :item/id)
       (? :ad/id)
       :rec/ui-read-more-card]}]}
   {(? :session/anon)
    [{:user/discover-recs
      [:item/id
       :item/url
       :item/ui-read-more-card]}]}]

  :get
  (fn [{:keys [biff/now params]} {:keys [user/discover-recs session/user session/anon]
                                  {:user/keys [current-item for-you-recs]} :session/user}]
    [:div {:class '[flex flex-col gap-6
                    max-w-screen-sm
                    h-full]}
     (when-let [{:keys [item/ui-read-more-card]} (and (:show-continue params) current-item)]
       [:div
        (ui-read-more-card {:on-click-route `read-page-route
                            :highlight-unread false
                            :show-author true})
        [:.h-5.sm:h-4]
        [:div.text-center
         (ui/muted-link {:href (href routes/history)}
                        "View reading history")]])
     (cond
       (every? empty? [for-you-recs (:user/discover-recs anon)])
       [:<>
        [:.grow]
        [:div.text-center "There's no content to recommend yet. Try adding some "
         [:span.inline-block
          (ui/web-link {:href (href routes/subs-page)} "subscriptions")
          " or "
          (ui/web-link {:href (href routes/bookmarks-page)} "bookmarks.")]]
        [:.grow]
        [:.grow]]

       user
       (for [[i {:rec/keys [ui-read-more-card]}] (map-indexed vector for-you-recs)]
         (let [prev-recs (take i for-you-recs)]
           (ui-read-more-card {:on-click-route `read-page-route
                               :on-click-params {:skip-items (into #{} (keep :item/id) prev-recs)
                                                 :skip-ads (into #{} (keep :ad/id) prev-recs)
                                                 :t now}
                               :highlight-unread false
                               :show-author true})))

       :else
       (for [[i {:item/keys [ui-read-more-card]}] (map-indexed vector (:user/discover-recs anon))]
         (ui-read-more-card {:highlight-unread false
                             :show-author true
                             :new-tab true})))]))

(fx/defroute-pathom page-route "/for-you"
  [:app.shell/app-shell]

  :get
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {}
     [:div#content.h-full (ui/lazy-load-spaced (href page-content-route {:show-continue true}))])))

(let [record-click-url (fn [{:keys [biff/href-safe
                                    params
                                    session
                                    biff.fx/pathom]}]
                         (href-safe record-item-click
                                    {:action  :action/click-item
                                     :user/id (:uid session)
                                     :item/id (get-in pathom [:params/item :item/id])
                                     :skip-items (:skip-items params)
                                     :skip-ads (:skip-ads params)
                                     :t       (:t params)}))]
  (fx/defroute-pathom read-page-route "/item/:item-id"
    [{(? :params/item) [:item/id
                        (? :item/url)]}
     {:session/user [(? :user/use-original-links)]}]

    :get
    (fn [{:keys [params] :as ctx} {:keys [params/item session/user]}]
      (let [{:item/keys [id url]} item
            {:user/keys [use-original-links]} user]
        (cond
          (nil? id)
          {:status 303
           :headers {"Location" (href page-route)}}

          (and use-original-links url)
          (ui/redirect-on-load {:redirect-url url
                                :beacon-url (when-not (:skip-record params)
                                              (record-click-url ctx))})

          :else
          {:biff.fx/next :render
           :biff.fx/pathom [:app.shell/app-shell
                            {:params/item [:item/ui-read-content
                                           :item/id
                                           (? :item/title)]}]})))

    :render
    (fn [{:keys [params] :as ctx} {:keys [app.shell/app-shell params/item]}]
      (let [{:item/keys [ui-read-content title]} item]
        (app-shell
         {:title title}
         (when-not (:skip-record params)
           [:div {:hx-post (record-click-url ctx)
                  :hx-trigger "load"
                  :hx-swap "outerHTML"}])
         (ui-read-content {:leave-item-redirect (href page-route)
                           :unsubscribe-redirect (href page-route)})
         [:div.h-10]
         [:div#content
          [:.flex.justify-center
           {:hx-get (href page-content-route)
            :hx-trigger "load delay:2s"
            :hx-swap "outerHTML"}
           [:img.h-10 {:src ui/spinner-gif}]]])))))

(fx/defroute click-ad-route "/c/:ewt"
  :get
  (fn [{:biff/keys [safe-params href-safe]}]
    (let [{:keys [action ad/url]} safe-params]
      (if (not= action :action/click-ad)
        (ui/on-error {:status 400})
        (ui/redirect-on-load
         {:redirect-url url
          :beacon-url (href-safe record-ad-click safe-params)})))))

(fx/defroute click-item-route "/r/:ewt"
  :get
  (fn [{:biff/keys [safe-params href-safe]}]
    (let [{:keys [action item/url item/id redirect]} safe-params]
      (if (not= action :action/click-item)
        (ui/on-error {:status 400})
        (ui/redirect-on-load
         {:redirect-url (if redirect
                          url
                          (href read-page-route id {:skip-record true}))
          :beacon-url (href-safe record-item-click safe-params)})))))

(def module
  {:routes [["" {:middleware [lib.mid/wrap-signed-in]}
             read-page-route]
            page-route
            page-content-route
            record-ad-click
            record-item-click
            click-ad-route
            click-item-route]})
