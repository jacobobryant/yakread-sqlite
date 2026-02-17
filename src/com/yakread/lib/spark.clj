(ns com.yakread.lib.spark
  (:require
   [clojure.set]
   [clojure.tools.logging :as log]
   [com.biffweb :refer [q]]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.connect.operation :as pco :refer [? defresolver]]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [com.yakread.lib.ads :as lib.ads]
   [com.yakread.util.biff-staging :as biffs]
   [honey.sql.helpers]
   [tick.core :as tick])
  (:import
   [java.time Instant]
   [org.apache.spark.api.java JavaSparkContext]
   [org.apache.spark.mllib.recommendation ALS Rating]))

(defn- median [xs]
  (first (take (/ (count xs) 2) xs)))

(defresolver item-candidates [{:keys [biff/conn*]} _]
  {::pco/output [{::item-candidates [:xt/id
                                     :item/url]}]}
  {::item-candidates
   (->> (biffs/q conn*
                 {:select [:item/id :item/url]
                  :from :item
                  :where [:= :item/direct-candidate-status [:lift :item.direct-candidate-status/approved]]})
        (mapv #(clojure.set/rename-keys % {:item/id :xt/id})))})


(defresolver ads [{:biff/keys [conn* now]} _]
  {::pco/output [{::all-ads [:xt/id]}
                 {::ad-candidates [:xt/id]}]}
  (let [all-ads (into []
                      (comp (map #(clojure.set/rename-keys % {:ad/id :xt/id
                                                              :recent-cost :ad/recent-cost}))
                            (remove (comp nil? :xt/id)))
                      (biffs/q conn*
                               {:select [:ad/id
                                         :ad/approve-state
                                         :ad/paused
                                         :ad/payment-failed
                                         :ad/payment-method
                                         :ad/budget
                                         [[:coalesce [:sum :ad-click/cost] 0] :recent-cost]]
                                :from :ad
                                :left-join [:ad-click [:and
                                                       [:= :ad-click/ad-id :ad/id]
                                                       [:<
                                                        (tick/<< now (tick/of-days 7))
                                                        :ad-click/created-at]]]
                                :group-by [:ad/id]}))]
    {::all-ads all-ads
     ::ad-candidates (filterv lib.ads/active? all-ads)}))

(defn- full-outer-join [on records]
  (->> (group-by on records)
       vals
       (mapv #(apply merge %))))

(defn ad-interaction-info [conn*]
  (->> {:union [{:select [[:ad/id :ad-id]
                          [:reclist/user-id :user-id]
                          [[:count :skip/id] :n-skips]
                          [[:max :reclist/created-at] :last-skipped]
                          [nil :last-clicked]]
                 :from :ad
                 :join [:skip [:= :skip/item-id :ad/id]
                        :reclist [:= :skip/reclist-id :reclist/id]]}
                {:select [[:ad/id :ad-id]
                          [:ad-click/user-id :user-id]
                          [nil :n-skips]
                          [nil :last-skipped]
                          [[:max :ad-click/created-at] :last-clicked]]
                 :from :ad
                 :join [:ad-click [:= :ad/id :ad-click/ad-id]]}]}
       (biffs/q conn*)
       (remove (comp nil? :ad-id))
       (full-outer-join (juxt :ad-id :user-id))))

(defresolver ad-ratings [{:keys [biff/conn*]} {::keys [all-ads]}]
  {::pco/input [{::all-ads [:xt/id]}]
   ::pco/output [{::ad-ratings [:rating/candidate
                                :rating/user
                                :rating/value
                                :rating/created-at]}]}
  {::ad-ratings (vec
                 (for [{:keys [ad-id user-id n-skips last-skipped last-clicked]}
                       (ad-interaction-info conn*)]
                   {:rating/candidate ad-id
                    :rating/user user-id
                    :rating/value (if last-clicked
                                    0.75
                                    (max 0 (- 0.5 (* 0.1 n-skips))))
                    :rating/created-at (or last-clicked last-skipped)}))})

(defresolver dedupe-item-id [{:keys [biff/conn*]} {::keys [item-candidates]}]
  {::pco/input [{::item-candidates [:xt/id
                                    :item/url]}]
   ::pco/output [::dedupe-item-id]}
  (let [candidate-urls (not-empty (mapv :item/url item-candidates))
        item-id->url (into {}
                           (map (juxt :item/id :item/url))
                           (when candidate-urls
                             (biffs/q conn*
                                      {:select [:item/id :item/url]
                                       :from :item
                                       :where [:in :item/url candidate-urls]})))
        url->item-candidate-id (into {}
                                     (map (juxt :item/url :xt/id))
                                     item-candidates)]
    {::dedupe-item-id (update-vals item-id->url url->item-candidate-id)}))

(defresolver item-ratings [{:keys [biff/conn*]} {::keys [item-candidates dedupe-item-id]}]
  {::pco/input [{::item-candidates [:xt/id
                                    :item/url]}
                ::dedupe-item-id]
   ::pco/output [{::item-ratings [:rating/user
                                  :rating/candidate
                                  :rating/value
                                  :rating/created-at]}]}
  (let [candidate-urls (not-empty (mapv :item/url item-candidates))
        all-item-ids (when candidate-urls
                       (not-empty
                        (mapv :item/id
                              (biffs/q conn*
                                       {:select :item/id
                                        :from :item
                                        :where [:in :item/url candidate-urls]}))))


        dedupe-usit (fn [usit]
                      (update usit :usit-item dedupe-item-id))
        usit-key (juxt :usit-user :usit-item)
        item-usits (->> (when all-item-ids
                          (biffs/q conn*
                                   {:select [[:user-item/user-id :usit-user]
                                             [:user-item/item-id :usit-item]
                                             :user-item/favorited-at
                                             :user-item/disliked-at
                                             :user-item/reported-at
                                             :user-item/viewed-at
                                             :user-item/bookmarked-at]
                                    :from :user-item
                                    :where [:in :user-item/item-id all-item-ids]}))
                        (mapv dedupe-usit)
                        (group-by usit-key)
                        (vals)
                        (mapv #(apply merge %)))
        merge-skips (fn [a b]
                      (cond
                        (number? a) (+ a b)
                        (tick/zoned-date-time? a) (tick/max a b)
                        :else b))
        skip-usits (->> (when all-item-ids
                          (biffs/q conn*
                                   {:select [[:reclist/user-id :usit-user]
                                             [:skip/item-id :usit-item]
                                             [[:count :skip/id] :usit-skips]
                                             [[:max :reclist/created-at] :skipped-at]]
                                    :from :skip
                                    :join [:reclist [:= :reclist/id :skip/reclist-id]]
                                    :where [:in :skip/item-id all-item-ids]}))
                        (mapv #(-> %
                                   (assoc :user-item/skipped-at (:skipped-at %))
                                   (dissoc :skipped-at)))
                        (mapv dedupe-usit)
                        (group-by usit-key)
                        (vals)
                        (mapv (fn [usits]
                                (apply merge-with merge-skips usits))))
        combined-usits (vals (merge-with merge
                                         (into {} (map (juxt usit-key identity) item-usits))
                                         (into {} (map (juxt usit-key identity) skip-usits))))]
    {::item-ratings
     (vec (for [usit combined-usits
                :let [[created-at value]
                      (some (fn [[k value]]
                              (when-some [t (k usit)]
                                [t value]))
                            [[:user-item/favorited-at 1]
                             [:user-item/disliked-at 0]
                             [:user-item/reported-at 0]
                             [:user-item/viewed-at 0.75]
                             [:user-item/skipped-at (-> 0.5
                                                        (- (* 0.1 (:usit-skips usit 0)))
                                                        (max 0))]
                             [:user-item/bookmarked-at 0.6]])]
                :when value]
            {:rating/user (:usit-user usit)
             :rating/candidate (:usit-item usit)
             :rating/value value
             :rating/created-at created-at}))}))

(defresolver spark-model [{:keys [yakread/spark]}
                          {::keys [item-ratings ad-ratings]}]
  {::pco/input [{::item-ratings [:rating/user
                                 :rating/candidate
                                 :rating/value]}
                {::ad-ratings [:rating/user
                               :rating/candidate
                               :rating/value]}]
   ::pco/output [::predict-fn]}
  (let [all-ratings (concat item-ratings ad-ratings)
        [[index->candidate candidate->index]
         [_ user->index]] (for [k [:rating/candidate :rating/user]
                                :let [index->x (->> all-ratings
                                                    (mapv k)
                                                    distinct
                                                    (map-indexed vector)
                                                    (into {}))]]
                            [index->x (into {} (map (fn [[k v]] [v k])) index->x)])
        spark-ratings (->> (for [{:rating/keys [user candidate value]} all-ratings]
                             (Rating. (int (user->index user))
                                      (int (candidate->index candidate))
                                      (double value)))
                           (.parallelize spark)
                           (.rdd)
                           (.cache))
        rank 10
        iterations 20
        lambda 0.1
        alpha 0.05
        _ (log/info "training ALS")
        als (when (not-empty all-ratings)
              (ALS/trainImplicit spark-ratings rank iterations lambda alpha))
        _ (log/info "done training ALS")]
    {::predict-fn (fn [user-id]
                    (let [candidate->score
                          (into {}
                                (map (fn [^Rating rating]
                                       [(index->candidate (.product rating))
                                        (.rating rating)]))
                                (when-let [user-idx (and als (user->index user-id))]
                                  (.recommendProducts als user-idx (count index->candidate))))]
                      (fn [candidate-id _candidate-type]
                        (or (candidate->score candidate-id)
                            0.1))))}))

(defresolver get-candidates [{::keys [item-ratings
                                      ad-ratings
                                      item-candidates
                                      ad-candidates
                                      predict-fn]}]
  {::pco/input [{::item-ratings [:rating/candidate
                                 :rating/user
                                 :rating/value
                                 :rating/created-at]}
                {::ad-ratings [:rating/candidate
                               :rating/user
                               :rating/value
                               :rating/created-at]}
                {::item-candidates [:xt/id]}
                {::ad-candidates [:xt/id]}
                ::predict-fn]
   ::pco/output [:yakread.model/get-candidates]}
  (let [all-ratings (concat item-ratings ad-ratings)
        candidate->ratings (group-by :rating/candidate all-ratings)
        candidate->n-ratings (update-vals candidate->ratings count)
        candidate->last-liked (update-vals candidate->ratings
                                           (fn [ratings]
                                             (->> ratings
                                                  (keep (fn [{:rating/keys [value created-at]}]
                                                          (when (< 0.5 value)
                                                            created-at)))
                                                  (apply max-key
                                                         inst-ms
                                                         (Instant/ofEpochMilli 0)))))]
    (log/info "done")
    {:yakread.model/get-candidates
     (fn [user-id]
       (let [predict (predict-fn user-id)]
         (into {}
               (for [[type* candidates] [[:item item-candidates]
                                         [:ad ad-candidates]]]
                 [type*
                  (->> (for [{:keys [xt/id]} candidates]
                         {:xt/id id
                          :candidate/type type*
                          :candidate/score (predict id type*)
                          :candidate/last-liked (get candidate->last-liked
                                                     id
                                                     (Instant/ofEpochMilli 0))
                          :candidate/n-ratings (get candidate->n-ratings id 0)})
                       (sort-by (juxt :candidate/n-ratings
                                      (comp - inst-ms :candidate/last-liked)))
                       vec)]))))}))

(defresolver item-candidate-ids [{::keys [item-candidates]}]
  {:yakread.model/item-candidate-ids (into #{} (map :xt/id) item-candidates)})

(defresolver all-liked-items [{:keys [biff/conn*]} _]
  {::pco/output [{:yakread.model/all-liked-items
                  [:item/id :item/n-likes]}]}
  {:yakread.model/all-liked-items
   (into []
         (comp (map #(assoc % :item/id (:id %) :item/n-likes (:n-likes %)))
               (remove (comp nil? :item/id)))
         (biffs/q conn*
                  {:select [[:user-item/item-id :id]
                            [[:count :user-item/id] :n-likes]]
                   :from :user-item
                   :where [:is-not :user-item/favorited-at nil]
                   :order-by [[:n-likes :desc]]}))})

(def ^:private pathom-env (pci/register [item-candidates
                                         ads
                                         ad-ratings
                                         dedupe-item-id
                                         item-ratings
                                         spark-model
                                         get-candidates
                                         item-candidate-ids
                                         all-liked-items]))

(defn new-model [ctx]
  (log/info "updating model")
  (merge {:yakread.model/item-candidate-ids #{}
          :yakread.model/get-candidates (constantly {})}
         (p.eql/process (merge ctx pathom-env {:biff/now (tick/zoned-date-time)})
                        {}
                        [(? :yakread.model/item-candidate-ids)
                         (? :yakread.model/get-candidates)
                         {:yakread.model/all-liked-items [:item/id :item/n-likes]}])))

(defn use-spark [ctx]
  (let [spark (doto (JavaSparkContext. "local[*]" "yakread")
                (.setCheckpointDir "storage/spark-checkpoint"))
        ctx (assoc ctx :yakread/spark spark)]
    (-> ctx
        (assoc :yakread/model (atom (new-model ctx)))
        (update :biff/stop conj #(.close spark)))))

(comment
  (-> @(:yakread/model @com.yakread/system)
      :yakread.model/get-candidates
      )

  (require 'repl)
  (time (do (new-model (repl/context))
          :done))

  (time (do (use-spark (repl/context))
          :done))

  (do (reset! (:yakread/model (repl/context))
              (new-model (repl/context)))
    :done)

  )
