(ns com.yakread.model.params
  (:require
   [com.biffweb.graph :as biff.graph :refer [defresolver]]))

(defresolver redirect-url
  {:output [:params/redirect-url]}
  [{:keys [com.yakread/sign-redirect params]} {}]
  (let [{:keys [redirect redirect-sig]} params]
    (when (and (some? redirect-sig)
               (= redirect-sig (some-> redirect sign-redirect :redirect-sig)))
      {:params/redirect-url redirect})))

(defresolver paginate-after
  {:output [:params/paginate-after]}
  [{:keys [params]} _]
  (when (uuid? (:after params))
    {:params/paginate-after (:after params)}))

(def module
  {:biff.graph/resolvers [redirect-url
                          paginate-after]})
