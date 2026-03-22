(ns com.yakread.model.schema)

(def ?
  "Mark an attribute as optional."
  {:optional true})

(defn r
  "Mark an attribute as a reference to another table."
  [target]
  {:biff/ref target})

(defn ?r
  "Mark an optional attribute as a reference."
  [target]
  (assoc (r target) :optional true))

(def schema
  {::string [:string {:max 2000}]
   ::day [:enum :sunday :monday :tuesday :wednesday :thursday :friday :saturday]

   :user [:map {:closed true}
          [:user/id                    :uuid]
          [:user/email                 ::string]
          [:user/roles               ? [:set [:enum :admin]]]
          [:user/joined-at           ? inst?]
          [:user/digest-days         ? [:set ::day]]
          [:user/send-digest-at      ? :string]
          [:user/timezone            ? ::string]
          [:user/digest-last-sent    ? inst?]
          [:user/from-the-sample     ? :boolean]
          [:user/use-original-links  ? :boolean]
          [:user/suppressed-at       ? inst?]
          [:user/email-username      ? ::string]
          [:user/customer-id         ? :string]
          [:user/plan                ? [:enum :user.plan/quarter :user.plan/annual]]
          [:user/cancel-at           ? inst?]]

   :feed [:map {:closed true
               :biff/unique [[:feed/url]]}
          [:feed/id                :uuid]
          [:feed/url               ::string]
          [:feed/synced-at       ? inst?]
          [:feed/title           ? ::string]
          [:feed/description     ? ::string]
          [:feed/image-url       ? ::string]
          [:feed/etag            ? ::string]
          [:feed/last-modified   ? ::string]
          [:feed/failed-syncs    ? :int]
          [:feed/moderation      ? [:enum :feed.moderation/approved :feed.moderation/blocked]]]

   :sub [:map {:closed true
              :biff/unique [[:sub/user-id :sub/feed-id :sub/email-from]]}
         [:sub/id                     :uuid]
         [:sub/user-id      (r :user) :uuid]
         [:sub/created-at             inst?]
         [:sub/pinned-at    ?         inst?]
         [:sub/record-type            [:enum :sub.record-type/feed :sub.record-type/email]]
         ;; feed sub fields
         [:sub/feed-id      (?r :feed) :uuid]
         ;; email sub fields
         [:sub/email-from           ? ::string]
         [:sub/email-unsubscribed-at ? inst?]]

   :item [:map {:closed true}
          [:item/id                  :uuid]
          [:item/ingested-at         inst?]
          [:item/title             ? ::string]
          [:item/url               ? ::string]
          [:item/redirect-urls     ? [:set ::string]]
          [:item/content           ? ::string]
          [:item/content-key       ? :uuid]
          [:item/published-at      ? inst?]
          [:item/excerpt           ? ::string]
          [:item/author-name       ? ::string]
          [:item/author-url        ? ::string]
          [:item/feed-url          ? ::string]
          [:item/lang              ? ::string]
          [:item/site-name         ? ::string]
          [:item/byline            ? ::string]
          [:item/length            ? :int]
          [:item/image-url         ? ::string]
          [:item/paywalled         ? :boolean]
          [:item/record-type         [:enum
                                      :item.record-type/feed
                                      :item.record-type/email
                                      :item.record-type/direct]]
          ;; feed item fields
          [:item/feed-id           (?r :feed) :uuid]
          [:item/feed-guid         ? ::string]
          ;; email item fields
          [:item/email-sub-id      (?r :sub) :uuid]
          [:item/email-raw-content-key ? :uuid]
          [:item/email-list-unsubscribe ? [:string {:max 5000}]]
          [:item/email-list-unsubscribe-post ? ::string]
          [:item/email-reply-to    ? ::string]
          [:item/email-maybe-confirmation ? :boolean]
          ;; direct item fields
          [:item/direct-candidate-status ? [:enum
                                            :item.direct-candidate-status/ingest-failed
                                            :item.direct-candidate-status/blocked
                                            :item.direct-candidate-status/approved]]]

   :redirect [:map {:closed true}
              [:redirect/id       :uuid]
              [:redirect/url      ::string]
              [:redirect/item-id  (r :item) :uuid]]

   :user-item [:map {:closed true
                    :biff/unique [[:user-item/user-id :user-item/item-id]]}
               [:user-item/id                  :uuid]
               [:user-item/user-id   (r :user) :uuid]
               [:user-item/item-id   (r :item) :uuid]
               [:user-item/viewed-at       ?   inst?]
               [:user-item/skipped-at      ?   inst?]
               [:user-item/bookmarked-at   ?   inst?]
               [:user-item/favorited-at    ?   inst?]
               [:user-item/disliked-at     ?   inst?]
               [:user-item/reported-at     ?   inst?]
               [:user-item/report-reason   ?   ::string]]

   :digest [:map {:closed true}
            [:digest/id                        :uuid]
            [:digest/user-id     (r :user)   :uuid]
            [:digest/sent-at                   inst?]
            [:digest/subject-id  (?r :item)  :uuid]
            [:digest/ad-id       (?r :ad)    :uuid]
            [:digest/bulk-send-id (?r :bulk-send) :uuid]]

   :digest-item [:map {:closed true}
                 [:digest-item/id                  :uuid]
                 [:digest-item/digest-id (r :digest) :uuid]
                 [:digest-item/item-id   (r :item)   :uuid]
                 [:digest-item/kind      [:enum
                                          :digest-item.kind/icymi
                                          :digest-item.kind/discover]]]

   :bulk-send [:map {:closed true}
               [:bulk-send/id              :uuid]
               [:bulk-send/sent-at         inst?]
               [:bulk-send/payload-size    :int]
               [:bulk-send/mailersend-id   :string]
               [:bulk-send/digests       ? [:vector :uuid]]]

   :reclist [:map {:closed true
                  :biff/unique [[:reclist/user-id :reclist/created-at]]}
             [:reclist/id                   :uuid]
             [:reclist/user-id    (r :user) :uuid]
             [:reclist/created-at           inst?]
             [:reclist/clicked              [:set :uuid]]]

   :skip [:map {:closed true
               :biff/unique [[:skip/reclist-id :skip/item-id :skip/ad-id]]}
          [:skip/id                      :uuid]
          [:skip/reclist-id (r :reclist) :uuid]
          [:skip/item-id    (?r :item)   :uuid]
          [:skip/ad-id      (?r :ad)     :uuid]]

   :ad [:map {:closed true
              :biff/unique [[:ad/user-id]]}
        [:ad/id                     :uuid]
        [:ad/user-id      (r :user) :uuid]
        [:ad/approve-state          [:enum
                                     :ad.approve-state/pending
                                     :ad.approve-state/approved
                                     :ad.approve-state/rejected]]
        [:ad/updated-at             inst?]
        [:ad/balance                :int]
        [:ad/recent-cost            :int]
        [:ad/bid            ?       :int]
        [:ad/budget         ?       :int]
        [:ad/url            ?       ::string]
        [:ad/title          ?       [:string {:max 75}]]
        [:ad/description    ?       [:string {:max 250}]]
        [:ad/image-url      ?       ::string]
        [:ad/paused         ?       :boolean]
        [:ad/payment-failed ?       :boolean]
        [:ad/customer-id    ?       :string]
        [:ad/session-id     ?       :string]
        [:ad/payment-method ?       :string]
        [:ad/card-details   ?       [:map {:closed true}
                                     [:brand     :string]
                                     [:last4     :string]
                                     [:exp_year  :int]
                                     [:exp_month :int]]]]

   :ad-click [:map {:closed true
                   :biff/unique [[:ad-click/user-id :ad-click/ad-id]]}
              [:ad-click/id                      :uuid]
              [:ad-click/user-id       (r :user) :uuid]
              [:ad-click/ad-id         (r :ad)   :uuid]
              [:ad-click/created-at              inst?]
              [:ad-click/cost                    :int]
              [:ad-click/source                  [:enum
                                                  :ad-click.source/web
                                                  :ad-click.source/email]]]

   :ad-credit [:map {:closed true}
               [:ad-credit/id                     :uuid]
               [:ad-credit/ad-id         (r :ad) :uuid]
               [:ad-credit/source                 [:enum
                                                   :ad-credit.source/charge
                                                   :ad-credit.source/manual]]
               [:ad-credit/amount                 :int]
               [:ad-credit/created-at             inst?]
               [:ad-credit/charge-status ?        [:enum
                                                   :ad-credit.charge-status/pending
                                                   :ad-credit.charge-status/confirmed
                                                   :ad-credit.charge-status/failed]]]

   :mv-sub [:map {:closed true
                  :biff/unique [[:mv-sub/sub-id]]}
            [:mv-sub/id                     :uuid]
            [:mv-sub/sub-id       (r :sub) :uuid]
            [:mv-sub/affinity-low     ?     :double]
            [:mv-sub/affinity-high    ?     :double]
            [:mv-sub/last-published   ?     inst?]
            [:mv-sub/unread           ?     :int]
            [:mv-sub/n-read             ?     :int]]

   :mv-user [:map {:closed true
                   :biff/unique [[:mv-user/user-id]]}
             [:mv-user/id                        :uuid]
             [:mv-user/user-id        (r :user) :uuid]
             [:mv-user/current-item-id (?r :item) :uuid]]

   :deleted-user [:map {:closed true}
                  [:deleted-user/id                    :uuid]
                  [:deleted-user/email-username-hash   :string]]

   :auth-code [:map {:closed true}
               [:auth-code/id              :uuid]
               [:auth-code/email           :string]
               [:auth-code/code            :string]
               [:auth-code/created-at      inst?]
               [:auth-code/failed-attempts :int]]

   :test-rss-post [:map {:closed true}
                   [:test-rss-post/id              :uuid]
                   [:test-rss-post/feed-slug        ::string]
                   [:test-rss-post/feed-title       ::string]
                   [:test-rss-post/post-title       ::string]
                   [:test-rss-post/post-url       ? ::string]
                   [:test-rss-post/post-content   ? [:string {:max 100000}]]
                   [:test-rss-post/published-at     inst?]]})

;; biff.sqlite columns map: column-id → column properties
(def columns
  {;; user
   :user/id                    {:type :uuid :primary-key true}
   :user/email                 {:type :text :required true :index true}
   :user/roles                 {:type :edn}
   :user/joined-at             {:type :inst}
   :user/digest-days           {:type :edn}
   :user/send-digest-at        {:type :text}
   :user/timezone              {:type :text}
   :user/digest-last-sent      {:type :inst}
   :user/from-the-sample       {:type :boolean}
   :user/use-original-links    {:type :boolean}
   :user/suppressed-at         {:type :inst}
   :user/email-username        {:type :text :index true}
   :user/customer-id           {:type :text :index true}
   :user/plan                  {:type :enum
                                :enum-values {0 :user.plan/quarter
                                              1 :user.plan/annual}}
   :user/cancel-at             {:type :inst}

   ;; feed
   :feed/id                    {:type :uuid :primary-key true}
   :feed/url                   {:type :text :required true :unique true}
   :feed/synced-at             {:type :inst}
   :feed/title                 {:type :text}
   :feed/description           {:type :text}
   :feed/image-url             {:type :text}
   :feed/etag                  {:type :text}
   :feed/last-modified         {:type :text}
   :feed/failed-syncs          {:type :int}
   :feed/moderation            {:type :enum
                                :enum-values {0 :feed.moderation/approved
                                              1 :feed.moderation/blocked}}

   ;; sub
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

   ;; item
   :item/id                    {:type :uuid :primary-key true}
   :item/ingested-at           {:type :inst :required true :index true}
   :item/title                 {:type :text}
   :item/url                   {:type :text :index true}
   :item/redirect-urls         {:type :edn}
   :item/content               {:type :text}
   :item/content-key           {:type :uuid}
   :item/published-at          {:type :inst}
   :item/excerpt               {:type :text}
   :item/author-name           {:type :text}
   :item/author-url            {:type :text}
   :item/feed-url              {:type :text}
   :item/lang                  {:type :text}
   :item/site-name             {:type :text}
   :item/byline                {:type :text}
   :item/length                {:type :int}
   :item/image-url             {:type :text}
   :item/paywalled             {:type :boolean}
   :item/record-type           {:type :enum :required true :index true
                                :enum-values {0 :item.record-type/feed
                                              1 :item.record-type/email
                                              2 :item.record-type/direct}}
   :item/feed-id               {:type :uuid :ref :feed/id :index true}
   :item/feed-guid             {:type :text}
   :item/email-sub-id          {:type :uuid :ref :sub/id :index true}
   :item/email-raw-content-key {:type :uuid}
   :item/email-list-unsubscribe      {:type :text}
   :item/email-list-unsubscribe-post {:type :text}
   :item/email-reply-to        {:type :text}
   :item/email-maybe-confirmation {:type :boolean}
   :item/direct-candidate-status {:type :enum :index true
                                  :enum-values {0 :item.direct-candidate-status/ingest-failed
                                                1 :item.direct-candidate-status/blocked
                                                2 :item.direct-candidate-status/approved}}

   ;; redirect
   :redirect/id                {:type :uuid :primary-key true}
   :redirect/url               {:type :text :required true :index true}
   :redirect/item-id           {:type :uuid :required true :ref :item/id :index true}

   ;; user-item
   :user-item/id               {:type :uuid :primary-key true}
   :user-item/user-id          {:type :uuid :required true :ref :user/id :index true
                                :unique-with [:user-item/item-id]}
   :user-item/item-id          {:type :uuid :required true :ref :item/id :index true}
   :user-item/viewed-at        {:type :inst}
   :user-item/skipped-at       {:type :inst}
   :user-item/bookmarked-at    {:type :inst}
   :user-item/favorited-at     {:type :inst}
   :user-item/disliked-at      {:type :inst}
   :user-item/reported-at      {:type :inst}
   :user-item/report-reason    {:type :text}

   ;; digest
   :digest/id                  {:type :uuid :primary-key true}
   :digest/user-id             {:type :uuid :required true :ref :user/id :index true}
   :digest/sent-at             {:type :inst :required true}
   :digest/subject-id          {:type :uuid :ref :item/id}
   :digest/ad-id               {:type :uuid :ref :ad/id}
   :digest/bulk-send-id        {:type :uuid :ref :bulk-send/id}

   ;; digest-item
   :digest-item/id             {:type :uuid :primary-key true}
   :digest-item/digest-id      {:type :uuid :required true :ref :digest/id}
   :digest-item/item-id        {:type :uuid :required true :ref :item/id}
   :digest-item/kind           {:type :enum :required true
                                :enum-values {0 :digest-item.kind/icymi
                                              1 :digest-item.kind/discover}}

   ;; bulk-send
   :bulk-send/id               {:type :uuid :primary-key true}
   :bulk-send/sent-at          {:type :inst :required true}
   :bulk-send/payload-size     {:type :int :required true}
   :bulk-send/mailersend-id    {:type :text :required true}
   :bulk-send/digests          {:type :edn}

   ;; reclist
   :reclist/id                 {:type :uuid :primary-key true}
   :reclist/user-id            {:type :uuid :required true :ref :user/id
                                :unique-with [:reclist/created-at]}
   :reclist/created-at         {:type :inst :required true}
   :reclist/clicked            {:type :edn :required true}

   ;; skip
   :skip/id                    {:type :uuid :primary-key true}
   :skip/reclist-id            {:type :uuid :required true :ref :reclist/id
                                :unique-with [:skip/item-id :skip/ad-id]}
   :skip/item-id               {:type :uuid :ref :item/id}
   :skip/ad-id                 {:type :uuid :ref :ad/id}

   ;; ad
   :ad/id                      {:type :uuid :primary-key true}
   :ad/user-id                 {:type :uuid :required true :ref :user/id :unique true}
   :ad/approve-state           {:type :enum :required true
                                :enum-values {0 :ad.approve-state/pending
                                              1 :ad.approve-state/approved
                                              2 :ad.approve-state/rejected}}
   :ad/updated-at              {:type :inst :required true}
   :ad/balance                 {:type :int :required true}
   :ad/recent-cost             {:type :int :required true}
   :ad/bid                     {:type :int}
   :ad/budget                  {:type :int}
   :ad/url                     {:type :text}
   :ad/title                   {:type :text}
   :ad/description             {:type :text}
   :ad/image-url               {:type :text}
   :ad/paused                  {:type :boolean}
   :ad/payment-failed          {:type :boolean}
   :ad/customer-id             {:type :text}
   :ad/session-id              {:type :text}
   :ad/payment-method          {:type :text}
   :ad/card-details            {:type :edn}

   ;; ad-click
   :ad-click/id                {:type :uuid :primary-key true}
   :ad-click/user-id           {:type :uuid :required true :ref :user/id
                                :unique-with [:ad-click/ad-id]}
   :ad-click/ad-id             {:type :uuid :required true :ref :ad/id}
   :ad-click/created-at        {:type :inst :required true}
   :ad-click/cost              {:type :int :required true}
   :ad-click/source            {:type :enum :required true
                                :enum-values {0 :ad-click.source/web
                                              1 :ad-click.source/email}}

   ;; ad-credit
   :ad-credit/id               {:type :uuid :primary-key true}
   :ad-credit/ad-id            {:type :uuid :required true :ref :ad/id :index true}
   :ad-credit/source           {:type :enum :required true
                                :enum-values {0 :ad-credit.source/charge
                                              1 :ad-credit.source/manual}}
   :ad-credit/amount           {:type :int :required true}
   :ad-credit/created-at       {:type :inst :required true}
   :ad-credit/charge-status    {:type :enum
                                :enum-values {0 :ad-credit.charge-status/pending
                                              1 :ad-credit.charge-status/confirmed
                                              2 :ad-credit.charge-status/failed}}

   ;; mv-sub
   :mv-sub/id                  {:type :uuid :primary-key true}
   :mv-sub/sub-id              {:type :uuid :required true :ref :sub/id :unique true}
   :mv-sub/affinity-low        {:type :real}
   :mv-sub/affinity-high       {:type :real}
   :mv-sub/last-published      {:type :inst}
   :mv-sub/unread              {:type :int}
   :mv-sub/n-read              {:type :int}

   ;; mv-user
   :mv-user/id                 {:type :uuid :primary-key true}
   :mv-user/user-id            {:type :uuid :required true :ref :user/id :unique true}
   :mv-user/current-item-id    {:type :uuid :ref :item/id}

   ;; deleted-user
   :deleted-user/id            {:type :uuid :primary-key true}
   :deleted-user/email-username-hash {:type :text :required true}

   ;; auth-code
   :auth-code/id               {:type :uuid :primary-key true}
   :auth-code/email            {:type :text :required true}
   :auth-code/code             {:type :text :required true}
   :auth-code/created-at       {:type :inst :required true}
   :auth-code/failed-attempts  {:type :int :required true}

   ;; test-rss-post
   :test-rss-post/id           {:type :uuid :primary-key true}
   :test-rss-post/feed-slug    {:type :text :required true}
   :test-rss-post/feed-title   {:type :text :required true}
   :test-rss-post/post-title   {:type :text :required true}
   :test-rss-post/post-url     {:type :text}
   :test-rss-post/post-content {:type :text}
   :test-rss-post/published-at {:type :inst :required true}})

(def module
  {})
