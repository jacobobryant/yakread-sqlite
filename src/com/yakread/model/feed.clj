(ns com.yakread.model.feed
  (:require
   [com.wsscode.pathom3.connect.operation :as pco :refer [defresolver]]))

(defresolver feed-id [{:keys [xt/id]}]
  {:feed/id id})

(defresolver xt-id [{:keys [feed/id]}]
  {:xt/id id})

(defresolver feed-title [{:keys [feed/url]}]
  {:feed/title url})

(def module
  {:resolvers [feed-id xt-id feed-title]})
