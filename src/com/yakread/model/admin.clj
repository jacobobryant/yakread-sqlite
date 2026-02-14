(ns com.yakread.model.admin 
  (:require
   [clojure.set]
   [com.yakread.util.biff-staging :as biffs]
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]])
  (:import
   [java.time ZoneId]))

(defresolver recent-users [{:biff/keys [conn* now]} _]
  {::pco/output [{:admin/recent-users [:xt/id]}]}
  {:admin/recent-users
   (->> (biffs/q conn*
                 {:select :user/id
                  :from :user
                  :where [:<= (.minusSeconds now (* 60 60 24 7)) :user/joined-at]})
        (mapv #(clojure.set/rename-keys % {:user/id :xt/id})))})

(defresolver dau [{:biff/keys [conn* now]} _]
  {:admin/dau
   (->> (biffs/q conn*
                 {:select :user-item/viewed-at
                  :from :user-item
                  :where [:<= (.minusSeconds now (* 60 60 24 30)) :user-item/viewed-at]})
        (mapv (fn [{:keys [user-item/viewed-at]}]
                (.. viewed-at
                    (atZone (ZoneId/of "America/Denver"))
                    (toLocalDate))))
        frequencies)})

(defresolver revenue [{:biff/keys [conn* now]} _]
  {:admin/revenue
   (->> (biffs/q conn*
                 {:select [:ad-click/created-at :ad-click/cost]
                  :from :ad-click
                  :where [:<= (.minusSeconds now (* 60 60 24 30)) :ad-click/created-at]})
        (reduce (fn [acc [cost t]]
                  (let [date (.. t
                                 (atZone (ZoneId/of "America/Denver"))
                                 (toLocalDate))]
                    (update acc date (fnil + 0) cost)))
                {}))})

(defresolver ads [{:biff/keys [conn*]} _]
  {::pco/output [{:admin/ads [:xt/id]}]}
  {:admin/ads (->> (biffs/q conn* {:select :ad/id :from :ad})
                   (mapv #(clojure.set/rename-keys % {:ad/id :xt/id})))})

(def module
  {:resolvers [recent-users
               dau
               revenue
               ads]})
