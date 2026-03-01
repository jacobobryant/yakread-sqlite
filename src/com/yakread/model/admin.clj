(ns com.yakread.model.admin 
  (:require
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]])
  (:import
   [java.time ZoneId]))

(defresolver recent-users [{:biff/keys [query now]} _]
  {::pco/output [{:admin/recent-users [:user/id]}]}
  {:admin/recent-users
   (query {:select :user/id
           :from :user
           :where [:<= (.minusSeconds now (* 60 60 24 7)) :user/joined-at]})})

(defresolver dau [{:biff/keys [query now]} _]
  {:admin/dau
   (->> (query {:select :user-item/viewed-at
                :from :user-item
                :where [:<= (.minusSeconds now (* 60 60 24 30)) :user-item/viewed-at]})
        (mapv (fn [{:keys [user-item/viewed-at]}]
                (.. viewed-at
                    (atZone (ZoneId/of "America/Denver"))
                    (toLocalDate))))
        frequencies)})

(defresolver revenue [{:biff/keys [query now]} _]
  {:admin/revenue
   (->> (query {:select [:ad-click/created-at :ad-click/cost]
                :from :ad-click
                :where [:<= (.minusSeconds now (* 60 60 24 30)) :ad-click/created-at]})
        (reduce (fn [acc {:ad-click/keys [created-at cost]}]
                  (let [date (.. created-at
                                 (atZone (ZoneId/of "America/Denver"))
                                 (toLocalDate))]
                    (update acc date (fnil + 0) cost)))
                {}))})

(defresolver ads [{:biff/keys [query]} _]
  {::pco/output [{:admin/ads [:ad/id]}]}
  {:admin/ads (query {:select :ad/id :from :ad})})

(def module
  {:resolvers [recent-users
               dau
               revenue
               ads]})
