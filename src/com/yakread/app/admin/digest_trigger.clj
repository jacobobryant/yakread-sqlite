(ns com.yakread.app.admin.digest-trigger
  (:require
   [clojure.pprint :as pprint]
   [com.biffweb :as biff]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.work.digest :as digest]
   [rum.core :as rum]))

(declare page-route send-single-route preview-route queue-route)

(fx/defroute send-single-route "/admin/digest-trigger/send-single"
  :post
  (fn [{:keys [biff/query params] :as ctx}]
    (let [email (some-> (:email params) clojure.string/trim not-empty)
          user (when email
                 (first (query {:select [:user/id :user/email]
                                :from :user
                                :where [:= :user/email email]})))]
      {:status 200
       :headers {"content-type" "text/html"}
       :body (rum/render-static-markup
              (cond
                (nil? email)
                (ui/callout {:ui/type :error} "Email address is required.")

                (nil? user)
                (ui/callout {:ui/type :error} (str "No user found with email: " email))

                :else
                (do
                  (biff/submit-job ctx :work.digest/prepare-digest user)
                  (ui/callout {:ui/type :info}
                              (str "Queued prepare-digest job for " (:user/email user))))))})))

(defn- mock-queues []
  {:work.digest/prepare-digest (java.util.concurrent.LinkedBlockingQueue.)})

(fx/defroute preview-route "/admin/digest-trigger/preview"
  :post
  (fn [{:keys [params] :as ctx}]
    (let [job-limit (some-> (:job-limit params) not-empty parse-long)
          result (digest/queue-prepare-digest
                  (merge ctx
                         {:yakread.work.digest/enabled true
                          :yakread.work.digest/job-limit job-limit
                          :biff/queues (mock-queues)})
                  :start)
          users (when result
                  (->> (get-in result [:biff.fx/queue :jobs])
                       (mapv second)))]
      {:status 200
       :headers {"content-type" "text/html"}
       :body (rum/render-static-markup
              (if (empty? users)
                (ui/callout {:ui/type :info} "No users eligible for digest right now.")
                [:div
                 (ui/callout {:ui/type :info} (str (count users) " user(s) eligible for digest:"))
                 [:pre.mt-4.p-4.bg-neut-100.rounded.text-sm.overflow-x-auto.max-h-96.overflow-y-auto
                  (with-out-str (pprint/pprint users))]]))})))

(fx/defroute queue-route "/admin/digest-trigger/queue"
  :post
  (fn [{:keys [params] :as ctx}]
    (let [job-limit (some-> (:job-limit params) not-empty parse-long)
          result (digest/queue-prepare-digest
                  (merge ctx
                         {:yakread.work.digest/enabled true
                          :yakread.work.digest/job-limit job-limit
                          :biff/queues (mock-queues)})
                  :start)
          users (when result
                  (->> (get-in result [:biff.fx/queue :jobs])
                       (mapv second)))]
      (doseq [user users]
        (biff/submit-job ctx :work.digest/prepare-digest user))
      {:status 200
       :headers {"content-type" "text/html"}
       :body (rum/render-static-markup
              (if (empty? users)
                (ui/callout {:ui/type :info} "No users eligible for digest right now.")
                (ui/callout {:ui/type :info}
                            (str "Queued " (count users) " prepare-digest job(s)."))))})))

(fx/defroute-pathom page-content-route "/admin/digest-trigger/content"
  []

  :get
  (fn [_ _]
    (let [single-result-id (ui/dom-id ::single-result)
          preview-result-id (ui/dom-id ::preview-result)
          queue-result-id (ui/dom-id ::queue-result)]
      (ui/wide-page-well
       (ui/section
        {:title "Send to Single User"}
        [:p.text-sm.text-neut-600
         "Queue a prepare-digest job for a single user by email address."]
        [:form {:hx-post (href send-single-route)
                :hx-target (str "#" single-result-id)
                :hx-swap "innerHTML"}
         [:.flex.flex-col.gap-4
          (ui/form-input
           {:ui/label "Email"
            :name "email"
            :type "email"
            :required true
            :placeholder "user@example.com"})
          (ui/button {:type "submit"
                      :ui/type :primary}
                     "Queue Digest")]]
        [:div {:id single-result-id :class "mt-4"}])

       (ui/section
        {:title "Preview Eligible Users"}
        [:p.text-sm.text-neut-600
         "Show which users would receive a digest right now (dry run, no jobs queued)."]
        [:form {:hx-post (href preview-route)
                :hx-target (str "#" preview-result-id)
                :hx-swap "innerHTML"}
         [:.flex.flex-col.gap-4
          (ui/form-input
           {:ui/label "Job Limit"
            :ui/description "Maximum number of users. Leave empty for no limit."
            :name "job-limit"
            :type "number"
            :value "10"
            :min "1"})
          (ui/button {:type "submit"
                      :ui/type :primary}
                     "Preview Users")]]
        [:div {:id preview-result-id :class "mt-4"}])

       (ui/section
        {:title "Queue All Eligible Digests"}
        [:p.text-sm.text-neut-600
         "Actually queue prepare-digest jobs for all eligible users. This will send real emails!"]
        [:form {:hx-post (href queue-route)
                :hx-target (str "#" queue-result-id)
                :hx-swap "innerHTML"}
         [:.flex.flex-col.gap-4
          (ui/form-input
           {:ui/label "Job Limit"
            :ui/description "Maximum number of users. Leave empty for no limit."
            :name "job-limit"
            :type "number"
            :value "10"
            :min "1"})
          (ui/button {:type "submit"
                      :ui/type :primary
                      :hx-confirm "Are you sure? This will queue real digest jobs that send actual emails."}
                     "Queue Digests")]]
        [:div {:id queue-result-id :class "mt-4"}])))))

(fx/defroute-pathom page-route "/admin/digest-trigger"
  [:app.shell/app-shell]

  :get
  (fn [_ {:keys [app.shell/app-shell]}]
    (app-shell
     {:wide true}
     (ui/page-header {:title "Admin"})
     (lib/navbar :digest-trigger)
     (ui/lazy-load (href page-content-route)))))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            page-content-route
            send-single-route
            preview-route
            queue-route]})
