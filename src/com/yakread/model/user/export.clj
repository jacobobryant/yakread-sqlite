(ns com.yakread.model.user.export 
  (:require
   [clojure.data.csv :as csv]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]
   [rum.core :as rum]))

(defn- generate-opml [urls]
  (str
   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
   (rum/render-static-markup
    [:opml {:version "1.0"} "\n"
     "  " [:head [:title "Yakread RSS subscriptions"]] "\n"
     "  " [:body "\n"
           (for [url urls]
             [:<> "    " [:outline {:type "rss" :xmlUrl url}] "\n"])]])))

(defresolver feed-subs [{:biff/keys [query]} {:keys [user/id]}]
  {::pco/input [:user/id]}
  {:user.export/feed-subs
   (->> (query {:select :feed/url
                :from :sub
                :join [:feed [:= :sub/feed-id :feed/id]]
                :where [:= :sub/user-id id]})
        (mapv :feed/url)
        sort
        generate-opml)})

(defn- item-resolver [op-name output-key query-key csv-label]
  (pco/resolver
   op-name
   {::pco/input [:user/id]
    ::pco/output [output-key]}
   (fn [{:biff/keys [query]} {:keys [user/id]}]
     {output-key
      (let [rows (->> (query {:select [query-key
                                       :user-item/viewed-at
                                       :item/url :item/title :item/author-name]
                              :from :user-item
                              :join [:item [:= :user-item/item-id :item/id]]
                              :where [:and
                                      [:= :user-item/user-id id]
                                      [:is-not query-key nil]]})
                      (sort-by query-key #(compare %2 %1))
                      (mapv (juxt :item/url
                                  :item/title
                                  :item/author-name
                                  query-key
                                  :user-item/viewed-at))
                      (cons ["URL" "Title" "Author" csv-label "Read at"]))]
        (with-out-str
         (csv/write-csv *out* rows)))})))

(def bookmarks (item-resolver `bookmarks
                              :user.export/bookmarks
                              :user-item/bookmarked-at
                              "Bookmarked at"))

(def favorites (item-resolver `favorites
                              :user.export/favorites
                              :user-item/favorited-at
                              "Favorited at"))

(def module
  {:resolvers [feed-subs
               bookmarks
               favorites]})
