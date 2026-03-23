(ns com.yakread.model.schema)

(def columns
  "biff.sqlite columns map defining all table columns with types, refs, indexes,
   unique constraints, and enum values."
  {;; --- user ---
   :user/id                  {:type :uuid :primary-key true}
   :user/email               {:type :text :required true :index true}
   :user/roles               {:type :edn}
   :user/joined-at           {:type :inst}
   :user/digest-days         {:type :edn}
   :user/send-digest-at      {:type :text}
   :user/timezone            {:type :text}
   :user/digest-last-sent    {:type :inst}
   :user/from-the-sample     {:type :boolean}
   :user/use-original-links  {:type :boolean}
   :user/suppressed-at       {:type :inst}
   :user/email-username      {:type :text :index true}
   :user/customer-id         {:type :text :index true}
   :user/plan                {:type :enum :enum-values {0 :user.plan/quarter
                                                        1 :user.plan/annual}}
   :user/cancel-at           {:type :inst}

   ;; --- feed ---
   :feed/id                  {:type :uuid :primary-key true}
   :feed/url                 {:type :text :required true :unique true}
   :feed/synced-at           {:type :inst}
   :feed/title               {:type :text}
   :feed/description         {:type :text}
   :feed/image-url           {:type :text}
   :feed/etag                {:type :text}
   :feed/last-modified       {:type :text}
   :feed/failed-syncs        {:type :int}
   :feed/moderation          {:type :enum :enum-values {0 :feed.moderation/approved
                                                        1 :feed.moderation/blocked}}

   ;; --- sub ---
   :sub/id                     {:type :uuid :primary-key true}
   :sub/user-id                {:type :uuid :required true :ref :user/id :index true
                                :unique-with [:sub/feed-id :sub/email-from]}
   :sub/created-at             {:type :inst :required true}
   :sub/pinned-at              {:type :inst}
   :sub/record-type            {:type :enum :required true
                                :enum-values {0 :sub.record-type/feed
                                              1 :sub.record-type/email}}
   :sub/feed-id                {:type :uuid :ref :feed/id :index true}
   :sub/email-from             {:type :text}
   :sub/email-unsubscribed-at  {:type :inst}

   ;; --- item ---
   :item/id                          {:type :uuid :primary-key true}
   :item/ingested-at                 {:type :inst :required true :index true}
   :item/title                       {:type :text}
   :item/url                         {:type :text :index true}
   :item/redirect-urls               {:type :edn}
   :item/content                     {:type :text}
   :item/content-key                 {:type :uuid}
   :item/published-at                {:type :inst}
   :item/excerpt                     {:type :text}
   :item/author-name                 {:type :text}
   :item/author-url                  {:type :text}
   :item/feed-url                    {:type :text}
   :item/lang                        {:type :text}
   :item/site-name                   {:type :text}
   :item/byline                      {:type :text}
   :item/length                      {:type :int}
   :item/image-url                   {:type :text}
   :item/paywalled                   {:type :boolean}
   :item/record-type                 {:type :enum :required true :index true
                                      :enum-values {0 :item.record-type/feed
                                                    1 :item.record-type/email
                                                    2 :item.record-type/direct}}
   :item/feed-id                     {:type :uuid :ref :feed/id :index true}
   :item/feed-guid                   {:type :text}
   :item/email-sub-id                {:type :uuid :ref :sub/id :index true}
   :item/email-raw-content-key       {:type :uuid}
   :item/email-list-unsubscribe      {:type :text}
   :item/email-list-unsubscribe-post {:type :text}
   :item/email-reply-to              {:type :text}
   :item/email-maybe-confirmation    {:type :boolean}
   :item/direct-candidate-status     {:type :enum :index true
                                      :enum-values {0 :item.direct-candidate-status/ingest-failed
                                                    1 :item.direct-candidate-status/blocked
                                                    2 :item.direct-candidate-status/approved}}

   ;; --- redirect ---
   :redirect/id       {:type :uuid :primary-key true}
   :redirect/url      {:type :text :required true :index true}
   :redirect/item-id  {:type :uuid :required true :ref :item/id :index true}

   ;; --- user-item ---
   :user-item/id            {:type :uuid :primary-key true}
   :user-item/user-id       {:type :uuid :required true :ref :user/id :index true
                             :unique-with [:user-item/item-id]}
   :user-item/item-id       {:type :uuid :required true :ref :item/id :index true}
   :user-item/viewed-at     {:type :inst}
   :user-item/skipped-at    {:type :inst}
   :user-item/bookmarked-at {:type :inst}
   :user-item/favorited-at  {:type :inst}
   :user-item/disliked-at   {:type :inst}
   :user-item/reported-at   {:type :inst}
   :user-item/report-reason {:type :text}

   ;; --- digest ---
   :digest/id            {:type :uuid :primary-key true}
   :digest/user-id       {:type :uuid :required true :ref :user/id :index true}
   :digest/sent-at       {:type :inst :required true}
   :digest/subject-id    {:type :uuid :ref :item/id}
   :digest/ad-id         {:type :uuid :ref :ad/id}
   :digest/bulk-send-id  {:type :uuid :ref :bulk-send/id}

   ;; --- digest-item ---
   :digest-item/id        {:type :uuid :primary-key true}
   :digest-item/digest-id {:type :uuid :required true :ref :digest/id}
   :digest-item/item-id   {:type :uuid :required true :ref :item/id}
   :digest-item/kind      {:type :enum :required true
                           :enum-values {0 :digest-item.kind/icymi
                                         1 :digest-item.kind/discover}}

   ;; --- bulk-send ---
   :bulk-send/id             {:type :uuid :primary-key true}
   :bulk-send/sent-at        {:type :inst :required true}
   :bulk-send/payload-size   {:type :int :required true}
   :bulk-send/mailersend-id  {:type :text :required true}
   :bulk-send/digests        {:type :edn}

   ;; --- reclist ---
   :reclist/id          {:type :uuid :primary-key true}
   :reclist/user-id     {:type :uuid :required true :ref :user/id
                         :unique-with [:reclist/created-at]}
   :reclist/created-at  {:type :inst :required true}
   :reclist/clicked     {:type :edn :required true}

   ;; --- skip ---
   :skip/id          {:type :uuid :primary-key true}
   :skip/reclist-id  {:type :uuid :required true :ref :reclist/id
                      :unique-with [:skip/item-id :skip/ad-id]}
   :skip/item-id     {:type :uuid :ref :item/id}
   :skip/ad-id       {:type :uuid :ref :ad/id}

   ;; --- ad ---
   :ad/id              {:type :uuid :primary-key true}
   :ad/user-id         {:type :uuid :required true :ref :user/id :unique true}
   :ad/approve-state   {:type :enum :required true
                        :enum-values {0 :ad.approve-state/pending
                                      1 :ad.approve-state/approved
                                      2 :ad.approve-state/rejected}}
   :ad/updated-at      {:type :inst :required true}
   :ad/balance         {:type :int :required true}
   :ad/recent-cost     {:type :int :required true}
   :ad/bid             {:type :int}
   :ad/budget          {:type :int}
   :ad/url             {:type :text}
   :ad/title           {:type :text}
   :ad/description     {:type :text}
   :ad/image-url       {:type :text}
   :ad/paused          {:type :boolean}
   :ad/payment-failed  {:type :boolean}
   :ad/customer-id     {:type :text}
   :ad/session-id      {:type :text}
   :ad/payment-method  {:type :text}
   :ad/card-details    {:type :edn}

   ;; --- ad-click ---
   :ad-click/id          {:type :uuid :primary-key true}
   :ad-click/user-id     {:type :uuid :required true :ref :user/id
                          :unique-with [:ad-click/ad-id]}
   :ad-click/ad-id       {:type :uuid :required true :ref :ad/id}
   :ad-click/created-at  {:type :inst :required true}
   :ad-click/cost        {:type :int :required true}
   :ad-click/source      {:type :enum :required true
                          :enum-values {0 :ad-click.source/web
                                        1 :ad-click.source/email}}

   ;; --- ad-credit ---
   :ad-credit/id             {:type :uuid :primary-key true}
   :ad-credit/ad-id          {:type :uuid :required true :ref :ad/id :index true}
   :ad-credit/source         {:type :enum :required true
                              :enum-values {0 :ad-credit.source/charge
                                            1 :ad-credit.source/manual}}
   :ad-credit/amount         {:type :int :required true}
   :ad-credit/created-at     {:type :inst :required true}
   :ad-credit/charge-status  {:type :enum
                              :enum-values {0 :ad-credit.charge-status/pending
                                            1 :ad-credit.charge-status/confirmed
                                            2 :ad-credit.charge-status/failed}}

   ;; --- mv-sub (materialized view, kept for schema compatibility) ---
   :mv-sub/id             {:type :uuid :primary-key true}
   :mv-sub/sub-id         {:type :uuid :required true :ref :sub/id :unique true}
   :mv-sub/affinity-low   {:type :real}
   :mv-sub/affinity-high  {:type :real}
   :mv-sub/last-published {:type :inst}
   :mv-sub/unread         {:type :int}
   :mv-sub/n-read         {:type :int}

   ;; --- mv-user (materialized view, kept for schema compatibility) ---
   :mv-user/id              {:type :uuid :primary-key true}
   :mv-user/user-id         {:type :uuid :required true :ref :user/id :unique true}
   :mv-user/current-item-id {:type :uuid :ref :item/id}

   ;; --- deleted-user ---
   :deleted-user/id                  {:type :uuid :primary-key true}
   :deleted-user/email-username-hash {:type :text :required true}

   ;; --- auth-code ---
   :auth-code/id              {:type :uuid :primary-key true}
   :auth-code/email           {:type :text :required true}
   :auth-code/code            {:type :text :required true}
   :auth-code/created-at      {:type :inst :required true}
   :auth-code/failed-attempts {:type :int :required true}

   ;; --- test-rss-post ---
   :test-rss-post/id            {:type :uuid :primary-key true}
   :test-rss-post/feed-slug     {:type :text :required true}
   :test-rss-post/feed-title    {:type :text :required true}
   :test-rss-post/post-title    {:type :text :required true}
   :test-rss-post/post-url      {:type :text}
   :test-rss-post/post-content  {:type :text}
   :test-rss-post/published-at  {:type :inst :required true}})

(def module
  {})
