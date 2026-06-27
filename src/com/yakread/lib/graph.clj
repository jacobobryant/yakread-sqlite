(ns com.yakread.lib.graph
  (:require
   [clojure.data.generators :as gen]
   [com.biffweb :as biff]
   [com.biffweb.graph :as biff.graph]
   [com.yakread.lib.error :as lib.error]
   [taoensso.tufte :refer [p]]))

(defn normalize-query [query]
  (letfn [(norm-item [item]
            (cond
              (and (vector? item)
                   (= :? (first item)))
              [:? (norm-item (second item))]

              (map? item)
              (let [[k v] (first item)]
                (if (and (vector? k)
                         (= :? (first k)))
                  {[:? (second k)] (normalize-query v)}
                  {k (normalize-query v)}))

              :else
              item))]
    (mapv norm-item query)))

(defn handler [query f]
  (fn handler*
    ([request]
     (let [extra {:biff/now (java.time.Instant/now)
                  :biff/seed (long (* (rand) Long/MAX_VALUE))}
           request (merge request extra)]
       (binding [gen/*rnd* (java.util.Random. (:biff/seed extra))]
         (lib.error/with-ex-data (merge (lib.error/request-ex-data request) extra)
           (handler* request (biff.graph/query request (normalize-query query)))))))
    ([request input]
     (f request input))))

(defn wrap-debug [{:biff.graph/keys [id resolve-fn] :as resolver}]
  (let [debug (:biff/debug resolver)]
    (when debug
      (println ":biff/debug set for" id))
    (assoc resolver
           :biff.graph/resolve-fn
           (fn [ctx]
             (let [input (:biff.graph/input ctx)]
               (if (or (not debug)
                       (and (fn? debug) (not (debug ctx input))))
                 (p id (resolve-fn ctx))
                 (do
                   (println id)
                   (biff/pprint input)
                   (println "=>")
                   (let [ret (p id (resolve-fn (assoc ctx :biff/debug true)))]
                     (biff/pprint ret)
                     (println)
                     ret))))))))

(defn query [ctx input]
  (let [{:keys [entity query]} (if (map? input)
                                 input
                                 {:query input})]
    (biff.graph/query ctx (or entity {}) (normalize-query query))))
