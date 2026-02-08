(ns com.yakread.lib.email
  (:require
   [clj-http.client :as http]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [com.yakread.lib.ui-email :as uie]
   [rum.core :as rum]))

(defn- button [{:keys [href]} label]
  (uie/button
    {:href href
     :label label
     :bg-color "#17b897"
     :text-color "white"}))

(defn- signin-link [{:keys [biff/base-url]} {:keys [to url user-exists]}]
  (if user-exists
    {:to [{:email to}]
     :subject "Sign in to Yakread"
     :html (uie/html
            :logo-on-click base-url
            :logo-src (str base-url "/img/logo-navbar.png")
            :title "Sign in to Yakread"
            :hide-unsubscribe true
            :content
            [:<>
             [:div
              "We received a request to sign in to Yakread using this email address. "
              "Click the link to sign in:"]
             (uie/h-space "32px")
             (button {:href url} "Click here to sign in")
             (uie/h-space "32px")
             [:div "If you did not request this link, you can ignore this email."]
             (uie/h-space "8px")])
     :text (str "We received a request to sign in to Yakread using this email address. "
                "Click here to sign in:\n"
                "\n"
                url "\n"
                "\n"
                "If you did not request this link, you can ignore this email."
                "\n"
                uie/address)}
    {:to [{:email to}]
     :subject "Sign up for Yakread"
     :html (uie/html
            :logo-on-click base-url
            :logo-src (str base-url "/img/logo-navbar.png")
            :title "Sign up for Yakread"
            :hide-unsubscribe true
            :content
            [:<>
             [:div "Welcome to Yakread! Click the link to create your account:"]
             (uie/h-space "32px")
             (button {:href url} "Click here to sign up")
             (uie/h-space "32px")
             [:div "If you didn't mean to sign up for Yakread, you can ignore this email."]
             (uie/h-space "8px")])
     :text (str "Welcome to Yakread! Click here to create your account:\n"
                "\n"
                url "\n"
                "\n"
                "If you didn't mean to sign up for Yakread, you can ignore this email."
                "\n"
                uie/address)}))

(defn- signin-code [{:keys [biff/base-url]} {:keys [to code]}]
  {:to [{:email to}]
   :subject "Sign in to Yakread"
   :html (uie/html
          :logo-on-click base-url
          :logo-src (str base-url "/img/logo-navbar.png")
          :title "Sign in to Yakread"
          :hide-unsubscribe true
          :content
          [:<>
           [:div
            "We received a request to sign in to Yakread using this email address. "
            "Enter the following code to sign in:"]
           (uie/h-space "16px")
           [:div {:style {:font-size "2rem"}} code]
           (uie/h-space "16px")
           [:div
            "This code will expire in three minutes. "
            "If you did not request this code, you can ignore this email."]
           (uie/h-space "8px")])
   :text (str "Welcome to Yakread! "
              "Enter the following code to sign in:\n"
              "\n"
              code "\n"
              "\n"
              "This code will expire in three minutes. "
              "If you did not request this code, you can ignore this email."
              "\n"
              uie/address)})

(defn- alert [{:keys [biff/alert-email]} {:keys [subject rum text]}]
  {:to [{:email alert-email}]
   :subject subject
   :html (rum/render-static-markup rum)
   :text text})

(defn- export [{:keys [biff/base-url]} {:keys [to download-url]}]
  {:to [{:email to}]
   :subject "Your data is ready to download"
   :html (uie/html
          :logo-on-click base-url
          :logo-src (str base-url "/img/logo-navbar.png")
          :title "Yakread export"
          :hide-unsubscribe true
          :content
          [:<>
           [:div
            "Your Yakread data has been exported and is ready to download:"]
           (uie/h-space "32px")
           (button {:href download-url} "Download your data")
           (uie/h-space "32px")
           [:div "This link will expire after 7 days."]
           (uie/h-space "8px")])
   :text (str "Your Yakread data has been exported and is ready to download:\n"
              "\n"
              download-url "\n"
              "\n"
              "This link will expire after 7 days.\n"
              "\n"
              uie/address)})

(defn- template [ctx k opts]
  ((case k
     :signin-link signin-link
     :signin-code signin-code
     :alert alert
     :export export)
   ctx
   opts))

(defn send-mailersend [{:keys [biff/secret mailersend/from mailersend/reply-to]} form-params]
  (let [result (http/post "https://api.mailersend.com/v1/email"
                          {:oauth-token (secret :mailersend/api-key)
                           :content-type :json
                           :throw-exceptions false
                           :as :json
                           :form-params (merge {:from {:email from :name "Yakread"}
                                                :reply_to {:email reply-to}}
                                               form-params)})
        success (< (:status result) 400)]
    (when-not success
      (log/error (:body result)))
    success))

(defn- send-console [_ form-params opts]
  (println "TO:" (-> form-params :to first :email))
  (println "SUBJECT:" (:subject form-params))
  (println)
  (println (:text form-params))
  (println)
  (println "To send emails instead of printing them to the console, add your"
           "API keys for MailerSend and Recaptcha to config.env.")
  (when-some [code (:code opts)]
    (let [dir (io/file "storage")]
      (.mkdirs dir)
      (spit (io/file dir "test-auth-code.txt") code)))
  true)

(defn send-email [{:keys [biff/secret recaptcha/site-key] :as ctx} opts]
  (let [form-params (if-some [template-key (:template opts)]
                      (template ctx template-key opts)
                      opts)]
    (if (every? some? [(secret :mailersend/api-key)
                       (secret :recaptcha/secret-key)
                       site-key])
      (send-mailersend ctx form-params)
      (send-console ctx form-params opts))))
