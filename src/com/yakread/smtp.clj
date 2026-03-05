(ns com.yakread.smtp
  (:require
   [clojure.data.generators :as gen]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [com.biffweb :as biff]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.smtp :as lib.smtp]
   [tick.core :as tick]) 
  (:import
   [org.jsoup Jsoup]))

(defn accept? [_] true)

(defn infer-post-url [headers html]
  (let [jsoup-parsed (Jsoup/parse html)
        url (some-> (or (some-> (get-in headers ["list-post" 0])
                                (str/replace #"(^<|>$)" ""))
                        (some-> (.select jsoup-parsed "a.post-title-link")
                                first
                                (.attr "abs:href"))
                        (some->> (.select jsoup-parsed "a")
                                 (filter #(re-find #"(?i)read online" (.text %)))
                                 first
                                 (#(.attr % "abs:href"))))
                    (str/replace #"\?.*" ""))]
    (when-not (some-> url (str/includes? "link.mail.beehiiv.com"))
      url)))

(fx/defmachine deliver*
  :start
  (fn [{:keys [biff/query yakread/domain biff.smtp/message]}]
    (let [result (and (or (not domain) (= domain (:domain message)))
                      (not-empty
                       (query
                                {:select 1
                                 :from :user
                                 :where [:=
                                         :user/email-username
                                         (str/lower-case (:username message))]})))
          html (when result
                 (lib.smtp/extract-html message))]
      (log/info "receiving email for"
                (str (str/lower-case (:username message)) "@" (:domain message)))
      (if-not result
        (log/warn "Rejected incoming email for"
                  (str (str/lower-case (:username message)) "@" (:domain message)))
        {:com.yakread.fx/js {:fn-name "juice" :input {:html html}}
         :biff.fx/next :end
         ::url (infer-post-url (:headers message) html)})))

  :end
  (fn [{:keys [biff.smtp/message biff/query biff/now ::url]
        {:keys [html]} :com.yakread.fx/js}]
    (if-not html
      (do
        (log/warn "juice failed to parse message for" (:username message))
        {:biff.fx/spit {:file (str "storage/juice-failed/" 
                                   (inst-ms (tick/instant now))
                                   ".edn")
                        :content (pr-str message)}})
      (let [html (-> html
                     lib.content/normalize
                     (str/replace #"#transparent" "transparent"))
            raw-content-key (gen/uuid)
            parsed-content-key (gen/uuid)
            from (some (fn [k]
                         (->> (concat (:from message)
                                      (:reply-to message)
                                      [(:sender message)])
                              (some (fn [recipient]
                                      (when (str/includes? (get recipient k "") "@")
                                        (get recipient k))))))
                       [:personal :address])
            text (lib.content/html->text html)

            [{user-id :user/id
              sub-id :sub/id}]
            (query
                     {:select [:user/id
                               :sub/id]
                      :from :user
                      :left-join [:sub [:and
                                        [:= :sub/user-id :user/id]
                                        [:= :sub/email-from from]]]
                      :where [:= :user/email-username (str/lower-case (:username message))]
                      :limit 1})
            new-sub (nil? sub-id)
            sub-id (or sub-id (gen/uuid))
            first-header (fn [header-name]
                           (some lib.smtp/decode-header (get-in message [:headers header-name])))]
        [{:biff.fx/s3 [{:config-ns 'yakread.s3.emails
                        :key raw-content-key
                        :method "PUT"
                        :body (:raw message)
                        :headers {"x-amz-acl" "private"
                                  "content-type" "text/plain"}}
                       {:config-ns 'yakread.s3.content
                        :key parsed-content-key
                        :method "PUT"
                        :body html
                        :headers {"x-amz-acl" "private"
                                  "content-type" "text/html"}}]}
         {:biff.fx/sqlite (concat
                           [{:insert-into :item
                             :values [(lib.core/some-vals
                                       {:item/id (gen/uuid)
                                        :item/ingested-at now
                                        :item/record-type [:lift :item.record-type/email]
                                        :item/title (:subject message)
                                        :item/url url
                                        :item/content-key parsed-content-key
                                        :item/published-at now
                                        :item/excerpt (lib.content/excerpt text)
                                        :item/author-name from
                                        :item/lang (lib.content/lang html)
                                        :item/length (count text)
                                        :item/email-sub-id sub-id
                                        :item/email-raw-content-key raw-content-key
                                        :item/email-list-unsubscribe (first-header "list-unsubscribe")
                                        :item/email-list-unsubscribe-post (first-header "list-unsubscribe-post")
                                        :item/email-reply-to (some :address (:reply-to message))
                                        :item/email-maybe-confirmation (or new-sub nil)})]}]
                           (when new-sub
                             [{:insert-into :sub
                               :values [{:sub/id sub-id
                                         :sub/user-id user-id
                                         :sub/email-from from
                                         :sub/record-type [:lift :sub.record-type/email]
                                         :sub/created-at now}]
                               :on-conflict [:sub/user-id :sub/feed-id :sub/email-from]
                               :do-nothing true}]))}]))))
