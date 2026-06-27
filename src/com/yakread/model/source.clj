(ns com.yakread.model.source
  (:require [com.biffweb.graph :as biff.graph :refer [defresolver]]))

(defresolver source-title
  {:input [[:? :sub/title]
           [:? :feed/title]]
   :output [:source/title]}
  [_ {sub-title :sub/title feed-title :feed/title :as params}]
  (some->> (or sub-title feed-title)
           (hash-map :source/title)))

(def module
  {:biff.graph/resolvers [source-title]})
