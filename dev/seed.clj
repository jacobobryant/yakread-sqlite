(ns seed
  "Seed data for e2e tests.

  Creates a user (seed@example.com) with subscriptions, feeds, items,
  and bookmarks/favorites so that authenticated pages show content.

  Run via nREPL after the server has started:
    (require '[seed])
    (seed/seed! @com.yakread/system)"
  (:require
   [com.biffweb.experimental :as biffx]
   [com.yakread.lib.sqlite :as lib.sqlite]
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

(defn days-ago-instant [n]
  (tick/instant (tick/<< (tick/instant) (tick/new-duration n :days))))

(defn seed! [ctx]
  (println "Inserting seed data for e2e tests...")
  (let [now (tick/instant)]
    ;; User
    (lib.sqlite/execute ctx
      {:insert-into :user
       :values [{:user/id seed-user-id
                 :user/email seed-email
                 :user/joined-at now
                 :user/timezone "America/New_York"
                 :user/digest-days #{:monday :wednesday :friday}
                 :user/send-digest-at "08:00"}]
       :on-conflict [:user/id]
       :do-nothing true})

    ;; Feeds
    (lib.sqlite/execute ctx
      {:insert-into :feed
       :values [{:feed/id feed-id-1
                 :feed/url "https://example.com/feed.xml"
                 :feed/title "Example Tech Blog"
                 :feed/description "A blog about technology and programming"
                 :feed/synced-at now}
                {:feed/id feed-id-2
                 :feed/url "https://example.com/news.xml"
                 :feed/title "Daily News Digest"
                 :feed/description "Daily news and current events"
                 :feed/synced-at now}]
       :on-conflict [:feed/id]
       :do-nothing true})

    ;; Subscriptions
    (lib.sqlite/execute ctx
      {:insert-into :sub
       :values [{:sub/id (biffx/prefix-uuid seed-user-id sub-id-1)
                 :sub/user-id seed-user-id
                 :sub/feed-id feed-id-1
                 :sub/created-at now
                 :sub/record-type [:lift :sub.record-type/feed]}
                {:sub/id (biffx/prefix-uuid seed-user-id sub-id-2)
                 :sub/user-id seed-user-id
                 :sub/feed-id feed-id-2
                 :sub/created-at now
                 :sub/record-type [:lift :sub.record-type/feed]}]
       :on-conflict [:sub/id]
       :do-nothing true})

    ;; Feed items
    (lib.sqlite/execute ctx
      {:insert-into :item
       :values [{:item/id (biffx/prefix-uuid feed-id-1 item-id-1)
                 :item/feed-id feed-id-1
                 :item/title "Introduction to Clojure"
                 :item/url "https://example.com/intro-clojure"
                 :item/excerpt "Learn the basics of Clojure, a modern Lisp for the JVM."
                 :item/author-name "Jane Developer"
                 :item/published-at (days-ago-instant 1)
                 :item/ingested-at now
                 :item/length 2500
                 :item/record-type [:lift :item.record-type/feed]}
                {:item/id (biffx/prefix-uuid feed-id-1 item-id-2)
                 :item/feed-id feed-id-1
                 :item/title "Building Web Apps with Biff"
                 :item/url "https://example.com/biff-web-apps"
                 :item/excerpt "A guide to building web applications using the Biff framework."
                 :item/author-name "Jane Developer"
                 :item/published-at (days-ago-instant 2)
                 :item/ingested-at now
                 :item/length 3200
                 :item/record-type [:lift :item.record-type/feed]}
                {:item/id (biffx/prefix-uuid feed-id-2 item-id-3)
                 :item/feed-id feed-id-2
                 :item/title "Tech Industry Update"
                 :item/url "https://example.com/tech-update"
                 :item/excerpt "The latest news from the technology industry."
                 :item/author-name "News Bot"
                 :item/published-at (days-ago-instant 3)
                 :item/ingested-at now
                 :item/length 1800
                 :item/record-type [:lift :item.record-type/feed]}]
       :on-conflict [:item/id]
       :do-nothing true})

    ;; Bookmarked item (direct type)
    (lib.sqlite/execute ctx
      {:insert-into :item
       :values [{:item/id (biffx/prefix-uuid "0000" item-id-bm)
                 :item/title "Bookmarked: XTDB Deep Dive"
                 :item/url "https://example.com/xtdb-deep-dive"
                 :item/excerpt "A deep dive into XTDB, the bitemporal database."
                 :item/author-name "DB Expert"
                 :item/published-at (days-ago-instant 5)
                 :item/ingested-at now
                 :item/length 4000
                 :item/record-type [:lift :item.record-type/direct]}
                {:item/id (biffx/prefix-uuid "0000" item-id-fav)
                 :item/title "Favorite: Functional Programming Patterns"
                 :item/url "https://example.com/fp-patterns"
                 :item/excerpt "Essential functional programming patterns for everyday use."
                 :item/author-name "FP Guru"
                 :item/published-at (days-ago-instant 4)
                 :item/ingested-at now
                 :item/length 3500
                 :item/record-type [:lift :item.record-type/direct]}]
       :on-conflict [:item/id]
       :do-nothing true})

    ;; User-items (bookmarks and favorites)
    (lib.sqlite/execute ctx
      {:insert-into :user-item
       :values [{:user-item/id (biffx/prefix-uuid seed-user-id ui-id-bm)
                 :user-item/user-id seed-user-id
                 :user-item/item-id (biffx/prefix-uuid "0000" item-id-bm)
                 :user-item/bookmarked-at (days-ago-instant 1)}
                {:user-item/id (biffx/prefix-uuid seed-user-id ui-id-fav)
                 :user-item/user-id seed-user-id
                 :user-item/item-id (biffx/prefix-uuid "0000" item-id-fav)
                 :user-item/favorited-at (days-ago-instant 1)}]
       :on-conflict [:user-item/id]
       :do-nothing true})

    ;; Materialized view records for subscriptions
    (lib.sqlite/execute ctx
      {:insert-into :mv-sub
       :values [{:mv-sub/id (biffx/prefix-uuid (biffx/prefix-uuid seed-user-id sub-id-1) mv-sub-id-1)
                 :mv-sub/sub-id (biffx/prefix-uuid seed-user-id sub-id-1)
                 :mv-sub/affinity-low 0.5
                 :mv-sub/affinity-high 0.5}
                {:mv-sub/id (biffx/prefix-uuid (biffx/prefix-uuid seed-user-id sub-id-2) mv-sub-id-2)
                 :mv-sub/sub-id (biffx/prefix-uuid seed-user-id sub-id-2)
                 :mv-sub/affinity-low 0.5
                 :mv-sub/affinity-high 0.5}]
       :on-conflict [:mv-sub/id]
       :do-nothing true}))

  (println "Seed data inserted successfully. User:" seed-email))
