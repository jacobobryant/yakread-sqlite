(ns com.yakread.ui-components.digest.text
  (:require
   [clojure.string :as str]
   [com.biffweb.graph :as biff.graph :refer [defresolver]]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.ui-email :as uie]
   [com.yakread.routes :as routes]
   [lambdaisland.uri :as uri]))

;; TODO use this instead of periods so gmail doesn't screw things up.
;; "․"

(defresolver settings
  {:input [:digest.settings/freq-text
           :digest.settings/time-text]
   :output [::settings]}
  [{:keys [biff/base-url]}
   {:digest.settings/keys [freq-text time-text]}]
  {::settings
   (str "You're getting this personalized digest " freq-text
        " at " time-text ".\n"
        "Change settings: " base-url (href routes/settings-page) "\n")})

(defresolver sponsored
  {:input [{:user/ad-rec [:ad/id
                          :ad/title
                          :ad/host
                          :ad/description
                          :ad/url-with-protocol
                          :ad/click-cost
                          :ad/recording-url]}]
   :output [::sponsored]}
  [_ {:user/keys [ad-rec id]}]
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
          (recording-url {:ad.click/source :email
                          :user/id id}) "\n")}))

(defn compact-section [title op-name input-key output-key]
  (biff.graph/resolver
   {:id (keyword (namespace op-name) (name op-name))
    :input [:user/id
            {input-key [:item/id
                        :item/digest-url
                        :item/ui-details*
                        [:? :item/clean-title]
                        [:? :item/url]]}]
    :output [output-key]
    :resolve-fn
    (fn [_env input]
      (when-some [items (not-empty (get input input-key))]
        (let [body (str/join
                    "\n"
                    (for [{:item/keys [digest-url
                                       clean-title
                                       ui-details*]} items]
                      (str (str/join ui/interpunct (ui-details* {:show-author true
                                                                 :label-type :text})) "\n"
                           clean-title "\n"
                           (digest-url {:user/id (:user/id input)}) "\n")))]
          {output-key (str (str/upper-case title) "\n"
                           "---\n"
                           "\n"
                           body)})))}))

(def subscriptions
  (compact-section "Subscriptions" `subscriptions :user/digest-sub-items ::subscriptions))

(def bookmarks
  (compact-section "Bookmarks" `bookmarks :user/digest-bookmarks ::bookmarks))

(def icymi
  (compact-section "In case you missed it" `icymi :user/icymi-recs ::icymi))

(defresolver discover
  {:input [:user/id
           {:user/digest-discover-recs [:item/digest-url
                                        [:? :item/clean-title]
                                        [:? :item/image-url]
                                        [:? :item/excerpt]
                                        [:? :item/url]]}]
   :output [::discover]}
  [_ {:user/keys [digest-discover-recs] user-id :user/id}]
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

(defresolver text
  {:input [::settings
           [:? ::sponsored]
           [:? ::subscriptions]
           [:? ::bookmarks]
           [:? ::icymi]
           [:? ::discover]
           :digest/unsubscribe-url]
   :output [:digest/text]}
  [{:keys [biff/base-url]}
   {:digest/keys [unsubscribe-url]
    ::keys [settings
            sponsored
            subscriptions
            bookmarks
            icymi
            discover]}]
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

(def module {:biff.graph/resolvers [sponsored
                                    subscriptions
                                    bookmarks
                                    icymi
                                    discover
                                    settings
                                    text]})
