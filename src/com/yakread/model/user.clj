(ns com.yakread.model.user
  (:require
   [clojure.string :as str]
   [com.biffweb.graph :refer [defresolver]]
   [com.yakread.lib.ui :as ui]
   [com.yakread.lib.user :as lib.user]
   [tick.core :as tick]))

(defresolver session-user
  {:output [{:session/user [:user/id]}]}
  [{:keys [session]} _]
  (when (:uid session)
    {:session/user {:user/id (:uid session)}}))

(defresolver session-anon
  {:output [{:session/anon []}]}
  [{:keys [session]} _]
  (when-not (:uid session)
    {:session/anon {}}))

(defresolver signed-in
  {:output [:session/signed-in]}
  [{:keys [session]} _]
  {:session/signed-in (some? (:uid session))})

;; TODO switch everything to :session/user
(defresolver current-user
  {:output [{:user/current [:user/id]}]}
  [{:keys [session]} _]
  (when (:uid session)
    {:user/current {:user/id (:uid session)}}))

(defresolver suggested-email-username
  {:input [:user/email [:? :user/email-username]]
   :output [:user/suggested-email-username]}
  [{:keys [biff/query]} {:user/keys [email email-username]}]
  (when-not email-username
    (let [suggested (some-> email
                            (str/split #"@")
                            first
                            str/lower-case
                            (str/replace #"(\+.*|yakread)" "")
                            lib.user/normalize-email-username)]
      (when-not (some->> suggested (lib.user/email-username-taken? query))
        {:user/suggested-email-username suggested}))))

(defresolver user-id
  {:input [:xt/id
           :user/email]
   :output [:user/id]}
  [_ {:keys [xt/id user/email]}]
  {:user/id id})

(defresolver xt-id
  {:input [:user/id]
   :output [:xt/id]}
  [_ {:keys [user/id]}]
  {:xt/id id})

(defresolver default-digest-days
  {:input [:user/email]
   :output [:user/digest-days]}
  [_ _]
  {:user/digest-days #{:sunday :monday :tuesday :wednesday :thursday :friday :saturday}})

(defresolver default-send-digest-at
  {:input [:user/email]
   :output [:user/send-digest-at]}
  [_ _]
  {:user/send-digest-at "08:00"})

(defresolver persisted-timezone
  {:input [:user/id]
   :output [:user/timezone*]}
  [{:biff/keys [query]} {:user/keys [id]}]
  (let [user (first (query {:select :user/timezone
                            :from :user
                            :where [:= :user/id id]
                            :limit 1}))]
    {:user/timezone* (:user/timezone user)}))

(defresolver default-timezone
  {:input [:user/id]
   :output [:user/timezone]}
  [{:biff/keys [query]} {:user/keys [id]}]
  {:user/timezone (or (:user/timezone
                       (first (query {:select :user/timezone
                                      :from :user
                                      :where [:= :user/id id]
                                      :limit 1})))
                      "US/Pacific")})

(defresolver premium
  {:input [[:? :user/plan]
           [:? :user/cancel-at]]
   :output [:user/premium]}
  [{:keys [biff/now]} {:user/keys [plan cancel-at]}]
  {:user/premium (boolean
                  (and plan
                       (or (not cancel-at)
                           (tick/<= now cancel-at))))})

(defresolver mv
  {:input [:user/id]
   :output [{:user/mv [:mv-user/id]}]}
  [{:biff/keys [query]} {:user/keys [id]}]
  (when-some [mv-user
              (first (query {:select :mv-user/id
                             :from :mv-user
                             :where [:= :mv-user/user-id id]
                             :limit 1}))]
    {:user/mv mv-user}))

(defresolver account-deletable
  {:input [[:? {:user/ad [:ad/balance]}]
           [:? :user/plan]
           [:? :user/cancel-at]]
   :output [:user/account-deletable
            :user/account-deletable-message]}
  [_ {:user/keys [ad plan cancel-at]}]
  (let [{:ad/keys [balance]} ad]
    (if-some [message (cond
                        (and balance (<= 50 balance))
                        (str "You have an advertising balance of " (ui/fmt-cents balance) ". Please enable "
                             "the \"pause\" setting on your ad and ensure you have a valid payment "
                             "method set. After your account is charged, you may delete your account.")

                        (and plan (not cancel-at))
                        (str "You have an active premium subscription. Please cancel your subscription "
                             "before deleting your account."))]
      {:user/account-deletable false
       :user/account-deletable-message message}
      {:user/account-deletable true})))

(def module {:biff.graph/resolvers [session-user
                                    session-anon
                                    signed-in
                                    current-user
                                    suggested-email-username
                                    user-id
                                    xt-id
                                    default-digest-days
                                    default-send-digest-at
                                    persisted-timezone
                                    default-timezone
                                    premium
                                    account-deletable]})
