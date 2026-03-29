(ns com.yakread.lib.admin
  (:require [com.yakread.lib.ui :as ui]
            [com.yakread.lib.route :refer [href]]))

(def pages
  [{:id :dashboard :route 'com.yakread.app.admin.dashboard/page-route :label "Dashboard"}
   {:id :advertise :route 'com.yakread.app.admin.advertise/page-route :label "Ads"}
   {:id :screen-discover :route 'com.yakread.app.admin.discover/page-route :label "Screen discover"}
   {:id :email-test :route 'com.yakread.app.admin.email-test/page-route :label "Email test"}
   {:id :rss-test :route 'com.yakread.app.admin.rss-test/page-route :label "RSS test"}
   {:id :digest-trigger :route 'com.yakread.app.admin.digest-trigger/page-route :label "Digest trigger"}
   {:id :impersonate :route 'com.yakread.app.admin.impersonate/page-route :label "Impersonate"}
   {:id :monitor :route 'com.yakread.app.admin.monitor/page-route :target "_blank" :label "Monitor"}])

(defn navbar [active]
  [:.flex.gap-4.mb-6.max-sm:mx-4
   (for [{:keys [label id route target]} pages]
     (ui/pill {:ui/label label
               :href (href route)
               :target target
               :data-active (str (= active id))}))])

