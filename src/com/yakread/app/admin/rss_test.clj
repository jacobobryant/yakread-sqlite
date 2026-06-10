(ns com.yakread.app.admin.rss-test
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [rum.core :as rum]
   [tick.core :as tick]))

(declare page-route create-post-route delete-post-route feed-route)

(fx/defroute create-post-route "/admin/rss-test/create"
  :post
  (fn [{:keys [biff/query biff/now params]}]
    (let [most-recent (first (query {:select [:test-rss-post/feed-slug
                                              :test-rss-post/feed-title]
                                     :from :test-rss-post
                                     :order-by [[:test-rss-post/published-at :desc]]
                                     :limit 1}))
          feed-slug (let [s (not-empty (str/trim (or (:feed-slug params) "")))]
                      (or s
                          (:test-rss-post/feed-slug most-recent)
                          "my-feed"))
          feed-title (let [s (not-empty (str/trim (or (:feed-title params) "")))]
                       (or s
                           (:test-rss-post/feed-title most-recent)
                           "My Test Feed"))
          post-title (str/trim (or (:post-title params) ""))
          post-url (not-empty (str/trim (or (:post-url params) "")))
          post-content (not-empty (str/trim (or (:post-content params) "")))
          published-at (or (some-> (:published-at params)
                                   not-empty
                                   tick/date-time
                                   (tick/in (tick/zone "UTC"))
                                   tick/instant)
                           now)]
      (if (empty? post-title)
        {:status 303
         :headers {"Location" (href page-route {:error "post-title-required"})}}
        {:biff.fx/sqlite [:biff.fx/sqlite
                          [{:insert-into :test-rss-post
                            :values [{:test-rss-post/id (gen/uuid)
                                      :test-rss-post/feed-slug feed-slug
                                      :test-rss-post/feed-title feed-title
                                      :test-rss-post/post-title post-title
                                      :test-rss-post/post-url post-url
                                      :test-rss-post/post-content post-content
                                      :test-rss-post/published-at published-at}]}]]
         :status 303
         :headers {"Location" (href page-route {:created "true"})}}))))

(fx/defroute delete-post-route "/admin/rss-test/delete"
  :post
  (fn [{:keys [params]}]
    (let [id (some-> (:id params) parse-uuid)]
      (merge {:status 303
             :headers {"Location" (href page-route)}}
             (when id
               {:biff.fx/sqlite [:biff.fx/sqlite
                                 [{:delete-from :test-rss-post
                                   :where [:= :test-rss-post/id id]}]]})))))

(defn- escape-xml [s]
  (when s
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\"" "&quot;")
        (str/replace "'" "&apos;"))))

(fx/defroute feed-route "/admin/test-feed/:slug"
  :get
  (fn [{:keys [biff/query biff/base-url path-params]}]
    (let [slug (:slug path-params)
          posts (query {:select :*
                        :from :test-rss-post
                        :where [:= :test-rss-post/feed-slug slug]
                        :order-by [[:test-rss-post/published-at :desc]]})
          feed-title (or (:test-rss-post/feed-title (first posts))
                         slug)
          feed-link (str base-url "/admin/test-feed/" slug)
          items-xml (str/join
                     "\n"
                     (for [{:test-rss-post/keys [post-title post-url post-content published-at id]} posts]
                       (str "    <item>\n"
                            "      <title>" (escape-xml post-title) "</title>\n"
                            (when post-url
                              (str "      <link>" (escape-xml post-url) "</link>\n"))
                            "      <guid>" id "</guid>\n"
                            (when post-content
                              (str "      <description><![CDATA[" post-content "]]></description>\n"))
                            (when published-at
                              (str "      <pubDate>" (.format (java.time.format.DateTimeFormatter/ofPattern
                                                               "EEE, dd MMM yyyy HH:mm:ss Z")
                                                              (.atZone (.toInstant (java.util.Date/from published-at))
                                                                       (java.time.ZoneId/of "UTC")))
                                   "</pubDate>\n"))
                            "    </item>")))]
      {:status 200
       :headers {"Content-Type" "application/rss+xml; charset=utf-8"}
       :body (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                  "<rss version=\"2.0\">\n"
                  "  <channel>\n"
                  "    <title>" (escape-xml feed-title) "</title>\n"
                  "    <link>" (escape-xml feed-link) "</link>\n"
                  "    <description>Test RSS feed: " (escape-xml slug) "</description>\n"
                  items-xml "\n"
                  "  </channel>\n"
                  "</rss>\n")})))

(fx/defroute-pathom page-content-route "/admin/rss-test/content"
  []

  :get
  (fn [{:keys [biff/query biff/base-url params]} _]
    (let [posts (query {:select :*
                        :from :test-rss-post
                        :order-by [[:test-rss-post/published-at :desc]]})
          most-recent (first posts)
          default-slug (or (:test-rss-post/feed-slug most-recent) "my-feed")
          default-title (or (:test-rss-post/feed-title most-recent) "My Test Feed")
          feed-slugs (->> posts (map :test-rss-post/feed-slug) distinct sort)]
      (ui/wide-page-well
       (ui/section
        {:title "Create Test RSS Post"}
        (when (= (:error params) "post-title-required")
          (ui/callout {:ui/type :error} "Post title is required."))
        (when (= (:created params) "true")
          (ui/callout {:ui/type :info} "Post created successfully."))
        (biff/form
         {:action (href create-post-route)}
         [:.flex.flex-col.gap-4
          (ui/form-input
           {:ui/label "Feed Slug"
            :ui/description "URL-safe identifier for the feed (e.g. my-feed)."
            :name "feed-slug"
            :placeholder default-slug
            :value default-slug})
          (ui/form-input
           {:ui/label "Feed Title"
            :name "feed-title"
            :placeholder default-title
            :value default-title})
          (ui/form-input
           {:ui/label "Post Title"
            :name "post-title"
            :required true
            :placeholder "My Test Post"})
          (ui/form-input
           {:ui/label "Post URL"
            :ui/description "Optional. If provided, the post will link to this URL."
            :name "post-url"
            :type "url"
            :placeholder "https://example.com/article"})
          (ui/form-input
           {:ui/label "Post Content"
            :ui/description "HTML content for the post. Can be left blank if URL is set."
            :name "post-content"
            :ui/input-type :textarea
            :placeholder "<p>Hello world</p>"})
          (ui/form-input
           {:ui/label "Published At"
            :ui/description "Defaults to current time if left blank."
            :name "published-at"
            :type "datetime-local"})
          (ui/button {:type "submit"
                      :ui/type :primary}
                     "Create Post")]))

       (when (not-empty feed-slugs)
         (ui/section
          {:title "Feed URLs"}
          [:.flex.flex-col.gap-2
           (for [slug feed-slugs
                 :let [url (str base-url "/admin/test-feed/" slug)]]
             [:div.text-sm
              [:a.text-tealv-600.hover:underline {:href url :target "_blank"} url]])]))

       (ui/section
        {:title "Posts"
         :description (str (count posts) " post" (when (not= 1 (count posts)) "s"))}
        (if (empty? posts)
          [:p.text-sm.text-neut-500 "No test posts yet."]
          [:.flex.flex-col.gap-4
           (for [{:test-rss-post/keys [id feed-slug feed-title post-title post-url published-at]} posts]
             [:.border.border-neut-200.rounded-lg.p-4
              [:.flex.justify-between.items-start
               [:div
                [:div.font-medium post-title]
                [:div.text-sm.text-neut-500
                 feed-title " (" feed-slug ")"
                 (when post-url
                   [:<> " · " [:a.text-tealv-600.hover:underline {:href post-url :target "_blank"} "link"]])]
                (when published-at
                  [:div.text-sm.text-neut-400
                   (str (tick/date-time (tick/in (tick/instant published-at) (tick/zone "UTC"))))])]
               (biff/form
                {:action (href delete-post-route)
                 :class "ml-4"}
                [:input {:type "hidden" :name "id" :value (str id)}]
                (ui/button {:type "submit"
                            :ui/type :danger}
                           "Delete"))]])]))))))

(fx/defroute-pathom page-route "/admin/rss-test"
  [:app.shell/app-shell]

  :get
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :rss-test)
     (ui/lazy-load (href page-content-route)))))

(def module
  {:routes [["" {:middleware [lib.mid/wrap-admin]}
             page-route
             page-content-route
             create-post-route
             delete-post-route]
            feed-route]})
