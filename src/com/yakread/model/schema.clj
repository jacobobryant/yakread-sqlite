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
              :biff/unique [[:sub/user-id :sub/feed-id]]}
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
               [:bulk-send/digests         [:vector :uuid]]]

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
                  [:deleted-user/email-username-hash   :string]]})

(def module
  {})
