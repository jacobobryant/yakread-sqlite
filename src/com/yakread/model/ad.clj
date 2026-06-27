(ns com.yakread.model.ad
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.biffweb.graph :refer [defresolver]]
   [com.yakread.lib.content :as lib.content]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.lib.fx :as fx]
   [com.yakread.routes :as routes]
   [lambdaisland.uri :as uri]
   [tick.core :as tick]))

;; TODO keep recent-cost updated (or calculate it on the fly if it's fast enough)
(defresolver effective-bid
  {:input [:ad/bid
           :ad/budget
           :ad/recent-cost]
   :output [:ad/effective-bid]}
  [_ {:ad/keys [bid budget recent-cost]}]
  {:ad/effective-bid (min bid (max 0 (- budget recent-cost)))})

(defresolver ad-id
  {:input [:xt/id
           :ad/user]
   :output [:ad/id]}
  [_ {:keys [xt/id ad/user]}]
  {:ad/id id})

(defresolver xt-id
  {:input [:ad/id]
   :output [:xt/id]}
  [_ {:keys [ad/id]}]
  {:xt/id id})

(defresolver user-ad
  {:input [:user/id]
   :output [{:user/ad [:ad/id]}]}
  [{:biff/keys [query]} {:keys [user/id]}]
  (when-some [ad (first (query {:select :ad/id
                                :from :ad
                                :where [:= :ad/user-id id]}))]
    {:user/ad ad}))

(defresolver url-with-protocol
  {:input [:ad/url]
   :output [:ad/url-with-protocol]}
  [_ {:keys [ad/url]}]
  {:ad/url-with-protocol (lib.content/add-protocol url)})

(defresolver recording-url
  {:input [:ad/id
           :ad/url-with-protocol
           :ad/click-cost]
   :output [:ad/recording-url]}
  [{:biff/keys [base-url href-safe]}
   {:ad/keys [id url-with-protocol click-cost]}]
  {:ad/recording-url
   (fn [{:keys [params :ad.click/source]
         user-id :user/id}]
     (str base-url
          (href-safe routes/click-ad
                     (merge params
                            {:action :action/click-ad
                             :ad/id id
                             :ad/url url-with-protocol
                             :ad/click-cost click-cost
                             :ad.click/source source
                             :user/id user-id}))))})

(def ^:private required-fields
  [:ad/payment-method
   :ad/bid
   :ad/budget
   :ad/url
   :ad/title
   :ad/description
   :ad/image-url])

(defresolver state
  {:input (into [[:? :ad/paused]
                 [:? :ad/payment-failed]
                 :ad/approve-state]
                (mapv #(vector :? %) required-fields))
   :output [:ad/state
            :ad/incomplete-fields]}
  [_ {:ad/keys [paused
                payment-failed
                approve-state]
      :as ad}]
  (let [incomplete-fields (remove (comp lib.core/something? ad) required-fields)]
    {:ad/state (cond
                 payment-failed :payment-failed
                 paused :paused
                 (not-empty incomplete-fields) :incomplete
                 (= approve-state :ad.approve-state/approved) :running
                 (= approve-state :ad.approve-state/rejected) :rejected
                 (= approve-state :ad.approve-state/pending) :pending)
     :ad/incomplete-fields incomplete-fields}))

(defresolver n-clicks
  {:input [:ad/id]
   :output [:ad/n-clicks]}
  [{:biff/keys [query]} {:keys [ad/id]}]
  {:ad/n-clicks
   (-> (query {:select [[[:count [:distinct :ad-click/user-id]] :cnt]]
               :from :ad-click
               :where [:= :ad-click/ad-id id]})
       first
       :cnt)})

(defresolver host
  {:input [:ad/url-with-protocol]
   :output [:ad/host]}
  [_ {:keys [ad/url-with-protocol]}]
  {:ad/host (some-> url-with-protocol uri/uri :host str/trim not-empty)})

(defresolver last-clicked
  {:input [:ad/id]
   :output [:ad/last-clicked]
   :batch true}
  [{:biff/keys [query]} ads]
  (->> (query {:select [[:ad-click/ad-id :ad/id]
                        [[:max :ad-click/created-at] :ad-click/created-at]]
               :from :ad-click
               :where [:in :ad-click/ad-id (mapv :ad/id ads)]
               :group-by :ad-click/ad-id})
       (mapv #(set/rename-keys % {:ad-click/created-at :ad/last-clicked}))
       (lib.core/restore-order ads :ad/id)))

(defresolver amount-pending
  {:input [:ad/id]
   :output [:ad/amount-pending]
   :batch true}
  [{:biff/keys [query]} ads]
  (->> (query {:select [[:ad-credit/ad-id :ad/id]
                        [[:sum :ad-credit/amount] :ad/amount-pending]]
               :from :ad-credit
               :where [:and
                       [:in :ad-credit/ad-id (mapv :ad/id ads)]
                       [:= :ad-credit/charge-status [:lift :ad-credit.charge-status/pending]]]
               :group-by :ad-credit/ad-id})
       (lib.core/restore-order ads :ad/id)))

(defresolver chargeable
  {:input [[:? :ad/payment-method]
           [:? :ad/payment-failed]
           :ad/balance
           [:? :ad/amount-pending]
           [:? :ad/paused]
           [:? :ad/last-clicked]
                ;; ensure these are set / user account hasn't been deleted
           :ad/customer-id
           {:ad/user [:user/email]}]
   :output [:ad/chargeable]}
  [{:keys [biff/now]} {:ad/keys [payment-method
                                 payment-failed
                                 balance
                                 amount-pending
                                 paused
                                 last-clicked]}]
  {:ad/chargeable
   (boolean
    (and payment-method
         (not payment-failed)
         (not amount-pending)
         last-clicked
         (< (tick/between last-clicked now :days) 60)
         (<= (if paused 50 500) balance)))})

(fx/defmachine get-stripe-status
  :start
  (fn [{:keys [biff/secret ::credit]}]
    (let [{:ad-credit/keys [ad created-at]} credit
          {:ad/keys [customer-id]} ad]
      {:biff.fx/http [:biff.fx/http
                      {:method :get
                       :url "https://api.stripe.com/v1/payment_intents"
                       :basic-auth [(secret :stripe/api-key) ""]
                       :flatten-nested-form-params true
                       :as :json
                       :query-params {:limit 100
                                      :customer customer-id
                                      :created {:gt (-> created-at
                                                        tick/instant
                                                        inst-ms
                                                        (quot 1000)
                                                        str)}}}]
       :biff.fx/next :end}))

  :end
  (fn [{:keys [::credit]
        http :biff.fx/http}]
    (when-some [status (some (fn [{:keys [metadata status]}]
                               (when (= (str (:ad-credit/id credit))
                                        (:charge_id metadata))
                                 status))
                             (get-in http [:body :data]))]
      {:ad-credit/stripe-status status})))

(defresolver stripe-status
  {:input [:ad-credit/id
           {:ad-credit/ad [:ad/customer-id]}
           :ad-credit/created-at
           :ad-credit/charge-status]
   :output [:ad-credit/stripe-status]}
  [ctx {:ad-credit/keys [charge-status] :as credit}]
  (when (= charge-status :ad-credit.charge-status/pending)
    (get-stripe-status (assoc ctx ::credit credit))))

(defresolver pending-charge
  {:input [:ad/id]
   :output [{:ad/pending-charge [:ad-credit/id]}]}
  [{:biff/keys [query]} {:keys [ad/id]}]
  (when-some [credit (first (query {:select :ad-credit/id
                                    :from :ad-credit
                                    :where [:and
                                            [:= :ad-credit/ad-id id]
                                            [:= :ad-credit/charge-status [:lift :ad-credit.charge-status/pending]]]}))]
    {:ad/pending-charge credit}))

(defresolver pending-charges
  {:output [{:admin/pending-charges [:ad-credit/id]}]}
  [{:biff/keys [query]} _]
  {:admin/pending-charges
   (query {:select :ad-credit/id
           :from :ad-credit
           :where [:= :ad-credit/charge-status [:lift :ad-credit.charge-status/pending]]})})

(def module {:biff.graph/resolvers [ad-id
                                    xt-id
                                    effective-bid
                                    user-ad
                                    url-with-protocol
                                    recording-url
                                    state
                                    n-clicks
                                    host
                                    amount-pending
                                    chargeable
                                    stripe-status
                                    pending-charge
                                    pending-charges
                                    last-clicked]})
