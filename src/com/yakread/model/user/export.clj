(ns com.yakread.model.user.export 
  (:require
   [clojure.data.csv :as csv]
   [com.yakread.util.biff-staging :as biffs]
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

(defresolver feed-subs [{:keys [biff/conn*]} {:keys [user/id]}]
  {::pco/input [:user/id]}
  {:user.export/feed-subs
   (->> (biffs/q conn*
                 {:select :feed/url
                  :from :sub
                  :join [:feed [:= :sub/feed-id :feed/id]]
                  :where [:= :sub/user-id id]})
        (mapv :feed/url)
        sort
        generate-opml)})

;; TODO
;; - make this part of biffx/q
;; - pull syntax
;; - use schema + join-key to infer table, nest one vs. nest many
(defn- nest-one [join-key table columns]
  [[:nest_one {:select columns
               :from table
               :where [:= (keyword (name table) "id") join-key]}]
   join-key])

(defn- item-resolver [op-name output-key query-key csv-label]
  (pco/resolver
   op-name
   {::pco/input [:user/id]
    ::pco/output [output-key]}
   (fn [{:keys [biff/conn*]} {:keys [user/id]}]
     {output-key
      (let [rows (->> (biffs/q conn*
                               {:select [query-key
                                         :user-item/viewed-at
                                         (nest-one :user-item/item-id
                                                   :item
                                                   [:item/url :item/title :item/author-name])]
                                :from :user-item
                                :where [:and
                                        [:= :user-item/user-id id]
                                        [:is-not query-key nil]]})
                      (sort-by query-key #(compare %2 %1))
                      (mapv (juxt (comp :item/url :user-item/item-id)
                                  (comp :item/title :user-item/item-id)
                                  (comp :item/author-name :user-item/item-id)
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
