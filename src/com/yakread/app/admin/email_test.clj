(ns com.yakread.app.admin.email-test
  (:require
   [clojure.string :as str]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.smtp :as lib.smtp]
   [com.yakread.lib.ui :as ui]
   [rum.core :as rum]))

(declare page-route send-email-route)

(fx/defmachine send-test-email
  :start
  (fn [{{:keys [from-address from-name to-address subject url]} :params
        :keys [biff/base-url]}]
    (if (empty? url)
      {::error "URL is required."}
      {:biff.fx/http [:biff.fx/http
                      {:url url
                       :method :get
                       :headers {"User-Agent" (or base-url "Yakread")}
                       :socket-timeout 10000
                       :connection-timeout 10000
                       :throw-exceptions false}]
       :biff.fx/next :handle-http
       ::from-address from-address
       ::from-name from-name
       ::to-address to-address
       ::subject subject
       ::url url}))

  :handle-http
  (fn [{:keys [biff.fx/http] :as ctx}]
    (cond
      (:exception http)
      {::error (str "Failed to fetch URL: " (.getMessage (:exception http)))}

      (not (some-> http :headers (get "Content-Type") (str/includes? "text")))
      {::error (str "Response Content-Type is not text/html: "
                    (some-> http :headers (get "Content-Type")))}

      (> (count (:body http)) (* 10 1000 1000))
      {::error "Content too large (>10MB)."}

      :else
      {:com.yakread.fx/js [:com.yakread.fx/js
                           {:fn-name "readability"
                            :input {:url (::url ctx) :html (:body http)}}]
       :biff.fx/next :handle-readability
       ::raw-html (:body http)
       ::from-address (::from-address ctx)
       ::from-name (::from-name ctx)
       ::to-address (::to-address ctx)
       ::subject (::subject ctx)
       ::url (::url ctx)}))

  :handle-readability
  (fn [{:as ctx}]
    (let [{:keys [content title]} (:com.yakread.fx/js ctx)
          html (or content (::raw-html ctx))
          from-address (::from-address ctx)
          from-name (::from-name ctx)
          to-address (::to-address ctx)
          subject (::subject ctx)]
      (if (empty? html)
        {::error "Failed to extract content from URL."}
        (let [subject (if (not-empty subject) subject (str "Test: " title))]
          (lib.smtp/send-local! {:from from-address
                                 :from-name from-name
                                 :to to-address
                                 :subject subject
                                 :rum [:html
                                       [:body
                                        [:div {:dangerouslySetInnerHTML
                                               {:__html (lib.content/normalize html)}}]]]})          {::success (str "Email sent to " to-address " with subject: " subject)})))))

(fx/defroute send-email-route "/admin/email-test/send"
  :post
  (fn [ctx]
    (let [result (send-test-email ctx)]
      {:status 200
       :headers {"content-type" "text/html"}
       :body (rum/render-static-markup
              (if (::error result)
                (ui/callout {:ui/type :error} (::error result))
                (ui/callout {:ui/type :info} (::success result))))})))

(fx/defroute-pathom page-content-route "/admin/email-test/content"
  []

  :get
  (fn [ctx _]
    (let [result-id (ui/dom-id ::result)]
      (ui/wide-page-well
       (ui/section
        {:title "Send Test Email"}
        [:p.text-sm.text-neut-600
         "Fetch a URL, extract its content, and send it as an email to the local SMTP server. "
         "Use this to test email-receiving functionality."]
        [:form {:hx-post (href send-email-route)
                :hx-target (str "#" result-id)
                :hx-swap "innerHTML"}
         [:.flex.flex-col.gap-4
          (ui/form-input
           {:ui/label "From Address"
            :name "from-address"
            :type "email"
            :required true
            :placeholder "newsletter@example.com"
            :value "newsletter@example.com"})
          (ui/form-input
           {:ui/label "From Name"
            :name "from-name"
            :placeholder "Example Newsletter"
            :value "Example Newsletter"})
          (ui/form-input
           {:ui/label "To Address"
            :ui/description "The email-username@yourdomain.com address for the test account."
            :name "to-address"
            :type "text"
            :required true
            :placeholder "testuser@yakread.com"})
          (ui/form-input
           {:ui/label "Subject"
            :name "subject"
            :placeholder "(auto-generated from page title)"})
          (ui/form-input
           {:ui/label "URL"
            :ui/description "The article URL to fetch and use as the email body."
            :name "url"
            :type "url"
            :required true
            :placeholder "https://example.com/article"})
          (ui/button {:type "submit"
                      :ui/type :primary}
                     "Fetch & Send Email")]]
        [:div {:id result-id :class "mt-4"}])))))

(fx/defroute-pathom page-route "/admin/email-test"
  [:app.shell/app-shell]

  :get
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :email-test)
     (ui/lazy-load (href page-content-route)))))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            page-content-route
            send-email-route]})
