(ns com.yakread.ui-components.digest.text
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.ui-email :as uie]
   [com.yakread.routes :as routes]
   [lambdaisland.uri :as uri]))

;; TODO use this instead of periods so gmail doesn't screw things up.
;; "․"

(defresolver settings [{:keys [biff/base-url]}
                       {:digest.settings/keys [freq-text time-text]}]
  {::settings
   (str "You're getting this personalized digest " freq-text
        " at " time-text ".\n"
        "Change settings: " base-url (href routes/settings-page) "\n")})

(defresolver sponsored [{:user/keys [ad-rec id]}]
  {::pco/input [{:user/ad-rec [:xt/id
                               :ad/title
                               :ad/host
                               :ad/description
                               :ad/url-with-protocol
                               :ad/click-cost
                               :ad/recording-url]}]
   ::pco/output [::sponsored]}
  (let [{:ad/keys [title
                   description
                   host
                   recording-url]} ad-rec]
    {::sponsored
     (str "SPONSORED\n"
          "---\n"
          "\n"
          title " (" host ")\n"
          description "\n"
          (recording-url {:ad.click/source :ad-click.source/email
                          :user/id id}) "\n")}))

(defn compact-section [title op-name input-key output-key]
  (pco/resolver
   op-name
   {::pco/input [:user/id
                 {input-key [:item/id
                             :item/digest-url
                             :item/ui-details*
                             (? :item/clean-title)
                             (? :item/url)]}]
    ::pco/output [output-key]}
   (fn [_env input]
     (when-some [items (not-empty (get input input-key))]
       {output-key
        (str (str/upper-case title) "\n"
             "---\n"
             "\n"
             (str/join
              "\n"
              (for [{:item/keys [digest-url
                                 clean-title
                                 ui-details*]} items]
                (str (str/join ui/interpunct (ui-details* {:show-author true
                                                           :label-type :text})) "\n"
                     clean-title "\n"
                     (digest-url {:user/id (:user/id input)}) "\n"))))}))))

(def subscriptions
  (compact-section "Subscriptions" `subscriptions :user/digest-sub-items ::subscriptions))

(def bookmarks
  (compact-section "Bookmarks" `bookmarks :user/digest-bookmarks ::bookmarks))

(def icymi
  (compact-section "In case you missed it" `icymi :user/icymi-recs ::icymi))

(defresolver discover [{:user/keys [digest-discover-recs] user-id :user/id}]
  {::pco/input [:user/id
                {:user/digest-discover-recs [:item/digest-url
                                             (? :item/clean-title)
                                             (? :item/image-url)
                                             (? :item/excerpt)
                                             (? :item/url)]}]
   ::pco/output [::discover]}
  (when (not-empty digest-discover-recs)
    {::discover
     (str "DISCOVER\n"
          "---\n"
          "\n"
          (str/join
           "\n"
           (for [{:item/keys [url
                              digest-url
                              clean-title
                              excerpt]} digest-discover-recs]
             (str (some-> url uri/uri :host str/trim not-empty) "\n"
                  clean-title "\n"
                  excerpt "\n"
                  (digest-url {:user/id user-id}) "\n"))))}))

(defresolver text [{:keys [biff/base-url]}
                   {:digest/keys [unsubscribe-url]
                    ::keys [settings
                            sponsored
                            subscriptions
                            bookmarks
                            icymi
                            discover]}]
  {::pco/input [::settings
                (? ::sponsored)
                (? ::subscriptions)
                (? ::bookmarks)
                (? ::icymi)
                (? ::discover)
                :digest/unsubscribe-url]
   ::pco/output [:digest/text]}
  (when-some [sections (->> [sponsored
                             subscriptions
                             bookmarks
                             icymi
                             discover]
                            (filterv some?)
                            not-empty)]
    {:digest/text
     (uie/text
      {:unsubscribe-url unsubscribe-url
       :content (str settings
                     "\n\n"
                     "Open Yakread: " base-url (href routes/for-you) "\n"
                     "\n\n"
                     (str/join "\n\n" sections))})}))

(def module {:resolvers [sponsored
                         subscriptions
                         bookmarks
                         icymi
                         discover
                         settings
                         text]})
