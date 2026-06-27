(ns com.yakread.lib.sqlite
  (:require
   [clojure.string :as str]
   [com.biffweb.graph :as biff.graph]
   [com.biffweb.sqlite :as biff.sqlite]))

;; ============================================================================
;; SQLite graph resolvers
;; ============================================================================

(defn- strip-id-suffix
  "Remove -id suffix from an attribute name: :ad/user-id -> :ad/user"
  [attr]
  (let [ns (namespace attr)
        n (name attr)]
    (if (str/ends-with? n "-id")
      (keyword ns (subs n 0 (- (count n) 3)))
      attr)))

(defn make-resolvers
  "Create biff.graph resolvers for SQLite tables from columns map."
  [columns]
  (let [columns  (or columns {})
        by-table (group-by (comp keyword namespace key) columns)]
    (vec
     (for [[table-key table-cols] by-table
           :let [table-cols-map (into {} table-cols)
                 pk-entry (first (filter (fn [[_ props]]
                                           (:primary-key props))
                                         table-cols-map))
                 _ (when-not pk-entry
                     (throw (ex-info (str "No primary key found for table " table-key)
                                     {:table table-key})))
                 pk-key (key pk-entry)
                 non-pk-cols (dissoc table-cols-map pk-key)
                 ref-cols (into {}
                                (keep (fn [[col-key props]]
                                        (when (and (:ref props)
                                                   (str/ends-with? (name col-key) "-id"))
                                          [col-key (:ref props)])))
                                non-pk-cols)
                 join-mappings (mapv (fn [[col-key ref-key]]
                                        {:join-key (strip-id-suffix col-key)
                                         :col-key col-key
                                         :ref-key ref-key})
                                      ref-cols)
                 output (vec (concat (keys non-pk-cols)
                                     (map (fn [{:keys [join-key ref-key]}]
                                            {join-key [ref-key]})
                                          join-mappings)))
                 resolver-id (keyword "com.yakread.lib.sqlite"
                                      (str (name table-key) "-resolver"))]]
       (biff.graph/resolver
        {:id resolver-id
         :input [pk-key]
         :output output
         :batch true
         :resolve-fn (fn [ctx inputs]
                       (let [ids (mapv pk-key inputs)
                             results (biff.sqlite/execute ctx {:select :*
                                                               :from table-key
                                                               :where [:in pk-key ids]})
                             process-row (fn [row]
                                           (let [row (dissoc row pk-key)
                                                 row (reduce
                                                      (fn [row {:keys [join-key col-key ref-key]}]
                                                        (let [ref-val (get row col-key)]
                                                          (cond-> row
                                                            (some? ref-val)
                                                            (assoc join-key {ref-key ref-val}))))
                                                      row
                                                      join-mappings)]
                                             (into {}
                                                   (filter (fn [[_ v]]
                                                             (some? v)))
                                                   row)))
                             id->result (into {} (map (juxt pk-key process-row)) results)]
                         (mapv (fn [input]
                                 (get id->result (get input pk-key) {}))
                               inputs)))})))))

(defn use-sqlite
  "Biff component that runs schema migrations, starts connection pool,
   and optionally starts litestream replication."
  [ctx]
  (let [ctx (biff.sqlite/use-sqlite ctx)]
    (assoc ctx :biff/query (partial biff.sqlite/execute ctx))))
