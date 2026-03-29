(ns com.yakread.app.admin.impersonate
  (:require
   [com.biffweb :as biff]
   [com.yakread.lib.admin :as lib]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.route :as lib.route :refer [href]]
   [com.yakread.lib.ui :as ui]))

(declare page-route)
(declare impersonate-route)

(def per-page 200)

(fx/defroute search-route "/admin/impersonate/search"
  :get
  (fn [{:biff/keys [query] :keys [params]}]
    (let [q (or (:q params) "")
          page-num (max 1 (parse-long (or (:page params) "1")))
          offset (* (dec page-num) per-page)
          users (when (seq q)
                  (query {:select [:user/id :user/email :user/joined-at]
                          :from :user
                          :where [:like :user/email (str "%" q "%")]
                          :order-by [[:user/email :asc]]
                          :limit (inc per-page)
                          :offset offset}))
          has-next? (> (count users) per-page)
          users (take per-page users)]
      [:div
       (if (empty? users)
         (if (seq q)
           [:p.text-neut-600.text-sm "No users found matching \"" q "\"."]
           [:p.text-neut-600.text-sm "Enter a search term above."])
         [:<>
          [:p.text-sm.text-neut-600.mb-4
           (str "Page " page-num
                (when has-next? (str " · showing " per-page " results"))
                (when (and (not has-next?) (seq users))
                  (str " · " (count users) " result" (when (not= 1 (count users)) "s"))))]
          (ui/table
           ["Email" "Joined" ""]
           (for [{:user/keys [id email joined-at]} users]
             [email
              (if joined-at
                (lib.core/fmt-inst joined-at "yyyy-MM-dd" "America/Denver")
                "—")
              (biff/form
               {:action (href impersonate-route)
                :class "inline"}
               [:input {:type "hidden" :name "user-id" :value (str id)}]
               (ui/button {:type "submit"
                           :ui/type :link
                           :ui/size :small}
                          "Sign in as"))]))
          (when (or (> page-num 1) has-next?)
            [:.flex.gap-4.mt-4
             (when (> page-num 1)
               (ui/button {:href (str (href search-route) "?q=" q "&page=" (dec page-num))
                           :ui/type :secondary
                           :ui/size :small}
                          "← Previous"))
             [:.grow]
             (when has-next?
               (ui/button {:href (str (href search-route) "?q=" q "&page=" (inc page-num))
                           :ui/type :secondary
                           :ui/size :small}
                          "Next →"))])])])))

(fx/defroute impersonate-route "/admin/impersonate/sign-in-as"
  :post
  (fn [{:biff/keys [query] :keys [params session]}]
    (let [user-id (parse-uuid (:user-id params))
          user (when user-id
                 (first (query {:select [:user/id]
                                :from :user
                                :where [:= :user/id user-id]
                                :limit 1})))]
      (if user
        {:status 303
         :headers {"location" "/app"}
         :session (assoc session :uid (:user/id user))}
        {:status 303
         :headers {"location" (href page-route)}}))))

(fx/defroute-pathom page-route "/admin/impersonate"
  [:app.shell/app-shell]

  :get
  (fn [{:keys [params]} {:keys [app.shell/app-shell]}]
    (let [q (or (:q params) "")]
      (app-shell
       {:wide true}
       (ui/page-header {:title "Admin"})
       (lib/navbar :impersonate)
       (ui/wide-page-well
        (ui/section
         {:title "Search subscribers"}
         [:form {:hx-get (href search-route)
                 :hx-target "#search-results"
                 :hx-swap "innerHTML"
                 :class "flex gap-3 items-end"}
          [:.grow
           (ui/text-input {:name "q"
                           :placeholder "Search by email..."
                           :value q})]
          (ui/button {:type "submit"
                      :ui/type :primary}
                     "Search")]
         [:div#search-results.mt-4]))))))

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            page-route
            search-route
            impersonate-route]})
