(ns com.yakread.lib.sqlite
  (:require
   [clojure.string :as str]
   [com.biffweb.sqlite :as biff.sqlite]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.yakread.lib.core :as lib.core]
   [com.yakread.model.schema :as sqlite-schema]))

(defn- table-id-key
  "Get the ID key for a table (e.g., :user -> :user/id)"
  [table-key]
  (keyword (name table-key) "id"))

;; ============================================================================
;; SQLite Pathom Resolvers
;; ============================================================================

(defn- strip-id-suffix
  "Remove -id suffix from an attribute name: :ad/user-id -> :ad/user"
  [attr]
  (let [ns (namespace attr)
        n (name attr)]
    (if (str/ends-with? n "-id")
      (keyword ns (subs n 0 (- (count n) 3)))
      attr)))

(defn- ref-target
  "Get the target table for a reference column."
  [col-props]
  (when-let [ref (:ref col-props)]
    (keyword (namespace ref))))

(defn execute [ctx input]
  (biff.sqlite/execute ctx input))

;; TODO add duplicate keys for backwards compat
(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables from columns map.

   For reference attributes ending in -id:
   - Returns the raw ID as :table/ref-id
   - Also returns a join without the -id suffix as {:ref-table/id uuid}

   Example: {:ad/user-id #uuid \"...\", :ad/user {:user/id #uuid \"...\"}}"
  [columns]
  (let [;; Group columns by table
        tables (group-by #(keyword (namespace (key %))) columns)]
    (for [[table-key table-cols] tables
          :let [id-key (table-id-key table-key)
                cols-map (into {} table-cols)

                ;; Find reference attrs
                ref-attrs (into {}
                                (keep (fn [[attr props]]
                                        (when-let [target (ref-target props)]
                                          [attr target])))
                                cols-map)

                ;; Build output spec
                output (vec (for [k (keys cols-map)
                                  :when (not= k id-key)
                                  :let [is-ref? (contains? ref-attrs k)
                                        target (get ref-attrs k)
                                        target-id-key (when target (table-id-key target))
                                        join-key (when is-ref? (strip-id-suffix k))]]
                              (if is-ref?
                                {join-key [target-id-key]}
                                k)))

                ;; Add the raw -id keys to output
                output (into output (keys ref-attrs))

                op-name (symbol "com.yakread.lib.sqlite"
                                (str (name table-key) "-resolver"))]]
      (pco/resolver op-name
                    {::pco/input [id-key]
                     ::pco/output output
                     ::pco/batch? true}
                    (fn [ctx inputs]
                      (let [ids (mapv id-key inputs)
                            results (execute ctx {:select :*
                                                  :from table-key
                                                  :where [:in :id ids]})

                            ;; Post-process to add join keys
                            process-row
                            (fn [row]
                              (into row
                                    (map (fn [[ref-attr target]]
                                           (let [target-id-key (table-id-key target)
                                                 join-key (strip-id-suffix ref-attr)
                                                 ref-val (get row ref-attr)]
                                             [join-key (when (some? ref-val)
                                                         {target-id-key ref-val})])))
                                    ref-attrs))

                            results (mapv process-row results)
                            id->result (into {} (map (juxt id-key identity)) results)]
                        (mapv (fn [input]
                                (let [id (get input id-key)]
                                  (-> (get id->result id {})
                                      lib.core/some-vals
                                      (assoc id-key id))))
                              inputs)))))))

(defn use-sqlite
  "Biff component that runs schema migrations, starts connection pool,
   and optionally starts litestream replication."
  [ctx]
  (let [ctx (-> ctx
                (assoc :biff.sqlite/columns sqlite-schema/columns)
                biff.sqlite/use-sqlite)]
    (assoc ctx :biff/query (partial execute ctx))))
