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
   :mv.user/current-item :mv-user/current-item-id})

(def xt->sqlite-table
  "Mapping from XTDB table names to SQLite table names.
   Only includes tables that differ."
  {:ad.click  :ad-click
   :ad.credit :ad-credit
   :mv.sub    :mv-sub
   :mv.user   :mv-user})

(defn- sqlite-table [xt-table]
  (get xt->sqlite-table xt-table xt-table))

(defn sqlite-table*
  "Public version of sqlite-table for use in test helpers."
  [xt-table]
  (sqlite-table xt-table))

(defn- sqlite-id-key [table]
  (keyword (name table) "id"))

(defn- rename-key [k table]
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

(defn rename-key*
  "Public version of rename-key for use in test helpers."
  [k table]
  (rename-key k table))

;; ============================================================================
;; Value coercion for SQLite writes
;; ============================================================================

(defn- uuid->bytes [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn coerce-sqlite-value*
  "Coerce a Clojure value for SQLite storage. Public version."
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

(defn- coerce-sqlite-value [v] (coerce-sqlite-value* v))

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
  "Coerce all values in a document map for SQLite storage."
  [doc]
  (into {}
        (map (fn [[k v]]
               [k (if (some? v)
                    (coerce-sqlite-value v)
                    v)]))
        doc))

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
  (let [[{existing-id :xt/id}] (biffx/q conn
                                        {:select :xt/id
                                         :from table
                                         :where (into [:and]
                                                      (map (fn [[k v]]
                                                             [:= k v]))
                                                      on)
                                         :limit 1})]
    (if existing-id
      (let [xt-update {:update table
                       :set (dissoc new-record :xt/id)
                       :where [:= :xt/id existing-id]}
            sqlite-set (-> (dissoc new-record :xt/id)
                           (rename-doc-keys table)
                           coerce-sqlite-doc)
            sqlite-update {:update (sqlite-table table)
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
  (if (empty? results)
    ;; we need a special case because the second assert doesn't work when results are empty.
    [{:xt {:assert [:not-exists query]}
      :sqlite nil}]
    [{:xt {:assert [:=
                    {:select [[[:nest_many query]]]}
                    {:select [[[:array (for [record results]
                                         [:lift record])]]]}]}
      :sqlite nil}]))

(defmethod biff-tx-op :biff/upsert
  [{:keys [biff/conn]} [_ table on & records]]
  (let [query {:select (conj on :xt/id)
               :from table
               :where [:in
                       [:array on]
                       (for [record records]
                         [:array (mapv record on)])]}
        results (biffx/q conn query)
        on-fn (apply juxt on)
        on->id (into {} (map (juxt on-fn :xt/id)) results)
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
                               records)
        sqlite-on (mapv #(rename-key % table) on)]
    (vec (concat
          [[:biff/assert-query query results]]

          (when (not-empty new-records)
            [(into [:put-docs table] new-records)])

          (for [record existing-records
                :let [xt-update {:update table
                                 :set (apply dissoc record :xt/id on)
                                 :where [:= :xt/id (:xt/id record)]}
                      strip-lifts (fn [m]
                                    (into {} (map (fn [[k v]] [k (strip-lift v)])) m))
                      sqlite-set (-> (apply dissoc record :xt/id on)
                                     strip-lifts
                                     (rename-doc-keys table)
                                     (coerce-sqlite-set table))
                      sqlite-update {:update (sqlite-table table)
                                     :set sqlite-set
                                     :where [:= :id (coerce-sqlite-value (:xt/id record))]}]]
            {:xt xt-update
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
  (and (vector? tx-op)
       (#{:put-docs :patch-docs :delete-docs :erase-docs} (first tx-op))))

(defn- dual-write-op? [tx-op]
  (and (map? tx-op)
       (contains? tx-op :xt)))

(defn- xtql->sqlite-honeysql
  "Translate an XTQL operation into a SQLite HoneySQL operation.
   Returns a vector of HoneySQL operations (may be empty or multiple)."
  [tx-op]
  (let [[op table-or-opts & args] tx-op
        table (if (keyword? table-or-opts) table-or-opts (:into table-or-opts))
        sqlite-tbl (sqlite-table table)]
    (case op
      :put-docs
      (let [docs args]
        (when (seq docs)
          (let [rows (mapv (fn [doc]
                             (coerce-sqlite-doc (rename-doc-keys doc table)))
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
                      coerced (coerce-sqlite-doc renamed)
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
      [])))

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
        xt-tx (into []
                    (mapcat (fn [op]
                              (if (xtql-op? op)
                                [op]
                                (let [xt-val (:xt op)]
                                  (when xt-val [xt-val])))))
                    resolved)
        sqlite-tx (into []
                        (mapcat (fn [op]
                                  (if (xtql-op? op)
                                    (xtql->sqlite-honeysql op)
                                    (let [sqlite-val (:sqlite op)]
                                      (when sqlite-val [sqlite-val])))))
                        resolved)]
    (submit-sqlite-tx! (:biff/conn* ctx) sqlite-tx)
    (biffx/submit-tx ctx xt-tx)))

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

(def sqlite->xt-key
  "Reverse mapping from SQLite attribute keys to XTDB attribute keys."
  (into {} (map (fn [[k v]] [v k])) xt->sqlite-key))

(defn- bytes->uuid [^bytes byte-array]
  (when byte-array
    (let [bb (ByteBuffer/wrap byte-array)]
      (UUID. (.getLong bb) (.getLong bb)))))

(defn- epoch-ms->inst [ms]
  (when ms
    (Instant/ofEpochMilli ms)))

(defn- infer-table-from-query
  "Infer the XTDB table name from a HoneySQL query map."
  [query]
  (cond
    (keyword? (:from query)) (:from query)
    (vector? (:from query)) (first (:from query))
    :else nil))

(declare translate-query)

(defn- rename-select-key
  "Rename a key in a select clause from XTDB to SQLite format.
   Handles: bare keywords, aliased pairs, aggregates."
  [k table]
  (cond
    (= k :*) :*
    (= k :xt/id) :id
    ;; Handle XTDB table._id references (e.g. :ad._id -> :ad/id, :skip._id -> :skip/id)
    (and (keyword? k) (str/ends-with? (name k) "._id"))
    (let [tbl-name (subs (name k) 0 (- (count (name k)) 4))
          sqlite-tbl (get xt->sqlite-table (keyword tbl-name) (keyword tbl-name))]
      (keyword (name sqlite-tbl) "id"))
    (keyword? k) (rename-key k table)
    :else k))

(declare translate-select)

(defn- translate-select-item
  "Translate a single select item that is a vector.
   Handles both [expr alias] pairs and function calls like [:sum :col]."
  [item table]
  (if (and (= 2 (count item))
           (keyword? (second item))
           ;; The first element is not a bare keyword (i.e. it's a vector/number/map expression)
           ;; OR the first element is a dotted XTDB reference like :ad._id
           (or (not (keyword? (first item)))
               (str/ends-with? (name (first item)) "._id")))
    ;; [expr alias] pair — translate expr, strip namespace from alias
    [(translate-select (first item) table)
     (keyword (name (second item)))]
    ;; Function call or other structure — recurse normally
    (mapv (fn [x]
            (cond
              (keyword? x) (rename-select-key x table)
              (vector? x) (translate-select-item x table)
              (map? x) (translate-query x)
              :else x))
          item)))

(defn- translate-select
  "Translate select clause from XTDB to SQLite format."
  [select table]
  (cond
    ;; :select :xt/id -> :select :id
    (keyword? select) (rename-select-key select table)
    ;; :select 1 -> :select 1
    (number? select) select
    ;; subquery map in select
    (map? select) (translate-query select)
    ;; :select [:col1 :col2] or [expr alias] pairs
    (vector? select) (mapv (fn [item]
                             (cond
                               (keyword? item) (rename-select-key item table)
                               (vector? item) (translate-select-item item table)
                               (map? item) (translate-query item)
                               :else item))
                           select)
    :else select))

(defn- translate-query
  "Translate an XTDB-style HoneySQL query to SQLite format.
   Renames tables, columns, and coerces values."
  [query]
  (let [table (infer-table-from-query query)
        sqlite-tbl (when table (sqlite-table table))]
    (-> query
        (cond-> table (assoc :from sqlite-tbl))
        (cond-> (:select query) (update :select translate-select table))
        (cond-> (:where query) (update :where #(coerce-where-clause % table)))
        (cond-> (:order-by query)
          (update :order-by
                  (fn [obs]
                    (mapv (fn [ob]
                            (if (vector? ob)
                              (let [[k dir] ob]
                                [(rename-select-key k table) dir])
                              (rename-select-key ob table)))
                          obs))))
        (cond-> (:join query)
          (update :join (fn [joins]
                          (mapv (fn [j]
                                  (cond
                                    (keyword? j) (sqlite-table j)
                                    (vector? j) (coerce-where-clause j table)
                                    :else j))
                                joins))))
        (cond-> (:left-join query)
          (update :left-join (fn [joins]
                               (mapv (fn [j]
                                       (cond
                                         (keyword? j) (sqlite-table j)
                                         (vector? j) (coerce-where-clause j table)
                                         :else j))
                                     joins))))
        (cond-> (:group-by query)
          (update :group-by (fn [gbs]
                              (mapv #(rename-select-key % table) gbs))))
        ;; Handle union/union-all by recursively translating subqueries
        (cond-> (:union query)
          (update :union (fn [qs] (mapv translate-query qs))))
        (cond-> (:union-all query)
          (update :union-all (fn [qs] (mapv translate-query qs)))))))

(def ^:private schema-info-cache (delay (sqlite/schema-info @(requiring-resolve 'com.yakread/malli-opts*))))

(defn- table-has-column?
  "Check if the table schema has a column with the given unqualified name."
  [table column-name]
  (let [info @schema-info-cache
        sqlite-tbl (sqlite-table table)]
    (some? (get-in info [sqlite-tbl (keyword (name sqlite-tbl) (name column-name))]))))

(defn- result-key->xt-key
  "Convert a SQLite result column key back to an XTDB key."
  [k table read-coerce-fns]
  (if (nil? table)
    ;; No table context (e.g. top-level query with only subqueries) — return key as-is
    k
    (let [;; SQLite results come back as :column_name, need to convert to :table/column-name
          sqlite-key (keyword (name (sqlite-table table))
                              (str/replace (name k) "_" "-"))
          id-key (sqlite-id-key (sqlite-table table))]
      (cond
        ;; :id -> :xt/id
        (= k :id) :xt/id
        (= sqlite-key id-key) :xt/id
        ;; Check reverse mapping
        (contains? sqlite->xt-key sqlite-key) (get sqlite->xt-key sqlite-key)
        ;; Check if this is a known column in the schema
        (table-has-column? table (str/replace (name k) "_" "-")) sqlite-key
        ;; Otherwise, treat as an alias (e.g. :count) — keep unqualified
        :else k))))

(defn- coerce-result-value
  "Coerce a SQLite result value back to XTDB-compatible format.
   Only coerces UUIDs (byte arrays -> UUID objects)."
  [v]
  (cond
    (instance? (Class/forName "[B") v) (bytes->uuid v)
    :else v))

(defn- coerce-result-row
  "Coerce a single result row from SQLite format back to XTDB format.
   Uses schema-aware read coercions for proper enum/type handling."
  [row table read-coerce-fns]
  (when row
    (into {}
          (map (fn [[k v]]
                 (let [xt-key (result-key->xt-key k table read-coerce-fns)
                       ;; Try schema-aware coercion first (handles enums, etc.)
                       coerce-fn (when table
                                   (get read-coerce-fns
                                        (keyword (name (sqlite-table table))
                                                 (str/replace (name k) "_" "-"))))
                       coerced-v (cond
                                   (and coerce-fn (some? v)) (coerce-fn v)
                                   :else (coerce-result-value v))]
                   [xt-key coerced-v])))
          row)))

(defn q
  "Query SQLite using an XTDB-style HoneySQL query.
   Translates the query to SQLite format, runs it, and converts results
   back to XTDB-compatible format. conn must be a JDBC/SQLite connection."
  [conn query]
  (let [table (infer-table-from-query query)
        sqlite-tbl (when table (sqlite-table table))
        read-fns (when sqlite-tbl
                   (sqlite/read-coercions @(requiring-resolve 'com.yakread/malli-opts*) sqlite-tbl))
        sqlite-query (translate-query query)
        formatted (sql/format sqlite-query)
        _ (log/debug "biffs/q SQL:" (first formatted))
        results (jdbc/execute! conn formatted
                               {:builder-fn rs/as-unqualified-kebab-maps})]
    (mapv #(coerce-result-row % table read-fns) results)))
