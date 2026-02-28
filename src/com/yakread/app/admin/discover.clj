(ns com.yakread.app.admin.discover
  (:require
   [com.biffweb :as biff]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]))

(declare page-route)

(fx/defroute save-moderation
  :post
  (fn [{{:keys [block all-items]} :params}]
    (let [block-ids (into #{}
                          (map parse-uuid)
                          (if (string? block)
                            [block]
                            block))]
      {:biff.fx/sqlite (for [id all-items]
                         {:update :item
                          :set {:item/direct-candidate-status
                                [:lift (if (block-ids id)
                                         :item.direct-candidate-status/blocked
                                         :item.direct-candidate-status/approved)]}
                          :where [:= :item/id id]})
       :status 303
       :headers {"location" (href page-route)}})))

(fx/defroute-pathom page-content-route "/admin/discover/content"
  [:admin.moderation/remaining
   :admin.moderation/approved
   :admin.moderation/blocked
   :admin.moderation/ingest-failed
   {:admin.moderation/next-batch
    [:item/id
     :item/n-likes
     :item/url
     :item/ui-read-more-card]}]

  :get
  (fn [ctx {:admin.moderation/keys [next-batch remaining approved blocked ingest-failed]}]
    [:<>
     [:.max-sm:mx-4 remaining " items left. " approved " approved. " blocked " blocked. "
      ingest-failed " ingest failed."]
     [:.h-6]
     [:.max-sm:mx-4
      (biff/form
       {:action (href save-moderation)
        :hidden (lib.route/nippy-params {:all-items (mapv :item/id next-batch)})}
       [:div.grid.xl:grid-cols-2.gap-6
        (for [{:item/keys [id ui-read-more-card url]
               :keys [item/n-likes]} next-batch]
          [:<>
           [:div
            [:div (str n-likes) " likes"]
            [:.h-2]
            [:div url]
            (ui-read-more-card {:show-author true
                                :new-tab true})]
           (ui/checkbox {:ui/label "block?"
                         :ui/size :large
                         :name "block"
                         :value id})])
        (ui/button {:type "submit"
                    :ui/size :large
                    :ui/type :primary
                    :class '[w-full]}
                   "Save")])]]))

(fx/defroute-pathom page-route "/admin/discover"
  [:app.shell/app-shell]

  :get
  (fn [ctx {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :screen-discover)
     (ui/lazy-load (href page-content-route)))))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            page-content-route
            save-moderation]})
