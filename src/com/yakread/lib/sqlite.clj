(ns com.yakread.lib.sqlite
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.biffweb.sqlite :as biff.sqlite]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.yakread.lib.core :as lib.core]))

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
  "Get the target table for a reference attribute."
  [attrs attr]
  (get-in attrs [attr :properties :biff/ref]))

(defn execute [ctx input]
  (-> ctx
      (set/rename-keys {:biff/conn* :biff/conn
                        :biff/malli-opts* :biff/malli-opts})
      (biff.sqlite/execute input)))

;; TODO add duplicate keys for backwards compat
(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables from malli schema.

   For reference attributes ending in -id:
   - Returns the raw ID as :table/ref-id
   - Also returns a join without the -id suffix as {:ref-table/id uuid}

   Example: {:ad/user-id #uuid \"...\", :ad/user {:user/id #uuid \"...\"}}"
  [malli-opts]
  (for [[table-key attrs] (biff.sqlite/schema-info malli-opts)
        :let [id-key (table-id-key table-key)

              ;; Find reference attrs
              ref-attrs (into {}
                              (keep (fn [[attr _]]
                                      (when-let [target (ref-target attrs attr)]
                                        [attr target])))
                              attrs)

              ;; Build output spec
              output (vec (for [k (keys attrs)
                                :when (not= k id-key)
                                :let [is-ref? (contains? ref-attrs k)
                                      target (get ref-attrs k)
                                      target-id-key (when target (table-id-key target))
                                      join-key (when is-ref? (strip-id-suffix k))]]
                            (if is-ref?
                              ;; Return both the raw -id and the join
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
                                                       {target-id-key ref-val})])
                                         ))
                                  ref-attrs))

                          results (mapv process-row results)
                          id->result (into {} (map (juxt id-key identity)) results)]
                      (mapv (fn [input]
                              (let [id (get input id-key)]
                                (-> (get id->result id {})
                                    lib.core/some-vals
                                    (assoc id-key id))))
                            inputs))))))

(defn use-sqlite
  "Biff component that starts a HikariCP connection pool for SQLite
   and puts it in the :biff/conn* key."
  [{:biff/keys [conn malli-opts malli-opts*] :as ctx}]
  (let [ctx (-> ctx
                (assoc :biff/malli-opts malli-opts*)
                biff.sqlite/use-sqlite
                (set/rename-keys {:biff/conn :biff/conn*})
                (assoc :biff/malli-opts malli-opts
                       :biff/conn conn))]
    (assoc ctx :biff/query (partial execute ctx))))
