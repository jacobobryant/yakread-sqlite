(ns com.yakread.model.feed
  (:require
   [com.biffweb.graph :as biff.graph :refer [defresolver]]))

(defresolver feed-title
  {:input [:feed/url]
   :output [:feed/title]}
  [_ {:keys [feed/url]}]
  {:feed/title url})

(def module
  {:biff.graph/resolvers [feed-title]})
