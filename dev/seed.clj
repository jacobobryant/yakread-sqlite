(ns seed
  "Seed data for e2e tests.

  Creates a user (seed@example.com) with subscriptions, feeds, items,
  and bookmarks/favorites so that authenticated pages show content.

  Run via nREPL after the server has started:
    (require '[seed])
    (seed/seed! @com.yakread/system)"
  (:require
   [com.biffweb.experimental :as biffx]
   [tick.core :as tick]))

(def seed-email "seed@example.com")

(def seed-user-id  #uuid "00000000-5eed-0000-0000-000000000001")
(def feed-id-1     #uuid "00000000-5eed-0000-0000-000000000010")
(def feed-id-2     #uuid "00000000-5eed-0000-0000-000000000011")
(def sub-id-1      #uuid "00000000-5eed-0000-0000-000000000020")
(def sub-id-2      #uuid "00000000-5eed-0000-0000-000000000021")
(def item-id-1     #uuid "00000000-5eed-0000-0000-000000000030")
(def item-id-2     #uuid "00000000-5eed-0000-0000-000000000031")
(def item-id-3     #uuid "00000000-5eed-0000-0000-000000000032")
(def item-id-bm    #uuid "00000000-5eed-0000-0000-000000000040")
(def item-id-fav   #uuid "00000000-5eed-0000-0000-000000000041")
(def ui-id-bm      #uuid "00000000-5eed-0000-0000-000000000050")
(def ui-id-fav     #uuid "00000000-5eed-0000-0000-000000000051")
(def mv-sub-id-1   #uuid "00000000-5eed-0000-0000-000000000060")
(def mv-sub-id-2   #uuid "00000000-5eed-0000-0000-000000000061")

(defn days-ago-zdt [n]
  (tick/in (tick/instant (tick/<< (tick/instant) (tick/new-duration n :days))) "UTC"))

(defn seed-tx []
  (let [now (tick/zoned-date-time)]
    [;; User
     [:put-docs :user
      {:xt/id          seed-user-id
       :user/email     seed-email
       :user/joined-at now
       :user/timezone*  "America/New_York"
       :user/digest-days #{:monday :wednesday :friday}
       :user/send-digest-at (java.time.LocalTime/of 8 0)}]

     ;; Feeds
     [:put-docs :feed
      {:xt/id             feed-id-1
       :feed/url          "https://example.com/feed.xml"
       :feed/title        "Example Tech Blog"
       :feed/description  "A blog about technology and programming"
       :feed/synced-at    now}]
     [:put-docs :feed
      {:xt/id             feed-id-2
       :feed/url          "https://example.com/news.xml"
       :feed/title        "Daily News Digest"
       :feed/description  "Daily news and current events"
       :feed/synced-at    now}]

     ;; Subscriptions
     [:put-docs :sub
      {:xt/id           (biffx/prefix-uuid seed-user-id sub-id-1)
       :sub/user        seed-user-id
       :sub.feed/feed   feed-id-1
       :sub/created-at  now}]
     [:put-docs :sub
      {:xt/id           (biffx/prefix-uuid seed-user-id sub-id-2)
       :sub/user        seed-user-id
       :sub.feed/feed   feed-id-2
       :sub/created-at  now}]

     ;; Feed items
     [:put-docs :item
      {:xt/id              (biffx/prefix-uuid feed-id-1 item-id-1)
       :item.feed/feed     feed-id-1
       :item/title         "Introduction to Clojure"
       :item/url           "https://example.com/intro-clojure"
       :item/excerpt       "Learn the basics of Clojure, a modern Lisp for the JVM."
       :item/author-name   "Jane Developer"
       :item/published-at  (days-ago-zdt 1)
       :item/ingested-at   now
       :item/length        2500}]
     [:put-docs :item
      {:xt/id              (biffx/prefix-uuid feed-id-1 item-id-2)
       :item.feed/feed     feed-id-1
       :item/title         "Building Web Apps with Biff"
       :item/url           "https://example.com/biff-web-apps"
       :item/excerpt       "A guide to building web applications using the Biff framework."
       :item/author-name   "Jane Developer"
       :item/published-at  (days-ago-zdt 2)
       :item/ingested-at   now
       :item/length        3200}]
     [:put-docs :item
      {:xt/id              (biffx/prefix-uuid feed-id-2 item-id-3)
       :item.feed/feed     feed-id-2
       :item/title         "Tech Industry Update"
       :item/url           "https://example.com/tech-update"
       :item/excerpt       "The latest news from the technology industry."
       :item/author-name   "News Bot"
       :item/published-at  (days-ago-zdt 3)
       :item/ingested-at   now
       :item/length        1800}]

     ;; Bookmarked item (direct type)
     [:put-docs :item
      {:xt/id              (biffx/prefix-uuid "0000" item-id-bm)
       :item/doc-type      :item/direct
       :item/title         "Bookmarked: XTDB Deep Dive"
       :item/url           "https://example.com/xtdb-deep-dive"
       :item/excerpt       "A deep dive into XTDB, the bitemporal database."
       :item/author-name   "DB Expert"
       :item/published-at  (days-ago-zdt 5)
       :item/ingested-at   now
       :item/length        4000}]
     [:put-docs :user-item
      {:xt/id                  (biffx/prefix-uuid seed-user-id ui-id-bm)
       :user-item/user         seed-user-id
       :user-item/item         (biffx/prefix-uuid "0000" item-id-bm)
       :user-item/bookmarked-at (days-ago-zdt 1)}]

     ;; Favorited item (direct type)
     [:put-docs :item
      {:xt/id              (biffx/prefix-uuid "0000" item-id-fav)
       :item/doc-type      :item/direct
       :item/title         "Favorite: Functional Programming Patterns"
       :item/url           "https://example.com/fp-patterns"
       :item/excerpt       "Essential functional programming patterns for everyday use."
       :item/author-name   "FP Guru"
       :item/published-at  (days-ago-zdt 4)
       :item/ingested-at   now
       :item/length        3500}]
     [:put-docs :user-item
      {:xt/id                  (biffx/prefix-uuid seed-user-id ui-id-fav)
       :user-item/user         seed-user-id
       :user-item/item         (biffx/prefix-uuid "0000" item-id-fav)
       :user-item/favorited-at (days-ago-zdt 1)}]

     ;; Materialized view records for subscriptions
     [:put-docs :mv-sub
      {:xt/id             (biffx/prefix-uuid (biffx/prefix-uuid seed-user-id sub-id-1) mv-sub-id-1)
       :mv.sub/sub        (biffx/prefix-uuid seed-user-id sub-id-1)
       :mv.sub/affinity-low  0.5
       :mv.sub/affinity-high 0.5}]
     [:put-docs :mv-sub
      {:xt/id             (biffx/prefix-uuid (biffx/prefix-uuid seed-user-id sub-id-2) mv-sub-id-2)
       :mv.sub/sub        (biffx/prefix-uuid seed-user-id sub-id-2)
       :mv.sub/affinity-low  0.5
       :mv.sub/affinity-high 0.5}]]))

(defn seed! [{:keys [biff/node] :as ctx}]
  (println "Inserting seed data for e2e tests...")
  (biffx/submit-tx ctx (seed-tx))
  ;; Wait for the transaction to be indexed
  (Thread/sleep 2000)
  (let [user (first (biffx/q node {:select :* :from :user :where [:= :user/email seed-email]}))]
    (if user
      (println "Seed data inserted successfully. User:" seed-email)
      (println "WARNING: Seed data may not have been indexed yet."))))
