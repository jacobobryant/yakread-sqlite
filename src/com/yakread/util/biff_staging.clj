(ns com.yakread.util.biff-staging
  (:require
   [aero.core :as aero]
   [buddy.core.mac :as mac]
   [clojure.data.generators :as gen]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.tools.namespace.find :as ns-find]
   [clojure.walk :as walk]
   [com.biffweb :as biff]
   [com.biffweb.experimental :as biffx]
   [com.yakread.lib.sqlite :as sqlite]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.wsscode.pathom3.connect.runner :as-alias pcr]
   [com.yakread.lib.core :as lib.core]
   [honey.sql :as sql]
   [malli.core :as malli]
   [malli.registry :as malr]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.nippy :as nippy])
  (:import
   [java.nio ByteBuffer]
   [java.time Instant ZonedDateTime]
   [java.util UUID]))

(declare q)

(defn doc-asts [{:keys [registry] :as malli-opts}]
  (for [schema-k (keys (malr/schemas (:registry malli-opts)))
        :let [schema (try (malli/deref-recursive schema-k malli-opts) (catch Exception _))]
        :when schema
        :let [schemas (volatile! [])
              _ (malli/walk schema (fn [schema _ _ _]
                                     (vswap! schemas conj schema)))]
        schema @schemas
        :let [ast (malli/ast schema)]
        :when (and ast
                   (= (:type ast) :map)
                   (contains? (:keys ast) :xt/id))]
    (assoc-in ast [:properties :schema] schema-k)))

(defn- attr-union [m1 m2]
  (let [shared-keys (into [] (filter #(contains? m2 %)) (keys m1))]
    (when-some [conflicting-attr (first (filter #(not= (m1 %) (m2 %)) shared-keys))]
      (throw (ex-info "An attribute has a conflicting definition"
                      {:attr conflicting-attr
                       :definition-1 (m1 conflicting-attr)
                       :definition-2 (m2 conflicting-attr)})))
    (merge m1 m2)))

(defn table-ast? [ast]
  (and (= :map (:type ast))
       (contains? (:keys ast) :xt/id)))

(defn deref-ast [schema malli-opts]
  (some-> (biff/catchall (malli/deref-recursive schema malli-opts))
          malli/ast))

(defn table-asts [schema malli-opts]
  (->> (deref-ast schema malli-opts)
       (tree-seq (constantly true) :children)
       (filterv table-ast?)))

;; rename to column info, or refactor to be more general
(defn schema-info [malli-opts]
  (into {}
        (keep (fn [schema-k]
                (let [attrs (->> (table-asts schema-k malli-opts)
                                 (mapv :keys)
                                 (reduce attr-union {}))]
                  (when (not-empty attrs)
                    [schema-k attrs]))))
        (keys (malr/schemas (:registry malli-opts)))))

(def table-properties
  (memoize
   (fn [table malli-opts]
     (->> (table-asts table malli-opts)
          ;; TODO maybe check that certain properties are same for all asts
          first
          :properties))))

(defn field-asts [malli-opts]
  (apply merge (vals (schema-info malli-opts))))

(defn- expects [env]
  (-> env
      ::pcp/node
      ::pcp/expects
      keys
      vec))

;; TODO see if we can infer this more intelligently
(def schema-whitelist
  #{:feed
    :ad-credit
    :bulk-send
    :mv-sub
    :user-item
    :sub
    :digest-item
    :item
    :mv-user
    :digest
    :reclist
    :ad-click
    :deleted-user
    :redirect
    :ad
    :user
    :skip})

(defn xtdb2-resolvers [malli-opts]
  ;; TODO maybe add reverse resolvers too
  (for [[schema attrs] (schema-info malli-opts)
        :when (contains? schema-whitelist schema)
        :let [ref? (fn [attr]
                     (boolean (get-in attrs [attr :properties :biff/ref])))
              joinify (fn [[k v]]
                        (if (ref? k)
                          [k {:xt/id v}]
                          [k v]))
              joinify-map (fn [m]
                            (into {} (map joinify) m))]
        :when (not (qualified-keyword? schema))
        :let [op-name (symbol "com.yakread.util.biff-staging"
                              (str (name schema) "-xtdb2-resolver"))]]
    (pco/resolver op-name
                  {::pco/input [:xt/id]
                   ::pco/output (vec (for [k (keys attrs)
                                           :when (not= k :xt/id)]
                                       (if (ref? k)
                                         {k [:xt/id]}
                                         k)))
                   ::pco/batch? true
                   ::pco/cache-key (fn [env input]
                                     [op-name input (expects env)])}
                  (fn [{:keys [biff/conn ::pcr/resolver-cache*] :as env} inputs]
                    ;; TODO
                    ;; - use a fixed db snapshot
                    (let [resolver-cache* (when (or (volatile? resolver-cache*)
                                                    (= clojure.lang.Atom (type resolver-cache*)))
                                            resolver-cache*)
                          cache-value (some-> resolver-cache* deref)
                          columns (filterv attrs (expects env))
                          results (mapv (fn [{:keys [xt/id] :as input}]
                                          (merge input
                                                 (get-in cache-value [::cache schema id])))
                                        inputs)
                          missing-columns (into #{}
                                                (mapcat (fn [entity]
                                                          (into []
                                                                (remove #(contains? entity %))
                                                                columns)))
                                                results)
                          incomplete-inputs (filterv (fn [input]
                                                       (some #(not (contains? input %))
                                                             missing-columns))
                                                     inputs)
                          query {:select (vec (conj missing-columns :xt/id))
                                                    :from schema
                                                    :where [:in :xt/id (mapv :xt/id incomplete-inputs)]}
                          query-results (when (not-empty incomplete-inputs)
                                          (biffx/q conn query))
                          nil-map (zipmap missing-columns (repeat nil))
                          update-cache (fn [cache-value]
                                         (reduce (fn [cache-value record]
                                                   (update-in cache-value
                                                              [::cache schema (:xt/id record)]
                                                              #(merge nil-map % record)))
                                                 cache-value
                                                 query-results))
                          cache-value (cond
                                        (not resolver-cache*)
                                        (update-cache cache-value)

                                        (volatile? resolver-cache*)
                                        (vswap! resolver-cache* update-cache)

                                        :else
                                        (swap! resolver-cache* update-cache))]
                      (mapv (fn [{:keys [xt/id]}]
                              (-> (get-in cache-value [::cache schema id])
                                  lib.core/some-vals
                                  joinify-map
                                  (assoc :xt/id id)))
                            inputs))))))

(defn- find-modules [search-dirs]
  (->> search-dirs
       (mapcat #(ns-find/find-namespaces-in-dir (io/file %)))
       (keep (fn [ns-sym]
               (require ns-sym)
               (if-some [module-var (resolve (symbol (str ns-sym) "module"))]
                 (symbol module-var)
                 (do
                   (log/warn "No `module` var found in namespace:" ns-sym)
                   nil))))
       vec))

(defn generate-modules-file! [{:keys [output-file search-dirs]}]
  (when-some [module-symbols (not-empty (find-modules search-dirs))]
    (with-open [w (io/writer output-file)]
      (binding [*out* w]
        (biff/pprint (list 'ns 'com.yakread.modules
                           "This file is auto-generated by Biff. Any changes will be overwritten."
                           (concat '(:require)
                                   (for [sym module-symbols]
                                     [(symbol (namespace sym))]))))
        (println)
        (biff/pprint (list 'def 'modules module-symbols))))))

(defn base64-url-encode [ba]
  (.encodeToString (java.util.Base64/getUrlEncoder) ba))

(defn base64-url-decode [s]
  (.decode (java.util.Base64/getUrlDecoder) s))

(defn signature
  "Returns the hmac-sha1 as base64"
  [secret s]
  (-> (mac/hash s {:key secret :alg :hmac+sha256})
      base64-url-encode))

(defn unsafe [& html]
  {:dangerouslySetInnerHTML {:__html (apply str html)}})

(defmethod aero/reader 'biff/edn
  [_ _ value]
  (edn/read-string value))

;; ============================================================================
;; XTDB-to-SQLite key mapping for dual-write
;; ============================================================================

(def xt->sqlite-key
  "Mapping from XTDB attribute keys to SQLite attribute keys.
   Only includes keys that differ between the two schemas."
  {;; user table - :xt/id -> :user/id handled by table-specific logic
   :user/timezone* :user/timezone
   :user/send-digest-at :user/send-digest-at

   ;; sub table
   :sub/user       :sub/user-id
   :sub.feed/feed  :sub/feed-id
   :sub.email/from :sub/email-from
   :sub.email/unsubscribed-at :sub/email-unsubscribed-at

   ;; item table
   :item.feed/feed            :item/feed-id
   :item.feed/guid            :item/feed-guid
   :item.email/sub            :item/email-sub-id
   :item.email/raw-content-key :item/email-raw-content-key
   :item.email/list-unsubscribe :item/email-list-unsubscribe
   :item.email/list-unsubscribe-post :item/email-list-unsubscribe-post
   :item.email/reply-to       :item/email-reply-to
   :item.email/maybe-confirmation :item/email-maybe-confirmation
   :item.direct/candidate-status :item/direct-candidate-status
   :item/doc-type             :item/record-type

   ;; redirect table
   :redirect/item  :redirect/item-id

   ;; user-item table
   :user-item/user :user-item/user-id
   :user-item/item :user-item/item-id

   ;; digest table
   :digest/user      :digest/user-id
   :digest/subject   :digest/subject-id
   :digest/ad        :digest/ad-id
   :digest/bulk-send :digest/bulk-send-id

   ;; digest-item table
   :digest-item/digest :digest-item/digest-id
   :digest-item/item   :digest-item/item-id

   ;; reclist table
   :reclist/user :reclist/user-id

   ;; skip table
   :skip/reclist :skip/reclist-id
   :skip/item    :skip/item-id

   ;; ad table
   :ad/user :ad/user-id

   ;; ad-click table (xtdb uses ad.click namespace)
   :ad.click/user       :ad-click/user-id
   :ad.click/ad         :ad-click/ad-id
   :ad.click/created-at :ad-click/created-at
   :ad.click/cost       :ad-click/cost
   :ad.click/source     :ad-click/source

   ;; ad-credit table (xtdb uses ad.credit namespace)
   :ad.credit/ad            :ad-credit/ad-id
   :ad.credit/source        :ad-credit/source
   :ad.credit/amount        :ad-credit/amount
   :ad.credit/created-at    :ad-credit/created-at
   :ad.credit/charge-status :ad-credit/charge-status

   ;; mv-sub table (xtdb uses mv.sub namespace)
   :mv.sub/sub            :mv-sub/sub-id
   :mv.sub/affinity-low   :mv-sub/affinity-low
   :mv.sub/affinity-high  :mv-sub/affinity-high
   :mv.sub/last-published :mv-sub/last-published
   :mv.sub/unread         :mv-sub/unread
   :mv.sub/read           :mv-sub/n-read

   ;; mv-user table (xtdb uses mv.user namespace)
   :mv.user/user         :mv-user/user-id
   :mv.user/current-item :mv-user/current-item-id

   ;; biff-auth-code table (xtdb uses biff.auth.code namespace)
   :biff.auth.code/email           :biff-auth-code/email
   :biff.auth.code/code            :biff-auth-code/code
   :biff.auth.code/created-at      :biff-auth-code/created-at
   :biff.auth.code/failed-attempts :biff-auth-code/failed-attempts})

(def xt->sqlite-table
  "Mapping from XTDB table names to SQLite table names.
   Only includes tables that differ."
  {:ad.click   :ad-click
   :ad.credit  :ad-credit
   :mv.sub     :mv-sub
   :mv.user    :mv-user
   :biff.auth/code :biff-auth-code})

(defn sqlite-table [xt-table]
  (get xt->sqlite-table xt-table xt-table))

(defn- sqlite-id-key [table]
  (keyword (name table) "id"))

(defn rename-key [k table]
  (cond
    (= k :xt/id)
    (sqlite-id-key (sqlite-table table))

    (contains? xt->sqlite-key k)
    (get xt->sqlite-key k)

    ;; Handle XTDB dotted table namespaces (e.g. :ad.click/id -> :ad-click/id)
    (and (namespace k)
         (str/includes? (namespace k) ".")
         (contains? xt->sqlite-table (keyword (namespace k))))
    (keyword (name (get xt->sqlite-table (keyword (namespace k)))) (name k))

    :else k))

;; ============================================================================
;; Value coercion for SQLite writes
;; ============================================================================

(defn- uuid->bytes [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn coerce-sqlite-value
  "Coerce a Clojure value for SQLite storage."
  [v]
  (cond
    (uuid? v) (uuid->bytes v)
    (instance? Instant v) (.toEpochMilli ^Instant v)
    (instance? ZonedDateTime v) (.toEpochMilli (.toInstant ^ZonedDateTime v))
    (boolean? v) (if v 1 0)
    (keyword? v) v ;; enums handled separately by the schema-aware coercion
    (set? v) (nippy/fast-freeze v)
    (map? v) (nippy/fast-freeze v)
    (vector? v) (nippy/fast-freeze v)
    :else v))

(defn- sql-expression?
  "Check if a value is a SQL expression vector (like [:- :col val])."
  [v]
  (and (vector? v)
       (keyword? (first v))
       (not= :lift (first v))))

(defn- coerce-sqlite-set-value
  "Coerce a value for a SQLite :set clause. Handles both plain values
   and SQL expression vectors."
  [v table]
  (if (sql-expression? v)
    ;; SQL expression - rename column refs and coerce literal values
    (mapv (fn [x]
            (cond
              (and (keyword? x) (qualified-keyword? x))
              (let [renamed (rename-key x table)]
                ;; Use unqualified name for SQLite column reference
                (keyword (name renamed)))
              :else
              (coerce-sqlite-value x)))
          v)
    (coerce-sqlite-value v)))

(defn- coerce-sqlite-doc
  "Coerce all values in a document map for SQLite storage.
   Uses schema-aware write coercions for proper enum/type handling."
  ([doc] (coerce-sqlite-doc doc nil))
  ([doc write-fns]
   (into {}
         (map (fn [[k v]]
                [k (if (some? v)
                     (let [coerce-fn (get write-fns k)]
                       (if coerce-fn
                         (coerce-fn v)
                         (coerce-sqlite-value v)))
                     v)]))
         doc)))

(defn- coerce-sqlite-set
  "Coerce all values in a :set map for SQLite storage, handling SQL expressions."
  [set-map table]
  (into {}
        (map (fn [[k v]]
               [k (if (some? v)
                    (coerce-sqlite-set-value v table)
                    v)]))
        set-map))

(defn- rename-doc-keys
  "Rename keys in a document from XTDB to SQLite naming."
  [doc table]
  (into {}
        (map (fn [[k v]]
               [(rename-key k table) v]))
        doc))

(defn- strip-lift
  "Remove :lift wrappers from values. In XTDB, [:lift v] is used to wrap
   literal values in HoneySQL. For SQLite, we just use the raw value."
  [v]
  (if (and (vector? v) (= :lift (first v)))
    (second v)
    v))

(defn upsert [conn table on new-record]
  (let [sqlite-tbl (sqlite-table table)
        ;; TODO: restructure so that malli-opts can be passed as a regular parameter
        ;; instead of using requiring-resolve. We can do that after the migration is finished.
        write-fns (sqlite/write-coercions @(requiring-resolve 'com.yakread/malli-opts*) sqlite-tbl)
        ;; Translate the 'on' map to SQLite column names and coerce values
        sqlite-on (-> (rename-doc-keys on table)
                      (coerce-sqlite-doc write-fns))
        [{existing-id :xt/id}] (q conn
                                  {:select :id
                                   :from sqlite-tbl
                                   :where (into [:and]
                                                (map (fn [[k v]]
                                                       [:= k v]))
                                                sqlite-on)
                                   :limit 1})]
    (if existing-id
      (let [sqlite-tbl (sqlite-table table)
            write-fns (sqlite/write-coercions @(requiring-resolve 'com.yakread/malli-opts*) sqlite-tbl)
            xt-update {:update table
                       :set (dissoc new-record :xt/id)
                       :where [:= :xt/id existing-id]}
            sqlite-set (-> (dissoc new-record :xt/id)
                           (rename-doc-keys table)
                           (coerce-sqlite-doc write-fns))
            sqlite-update {:update sqlite-tbl
                           :set sqlite-set
                           :where [:= :id (coerce-sqlite-value existing-id)]}]
        [{:xt xt-update
          :sqlite sqlite-update}])
      [[:put-docs table (into {}
                              (filter (comp some? val))
                              (merge {:xt/id (gen/uuid)} new-record on))]
       {:xt (biffx/assert-unique table on)
        :sqlite nil}])))

(defmulti biff-tx-op (fn [ctx op]
                       (when (and (vector? op)
                                  (qualified-keyword? (first op)))
                         (first op))))

(defmethod biff-tx-op :default
  [_ op]
  [op])

(defmethod biff-tx-op :biff/assert-query
  [_ [_ query results]]
  ;; No-op: assertions were for XTDB transactions. With SQLite-only writes,
  ;; uniqueness is handled via UNIQUE constraints in the schema.
  [])

(defmethod biff-tx-op :biff/upsert
  [{:keys [biff/conn*]} [_ table on & records]]
  (let [sqlite-tbl (sqlite-table table)
        sqlite-on (mapv #(rename-key % table) on)
        ;; Query SQLite for existing records matching the ON keys
        where-clause (if (= 1 (count sqlite-on))
                       [:in (first sqlite-on)
                        (mapv (fn [record] (coerce-sqlite-value (get record (first on)))) records)]
                       [:or (for [record records]
                              (into [:and]
                                    (map (fn [xt-k sqlite-k]
                                           [:= sqlite-k (coerce-sqlite-value (get record xt-k))])
                                         on sqlite-on)))])
        id-key (keyword (name sqlite-tbl) "id")
        select-keys (into [id-key] sqlite-on)
        sqlite-query {:select select-keys
                      :from sqlite-tbl
                      :where where-clause}
        results (q conn* sqlite-query)
        ;; Map from ON-key values to existing IDs
        on-fn (apply juxt on)
        sqlite-on-fn (apply juxt (mapv #(rename-key % table) on))
        on->id (into {} (map (fn [row] [(sqlite-on-fn row) (get row id-key)])) results)
        new-records (into []
                          (keep (fn [record]
                                  (when-not (contains? on->id (on-fn record))
                                    (into {}
                                          (filter (comp some? val))
                                          (merge {:xt/id (gen/uuid)}
                                                 (dissoc record :biff/on-insert :biff/on-update)
                                                 (:biff/on-insert record))))))
                          records)
        existing-records (into []
                               (keep (fn [record]
                                       (when-some [id (on->id (on-fn record))]
                                         (merge (into {}
                                                      (map (fn [[k v]]
                                                             (if (keyword? v)
                                                               [k [:lift v]]
                                                               [k v])))
                                                      (dissoc record :biff/on-insert :biff/on-update))
                                                (:biff/on-update record)
                                                {:xt/id id}))))
                               records)]
    (vec (concat
          (when (not-empty new-records)
            [(into [:put-docs table] new-records)])

          (for [record existing-records
                :let [strip-lifts (fn [m]
                                    (into {} (map (fn [[k v]] [k (strip-lift v)])) m))
                      sqlite-set (-> (apply dissoc record :xt/id on)
                                     strip-lifts
                                     (rename-doc-keys table)
                                     (coerce-sqlite-set table))
                      sqlite-update {:update (sqlite-table table)
                                     :set sqlite-set
                                     :where [:= :id (coerce-sqlite-value (:xt/id record))]}]]
            {:xt nil
             :sqlite sqlite-update})))))

(defn resolve-tx-ops [ctx tx]
  (into []
        (mapcat (fn [tx-op]
                  (let [expanded (biff-tx-op ctx tx-op)]
                    (cond->> expanded
                      (not= expanded [tx-op]) (resolve-tx-ops ctx)))))
        tx))

;; ============================================================================
;; XTQL to SQLite HoneySQL translation
;; ============================================================================

(defn- xtql-op? [tx-op]
  (or (and (vector? tx-op)
           (#{:put-docs :patch-docs :delete-docs :erase-docs} (first tx-op)))
      (and (map? tx-op)
           (or (contains? tx-op :update)
               (contains? tx-op :delete)))))

(defn- dual-write-op? [tx-op]
  (and (map? tx-op)
       (contains? tx-op :xt)))

(defn- infer-record-type
  "Infer the record_type value for tables that use discriminated unions.
   In XTDB, the type is determined by which subtype attributes are present.
   In SQLite, it's stored as an integer in the record_type column.
   Returns the unqualified keyword enum value (e.g. :feed, :email, :direct)."
  [doc table]
  (case table
    :sub (cond
           (contains? doc :sub.feed/feed)   :feed
           (contains? doc :sub.email/from)  :email
           :else nil)
    :item (cond
            (contains? doc :item/doc-type)       nil  ;; already has doc-type, will be coerced
            (contains? doc :item.feed/feed)       :feed
            (contains? doc :item.email/sub)       :email
            :else nil)
    nil))

(defn- xtql->sqlite-honeysql
  "Translate an XTQL operation into a SQLite HoneySQL operation.
   Returns a vector of HoneySQL operations (may be empty or multiple).
   Handles both vector-style [:put-docs table ...] and map-style {:update table ...} ops."
  [tx-op]
  (if (map? tx-op)
    ;; Map-style XTQL ops: {:update table :set {...} :where [...]}
    ;; or {:delete table :where [...]}
    (let [table-sym (or (:update tx-op) (:delete tx-op))
          table (cond
                  (keyword? table-sym) table-sym
                  (symbol? table-sym)  (keyword table-sym)
                  :else                table-sym)
          sqlite-tbl (sqlite-table table)
          write-fns (sqlite/write-coercions @(requiring-resolve 'com.yakread/malli-opts*) sqlite-tbl)]
      (cond
        (:update tx-op)
        (let [set-map (:set tx-op)
              where-clause (:where tx-op)
              renamed-set (reduce-kv (fn [m k v]
                                       (let [new-k (rename-key k table)
                                             new-k-unqualified (keyword (name new-k))
                                             coerced-v (if (and (vector? v) (keyword? (first v)))
                                                         ;; Arithmetic expressions like [:+ :col 1]
                                                         (mapv (fn [x]
                                                                 (if (keyword? x)
                                                                   (keyword (name (rename-key x table)))
                                                                   (coerce-sqlite-value x)))
                                                               v)
                                                         (coerce-sqlite-value v))]
                                         (assoc m new-k-unqualified coerced-v)))
                                     {}
                                     set-map)
              renamed-where (walk/postwalk
                              (fn [x]
                                (if (keyword? x)
                                  (if (= x :xt/id)
                                    :id
                                    (keyword (name (rename-key x table))))
                                  (coerce-sqlite-value x)))
                              where-clause)]
          [{:update sqlite-tbl
            :set renamed-set
            :where renamed-where}])

        (:delete tx-op)
        (let [where-clause (:where tx-op)
              renamed-where (walk/postwalk
                              (fn [x]
                                (if (keyword? x)
                                  (if (= x :xt/id)
                                    :id
                                    (keyword (name (rename-key x table))))
                                  (coerce-sqlite-value x)))
                              where-clause)]
          [{:delete-from sqlite-tbl
            :where renamed-where}])

        :else []))
    ;; Vector-style XTQL ops: [:put-docs table ...]
    (let [[op table-or-opts & args] tx-op
          table (if (keyword? table-or-opts) table-or-opts (:into table-or-opts))
          sqlite-tbl (sqlite-table table)
          write-fns (sqlite/write-coercions @(requiring-resolve 'com.yakread/malli-opts*) sqlite-tbl)]
      (case op
        :put-docs
        (let [docs args]
          (when (seq docs)
            (let [rows (mapv (fn [doc]
                               (let [record-type (infer-record-type doc table)
                                     rt-key (keyword (name table) "record-type")
                                     doc-with-rt (cond-> doc
                                                   record-type (assoc rt-key record-type))]
                                 (coerce-sqlite-doc (rename-doc-keys doc-with-rt table) write-fns)))
                             docs)
                  all-keys (vec (into #{} (mapcat keys) rows))
                  id-key (sqlite-id-key sqlite-tbl)
                  non-id-keys (into [] (comp (remove #{id-key})
                                             (map #(keyword (name %))))
                                    all-keys)]
              [{:insert-into sqlite-tbl
                :values rows
                :on-conflict :id
                :do-update-set {:fields non-id-keys}}])))

        :patch-docs
        (let [docs args]
          (mapv (fn [doc]
                  (let [renamed (rename-doc-keys doc table)
                        coerced (coerce-sqlite-doc renamed write-fns)
                        id-key (sqlite-id-key sqlite-tbl)
                        id-val (get coerced id-key)]
                    {:update sqlite-tbl
                     :set (dissoc coerced id-key)
                     :where [:= :id id-val]}))
                docs))

        (:delete-docs :erase-docs)
        (let [ids args]
          (when (seq ids)
            [{:delete-from sqlite-tbl
              :where [:in :id (mapv coerce-sqlite-value ids)]}]))

        ;; Unknown op - skip for sqlite
        []))))

(defn- submit-sqlite-tx!
  "Submit a vector of HoneySQL operations to SQLite.
   Errors are logged but do not propagate, so that SQLite failures
   don't crash the server during the dual-write migration phase."
  [conn ops]
  (when (and conn (seq ops))
    (try
      (jdbc/with-transaction [tx conn]
        (doseq [op ops]
          (let [formatted (if (vector? op)
                            op
                            (sql/format op))]
            (jdbc/execute! tx formatted))))
      (catch Exception e
        (log/error e "Error in submit-sqlite-tx!")))))

(defn submit-tx [ctx tx]
  (let [resolved (resolve-tx-ops ctx tx)
        _ (doseq [op resolved]
            (assert (or (xtql-op? op) (dual-write-op? op))
                    (str "Transaction element must be either an XTQL operation "
                         "(:put-docs/:patch-docs/:delete-docs/:erase-docs) or a "
                         "{:xt ... :sqlite ...} map. Got: " (pr-str op))))
        sqlite-tx (into []
                        (mapcat (fn [op]
                                  (if (xtql-op? op)
                                    (xtql->sqlite-honeysql op)
                                    (let [sqlite-val (:sqlite op)]
                                      (when sqlite-val [sqlite-val])))))
                        resolved)]
    (submit-sqlite-tx! (:biff/conn* ctx) sqlite-tx)))

(defn bundle [& {:as k->query}]
  {:select (mapv (fn [[k query]]
                   [[:nest_many query] k])
                 k->query)})

;; Mapping of XTDB enum string values to SQLite integer values.
;; In XTDB, some enums are stored as strings like "item/direct",
;; while in SQLite they are stored as integer indices.
(def xt-enum-string->sqlite-int
  {"item/feed" 0, "item/email" 1, "item/direct" 2
   "sub/feed" 0, "sub/email" 1
   "digest.item/icymi" 0, "digest.item/discover" 1
   "ad/pending" 0, "ad/approved" 1, "ad/rejected" 2
   "ad.click/web" 0, "ad.click/email" 1
   "ad.credit/charge" 0, "ad.credit/manual" 1
   "ad.credit/pending" 0, "ad.credit/confirmed" 1, "ad.credit/failed" 2
   "feed/approved" 0, "feed/blocked" 1
   "user/quarter" 0, "user/annual" 1
   "item.direct/ingest-failed" 0, "item.direct/blocked" 1, "item.direct/approved" 2})

;; Mapping of keyword enum values to SQLite integer values.
(def xt-enum-kw->sqlite-int
  {:feed 0, :email 1, :direct 2
   :icymi 0, :discover 1
   :pending 0, :approved 1, :rejected 2
   :web 0
   :charge 0, :manual 1
   :confirmed 1, :failed 2
   :blocked 1
   :ingest-failed 0
   :quarter 0, :annual 1})

(defn- coerce-where-clause
  "Recursively walk a where clause, renaming keys and coercing values for SQLite."
  [where table]
  (walk/postwalk
   (fn [x]
     (cond
       (= x :xt/id) :id
       ;; Handle XTDB table._id references (e.g. :ad._id -> :ad/id)
       (and (keyword? x) (str/ends-with? (name x) "._id"))
       (let [tbl-name (subs (name x) 0 (- (count (name x)) 4))
             sqlite-tbl (get xt->sqlite-table (keyword tbl-name) (keyword tbl-name))]
         (keyword (name sqlite-tbl) "id"))
       ;; Rename known column mappings (preserves namespace for table-qualified columns)
       (and (keyword? x) (contains? xt->sqlite-key x))
       (get xt->sqlite-key x)
       ;; Other namespaced keywords — use rename-key to map properly
       (and (keyword? x) (namespace x))
       (rename-key x table)
       (uuid? x) (uuid->bytes x)
       (instance? Instant x) (.toEpochMilli ^Instant x)
       (instance? ZonedDateTime x) (.toEpochMilli (.toInstant ^ZonedDateTime x))
       ;; Convert XTDB enum strings to SQLite integers
       (and (string? x) (contains? xt-enum-string->sqlite-int x))
       (get xt-enum-string->sqlite-int x)
       :else x))
   where))

(defn dual-write
  "Wrap a HoneySQL operation map (e.g. {:update ...} or {:delete-from ...})
   in a {:xt ... :sqlite ...} map, automatically translating keys and values
   for the SQLite side.

   Supports :update and :delete-from operations."
  [{:keys [update delete-from set where] :as xt-op}]
  (let [table (or update delete-from)
        sqlite-tbl (sqlite-table table)
        strip-lifts (fn [m]
                      (into {} (map (fn [[k v]] [k (strip-lift v)])) m))]
    {:xt xt-op
     :sqlite (cond
               update
               {:update sqlite-tbl
                :set (coerce-sqlite-set (rename-doc-keys (strip-lifts (or set {})) table) table)
                :where (coerce-where-clause where table)}

               delete-from
               {:delete-from sqlite-tbl
                :where (coerce-where-clause where table)}

               :else
               xt-op)}))

(defn gen-uuid
  ([]
   (gen/uuid))
  ([prefix]
   (biffx/prefix-uuid prefix (gen/uuid))))

;; ============================================================================
;; SQLite query function (XTDB-compatible interface)
;; ============================================================================

(defn- bytes->uuid [^bytes byte-array]
  (when byte-array
    (let [bb (ByteBuffer/wrap byte-array)]
      (UUID. (.getLong bb) (.getLong bb)))))

(defn- infer-table-from-query
  "Infer the table name from a HoneySQL query map."
  [query]
  (cond
    (keyword? (:from query)) (:from query)
    (vector? (:from query)) (first (:from query))
    :else nil))

(defn- coerce-result-value
  "Coerce a SQLite result value. Converts byte arrays to UUIDs."
  [v]
  (cond
    (instance? (Class/forName "[B") v) (bytes->uuid v)
    :else v))

(defn- table-column-names
  "Get the set of column names (unqualified, kebab-case) for a table from schema."
  [malli-opts table]
  (when (and malli-opts table)
    (when-let [attrs (get (sqlite/schema-info malli-opts) table)]
      (into #{"id"} ;; always include :id
            (map (fn [[attr _]]
                   (name attr)))
            attrs))))

(defn- coerce-result-row
  "Coerce a single result row from SQLite.
   Applies schema-aware read coercions (bytes->UUID, int->enum, epoch-ms->Instant).
   Maps :id -> :table/id (e.g. :sub/id) for Pathom compatibility.
   Only table-qualifies keys that are known schema columns; aliases stay unqualified.
   Removes nil values to match XTDB behavior (missing fields are absent, not nil)."
  [row table read-coerce-fns known-columns]
  (when row
    (into {}
          (keep (fn [[k v]]
                  (when (some? v)
                    (let [col-name (str/replace (name k) "_" "-")
                          ;; Only namespace if it's a known schema column
                          is-schema-col (and known-columns (contains? known-columns col-name))
                          sqlite-key (when (and table is-schema-col)
                                      (keyword (name table) col-name))
                          ;; Map :id -> :table/id (e.g. :sub/id)
                          out-key (cond
                                    (= k :id) (if table
                                                (keyword (name table) "id")
                                                :xt/id)
                                    sqlite-key sqlite-key
                                    :else k)
                          ;; Apply schema-aware coercion
                          coerce-fn (when sqlite-key
                                      (get read-coerce-fns sqlite-key))
                          coerced-v (cond
                                      (and coerce-fn (some? v))
                                      (try (coerce-fn v)
                                           (catch Exception _ (coerce-result-value v)))
                                      :else (coerce-result-value v))]
                      [out-key coerced-v]))))
          row)))

(defn- coerce-query-values
  "Walk a HoneySQL query and coerce Clojure values to SQLite-compatible values.
   Converts UUIDs to byte arrays, Instants/ZonedDateTimes to epoch-ms."
  [query]
  (walk/postwalk
   (fn [x]
     (cond
       (uuid? x) (uuid->bytes x)
       (instance? Instant x) (.toEpochMilli ^Instant x)
       (instance? ZonedDateTime x) (.toEpochMilli (.toInstant ^ZonedDateTime x))
       :else x))
   query))

(defn- coerce-where-enum-values
  "Coerce enum string/keyword values in WHERE clauses using schema-aware write coercions.
   Walks :where vectors looking for [:= :column value] patterns where the column
   has an enum write coercion. Also handles [:lift val] wrappers."
  [query write-fns]
  (if (or (nil? write-fns) (nil? (:where query)))
    query
    (update query :where
            (fn walk-where [clause]
              (cond
                ;; [:= :col val] or [:!= :col val] etc.
                (and (vector? clause)
                     (>= (count clause) 3)
                     (keyword? (second clause)))
                (let [col (second clause)]
                  (if-let [coerce-fn (get write-fns col)]
                    (into [(first clause) col]
                          (map (fn [v]
                                 (cond
                                   ;; Handle [:lift val] wrappers
                                   (and (vector? v) (= :lift (first v)))
                                   (let [inner (second v)]
                                     (if (or (string? inner) (keyword? inner))
                                       (try (coerce-fn inner) (catch Exception _ v))
                                       v))
                                   (or (string? v) (keyword? v))
                                   (try (coerce-fn v) (catch Exception _ v))
                                   :else v))
                               (drop 2 clause)))
                    ;; Recurse into sub-clauses (e.g. [:and ...])
                    (mapv walk-where clause)))
                ;; Recursive case for :and/:or
                (vector? clause) (mapv walk-where clause)
                :else clause)))))

(defn q
  "Query SQLite. conn must be a JDBC/SQLite connection.
   Queries should use native SQLite column/table names.
   Automatically coerces UUID/Instant values in the query and
   applies schema-aware read coercions on results."
  ;; TODO: restructure so that malli-opts can be passed as a regular parameter
  ;; instead of using requiring-resolve. We can do that after the migration is finished.
  [conn query]
  (let [table (infer-table-from-query query)
        malli-opts (when table @(requiring-resolve 'com.yakread/malli-opts*))
        read-fns (when table
                   (sqlite/read-coercions malli-opts table))
        write-fns (when table
                    (sqlite/write-coercions malli-opts table))
        known-cols (table-column-names malli-opts table)
        query (coerce-where-enum-values query write-fns)
        coerced-query (coerce-query-values query)
        formatted (sql/format coerced-query)
        _ (log/debug "biffs/q SQL:" (first formatted))
        results (jdbc/execute! conn formatted
                               {:builder-fn rs/as-unqualified-kebab-maps})]
    (mapv #(coerce-result-row % table read-fns known-cols) results)))
