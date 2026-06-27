(ns com.yakread.app.subscriptions.view
  (:require
   [clojure.data.generators :as gen]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.middle]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.routes :as routes]))

(fx/defroute-graph mark-read
  [{:session/user [:user/id]}
   {:params/item [:item/id]}]

  :post
  (fn [{:biff/keys [query now]} {:keys [session/user params/item]}]
    (merge {:status 204}
           (when (empty? (query {:select 1
                                 :from :user-item
                                 :where [:and
                                         [:= :user-item/user-id (:user/id user)]
                                         [:= :user-item/item-id (:item/id item)]
                                         [:is-not :user-item/viewed-at nil]]
                                 :limit 1}))
             {:biff.fx/sqlite [:biff.fx/sqlite
                               [{:insert-into :user-item
                                 :values [{:user-item/id (gen/uuid)
                                           :user-item/user-id (:user/id user)
                                           :user-item/item-id (:item/id item)
                                           :user-item/viewed-at now}]
                                 :on-conflict [:user-item/user-id :user-item/item-id]
                                 :do-update-set {:fields [:viewed-at]}}]]}))))

(fx/defroute-graph mark-all-read
  [{:session/user [:user/id]}
   {[:? :params/sub] [:sub/id
                      {:sub/items [:item/id
                                   :item/unread]}]}]

  :post
  (fn [{:keys [biff/now]} {:keys [session/user params/sub]}]
    (if-not sub
      {:status 303
       :headers {"Location" (href routes/subs-page)}}
      (let [values (vec (for [{:item/keys [id unread]} (:sub/items sub)
                              :when unread]
                          {:user-item/id (gen/uuid)
                           :user-item/user-id (:user/id user)
                           :user-item/item-id id
                           :user-item/skipped-at now}))]
        (merge {:status 303
                :headers {"HX-Location" (href `page-route (:sub/id sub))}}
               (when (not-empty values)
                 {:biff.fx/sqlite [:biff.fx/sqlite
                                   [{:insert-into :user-item
                                     :values values
                                     :on-conflict [:user-item/user-id :user-item/item-id]
                                     :do-update-set {:fields [:skipped-at]}}]]}))))))

(fx/defroute-graph read-content-route "/sub-item/:item-id/content"
  [{[:? :params/item] [:item/ui-read-content
                       {:item/sub [:sub/id
                                   :sub/title
                                   [:? :sub/subtitle]]}]}]

  :get
  (fn [_ {{:item/keys [ui-read-content sub]
           :as item} :params/item}]
    (when item
      [:<>
       (ui-read-content {:leave-item-redirect (href `page-route (get-in item [:item/sub :sub/id]))
                         :unsubscribe-redirect (href routes/subs-page)})
       [:div.h-10]
       (ui/page-header {:title     (:sub/title sub)
                        :subtitle  (:sub/subtitle sub)
                        :back-href (href routes/subs-page)
                        :no-margin true})
       [:.h-4]
       [:div#content (ui/lazy-load-spaced (href `page-content-route (:sub/id sub)))]])))

(let [record-click-url (fn [item]
                         (href mark-read {:item/id (:item/id item)}))]
  (fx/defroute-graph read-page-route "/sub-item/:item-id"
    [{[:? :params/item] [:item/id
                         [:? :item/url]]}
     {:session/user [[:? :user/use-original-links]]}]

    :get
    (fn [_ {:keys [params/item session/user]}]
      (let [{:item/keys [id url]} item
            {:user/keys [use-original-links]} user]
        (cond
          (nil? id)
          {:status 303
           :headers {"Location" (href routes/subs-page)}}

          (and use-original-links url)
          (ui/redirect-on-load {:redirect-url url
                                :beacon-url (record-click-url item)})

          :else
          {:biff.fx/next :render
           :biff.fx/result [:biff.fx/graph
                            [:app.shell/app-shell
                             {:params/item [:item/id
                                            :item/title
                                            {:item/sub [:sub/id
                                                        :sub/title]}]}]]})))

    :render
    (fn [_ {:keys [app.shell/app-shell params/item]}]
      (let [{:item/keys [id title sub]} item]
        (app-shell
         {:title title}
         [:div {:hx-post (record-click-url item) :hx-trigger "load" :hx-swap "outerHTML"}]
         (ui/lazy-load-spaced (href read-content-route id)))))))

(fx/defroute-graph page-content-route "/subscription/:sub-id/content"
  [{:params/sub [:sub/id
                 :sub/title
                 {:sub/items
                  [:item/ui-read-more-card
                   :item/published-at]}]}]

  :get
  (fn [_ {:keys [params/sub]}]
    (if-not sub
      {:status 303
       :headers {"Location" (href routes/subs-page)}}
      (let [{:sub/keys [id title items]} sub]
        [:<>
         [:.flex.gap-4.max-sm:px-4
          (ui/button {:ui/type :secondary
                      :ui/size :small
                      :hx-post (href mark-all-read {:sub/id id})}
                     "Mark all as read")
          (ui/button {:ui/type :secondary
                      :ui/size :small
                      :hx-post (href routes/unsubscribe! {:sub/id id})
                      :hx-confirm (ui/confirm-unsub-msg title)}
                     "Unsubscribe")]
         [:.h-6]
         [:div {:class '[flex flex-col gap-6
                         max-w-screen-sm]}
          (for [{:item/keys [ui-read-more-card]}
                (sort-by :item/published-at #(compare %2 %1) items)]
            (ui-read-more-card {:on-click-route read-page-route
                                :highlight-unread true
                                :show-author false}))]]))))

(fx/defroute-graph page-route "/subscription/:sub-id"
  [:app.shell/app-shell
   {[:? :params/sub] [:sub/id
                      :sub/title
                      [:? :sub/subtitle]]}]

  :get
  (fn [_ {:keys [app.shell/app-shell params/sub]}]
    (if-not sub
      {:status 303
       :headers {"Location" (href routes/subs-page)}}
      (let [{:sub/keys [id title subtitle]} sub]
        (app-shell
         {:title title}
         (ui/page-header {:title     title
                          :subtitle  subtitle
                          :back-href (href routes/subs-page)
                          :no-margin true})
         [:.h-4]
         [:div#content (ui/lazy-load-spaced (href page-content-route id {:sub/id id}))])))))

(def module
  {:routes [["" {:middleware [lib.middle/wrap-signed-in]}
             page-route
             page-content-route
             read-page-route
             read-content-route
             mark-read
             mark-all-read]]})
