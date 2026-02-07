(ns com.yakread.lib.sqlite
  "SQLite integration using malli schema as the source of truth.

   This namespace provides:
   1. Malli schema as the source of truth (generates SQLite DDL)
   2. SQLite DDL generation from malli schema
   3. Pathom resolvers generated from malli schema (like xtdb2-resolvers)

   Type inference:
   - SQLite types are inferred from malli types (no explicit :sqlite/type needed)
   - Coercion is inferred from malli types (no explicit :sqlite/coerce needed)
   - Enums are auto-mapped to integers (0, 1, 2, ...)

   Note: next.jdbc automatically converts underscores to hyphens in column names,
   and honeysql converts hyphens to underscores."
  (:require
   [clojure.string :as str]
   [com.stuartsierra.dependency :as dep]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.yakread.lib.core :as lib.core]
   [honey.sql :as sql]
   [malli.core :as malli]
   [malli.registry :as malr]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.nippy :as nippy])
  (:import
   [java.nio ByteBuffer]
   [java.sql ResultSet]
   [java.time Instant]
   [java.util UUID]))

;; ============================================================================
;; Schema Helpers
;; ============================================================================

(def ?
  "Mark an attribute as optional."
  {:optional true})

(defn r
  "Mark an attribute as a reference to another table."
  [target]
  {:biff/ref target})

(defn ?r
  "Mark an optional attribute as a reference."
  [target]
  (assoc (r target) :optional true))

;; ============================================================================
;; SQLite Malli Schema
;; ============================================================================
;;
;; Schema design:
;; - Each table has :table/id instead of :xt/id
;; - Reference attributes end with -id and have :biff/ref
;; - Types are standard malli types; SQLite type is inferred
;; - Enums automatically get integer mappings (0, 1, 2, ...)
;; - Use inst? for timestamps (stored as epoch ms INT in SQLite)

(def schema
  {::string [:string {:max 2000}]
   ::day [:enum :sunday :monday :tuesday :wednesday :thursday :friday :saturday]

   :user [:map {:closed true}
           [:user/id                    :uuid]
           [:user/email                 ::string]
           [:user/roles               ? [:set [:enum :admin]]]
           [:user/joined-at           ? inst?]
           [:user/digest-days         ? [:set ::day]]
           [:user/send-digest-at      ? :string]
           [:user/timezone            ? ::string]
           [:user/digest-last-sent    ? inst?]
           [:user/from-the-sample     ? :boolean]
           [:user/use-original-links  ? :boolean]
           [:user/suppressed-at       ? inst?]
           [:user/email-username      ? ::string]
           [:user/customer-id         ? :string]
           [:user/plan                ? [:enum :quarter :annual]]
           [:user/cancel-at           ? inst?]]

   :feed [:map {:closed true}
           [:feed/id                :uuid]
           [:feed/url               ::string]
           [:feed/synced-at       ? inst?]
           [:feed/title           ? ::string]
           [:feed/description     ? ::string]
           [:feed/image-url       ? ::string]
           [:feed/etag            ? ::string]
           [:feed/last-modified   ? ::string]
           [:feed/failed-syncs    ? :int]
           [:feed/moderation      ? [:enum :approved :blocked]]]

   :sub [:map {:closed true}
          [:sub/id                     :uuid]
          [:sub/user-id      (r :user) :uuid]
          [:sub/created-at             inst?]
          [:sub/pinned-at    ?         inst?]
          [:sub/record-type            [:enum :feed :email]]
          ;; feed sub fields
          [:sub/feed-id      (?r :feed) :uuid]
          ;; email sub fields
          [:sub/email-from           ? ::string]
          [:sub/email-unsubscribed-at ? inst?]]

   :item [:map {:closed true}
           [:item/id                  :uuid]
           [:item/ingested-at         inst?]
           [:item/title             ? ::string]
           [:item/url               ? ::string]
           [:item/redirect-urls     ? [:set ::string]]
           [:item/content           ? ::string]
           [:item/content-key       ? :uuid]
           [:item/published-at      ? inst?]
           [:item/excerpt           ? ::string]
           [:item/author-name       ? ::string]
           [:item/author-url        ? ::string]
           [:item/feed-url          ? ::string]
           [:item/lang              ? ::string]
           [:item/site-name         ? ::string]
           [:item/byline            ? ::string]
           [:item/length            ? :int]
           [:item/image-url         ? ::string]
           [:item/paywalled         ? :boolean]
           [:item/record-type         [:enum :feed :email :direct]]
           ;; feed item fields
           [:item/feed-id           ? (r :feed) :uuid]
           [:item/feed-guid         ? ::string]
           ;; email item fields
           [:item/email-sub-id      (?r :sub) :uuid]
           [:item/email-raw-content-key ? :uuid]
           [:item/email-list-unsubscribe ? [:string {:max 5000}]]
           [:item/email-list-unsubscribe-post ? ::string]
           [:item/email-reply-to    ? ::string]
           [:item/email-maybe-confirmation ? :boolean]
           ;; direct item fields
           [:item/direct-candidate-status ? [:enum :ingest-failed :blocked :approved]]]

   :redirect [:map {:closed true}
               [:redirect/id       :uuid]
               [:redirect/url      ::string]
               [:redirect/item-id  (r :item) :uuid]]

   :user-item [:map {:closed true}
                [:user-item/id                  :uuid]
                [:user-item/user-id   (r :user) :uuid]
                [:user-item/item-id   (r :item) :uuid]
                [:user-item/viewed-at       ?   inst?]
                [:user-item/skipped-at      ?   inst?]
                [:user-item/bookmarked-at   ?   inst?]
                [:user-item/favorited-at    ?   inst?]
                [:user-item/disliked-at     ?   inst?]
                [:user-item/reported-at     ?   inst?]
                [:user-item/report-reason   ?   ::string]]

   :digest [:map {:closed true}
             [:digest/id                        :uuid]
             [:digest/user-id     (r :user)   :uuid]
             [:digest/sent-at                   inst?]
             [:digest/subject-id  (?r :item)  :uuid]
             [:digest/ad-id       (?r :ad)    :uuid]
             [:digest/bulk-send-id (?r :bulk-send) :uuid]]

   :digest-item [:map {:closed true}
                  [:digest-item/id                  :uuid]
                  [:digest-item/digest-id (r :digest) :uuid]
                  [:digest-item/item-id   (r :item)   :uuid]
                  [:digest-item/kind      [:enum :icymi :discover]]]

   :bulk-send [:map {:closed true}
                [:bulk-send/id              :uuid]
                [:bulk-send/sent-at         inst?]
                [:bulk-send/payload-size    :int]
                [:bulk-send/mailersend-id   :string]
                [:bulk-send/digests         [:vector :uuid]]]

   :reclist [:map {:closed true}
              [:reclist/id                   :uuid]
              [:reclist/user-id    (r :user) :uuid]
              [:reclist/created-at           inst?]
              [:reclist/clicked              [:set :uuid]]]

   :skip [:map {:closed true}
           [:skip/id                      :uuid]
           [:skip/reclist-id (r :reclist) :uuid]
           [:skip/item-id    (r :item)    :uuid]]

   :ad [:map {:closed true}
         [:ad/id                     :uuid]
         [:ad/user-id      (r :user) :uuid]
         [:ad/approve-state          [:enum :pending :approved :rejected]]
         [:ad/updated-at             inst?]
         [:ad/balance                :int]
         [:ad/recent-cost            :int]
         [:ad/bid            ?       :int]
         [:ad/budget         ?       :int]
         [:ad/url            ?       ::string]
         [:ad/title          ?       [:string {:max 75}]]
         [:ad/description    ?       [:string {:max 250}]]
         [:ad/image-url      ?       ::string]
         [:ad/paused         ?       :boolean]
         [:ad/payment-failed ?       :boolean]
         [:ad/customer-id    ?       :string]
         [:ad/session-id     ?       :string]
         [:ad/payment-method ?       :string]
         [:ad/card-details   ?       [:map {:closed true}
                                      [:brand     :string]
                                      [:last4     :string]
                                      [:exp-year  :int]
                                      [:exp-month :int]]]]

   :ad-click [:map {:closed true}
               [:ad-click/id                      :uuid]
               [:ad-click/user-id       (r :user) :uuid]
               [:ad-click/ad-id         (r :ad)   :uuid]
               [:ad-click/created-at              inst?]
               [:ad-click/cost                    :int]
               [:ad-click/source                  [:enum :web :email]]]

   :ad-credit [:map {:closed true}
                [:ad-credit/id                     :uuid]
                [:ad-credit/ad-id         (r :ad) :uuid]
                [:ad-credit/source                 [:enum :charge :manual]]
                [:ad-credit/amount                 :int]
                [:ad-credit/created-at             inst?]
                [:ad-credit/charge-status ?        [:enum :pending :confirmed :failed]]]

   :mv-sub [:map {:closed true}
             [:mv-sub/id                     :uuid]
             [:mv-sub/sub-id       (r :sub) :uuid]
             [:mv-sub/affinity-low     ?     :double]
             [:mv-sub/affinity-high    ?     :double]
             [:mv-sub/last-published   ?     inst?]
             [:mv-sub/unread           ?     :int]
             [:mv-sub/read             ?     :int]]

   :mv-user [:map {:closed true}
              [:mv-user/id                        :uuid]
              [:mv-user/user-id        (r :user) :uuid]
              [:mv-user/current-item-id (?r :item) :uuid]]

   :deleted-user [:map {:closed true}
                   [:deleted-user/id                    :uuid]
                   [:deleted-user/email-username-hash   :string]]})

;; ============================================================================
;; Malli Registry and Options
;; ============================================================================

(def malli-opts
  {:registry (malr/composite-registry
              (malli/default-schemas)
              schema)})

;; ============================================================================
;; Schema Info Extraction
;; ============================================================================

(defn- table-id-key
  "Get the ID key for a table (e.g., :user -> :user/id)"
  [table-key]
  (keyword (name table-key) "id"))

(defn table-ast?
  "Check if an AST represents a table (has an id column)."
  [table-key ast]
  (and (= :map (:type ast))
       (contains? (:keys ast) (table-id-key table-key))))

(defn deref-ast
  "Dereference a schema and get its AST."
  [schema malli-opts]
  (some-> (try (malli/deref-recursive schema malli-opts) (catch Exception _))
          malli/ast))

(defn table-asts
  "Get all table ASTs from a schema."
  [table-key malli-opts]
  (when-let [ast (deref-ast table-key malli-opts)]
    (if (table-ast? table-key ast)
      [ast]
      (->> (tree-seq (constantly true) :children ast)
           (filterv #(table-ast? table-key %))))))

(defn- attr-union [m1 m2]
  (let [shared-keys (into [] (filter #(contains? m2 %)) (keys m1))]
    (when-some [conflicting-attr (first (filter #(not= (m1 %) (m2 %)) shared-keys))]
      (throw (ex-info "An attribute has a conflicting definition"
                      {:attr conflicting-attr
                       :definition-1 (m1 conflicting-attr)
                       :definition-2 (m2 conflicting-attr)})))
    (merge m1 m2)))

(defn schema-info
  "Extract schema info: map of table-key -> attrs map."
  [malli-opts]
  (into {}
        (keep (fn [schema-k]
                (let [attrs (->> (table-asts schema-k malli-opts)
                                 (mapv :keys)
                                 (reduce attr-union {}))]
                  (when (not-empty attrs)
                    [schema-k attrs]))))
        (keys (malr/schemas (:registry malli-opts)))))

;; ============================================================================
;; Type Inference from Malli
;; ============================================================================

(defn- get-schema-type
  "Get the type from an attribute AST, handling nested value structures."
  [ast]
  (or (:type ast)
      (get-in ast [:value :type])))

(defn- infer-sqlite-type
  "Infer SQLite type from malli AST. Throws if type cannot be determined."
  [attr-key ast]
  (let [type-val (get-schema-type ast)]
    ;; Note: inst? here is a symbol (from malli schema), not the function.
    ;; case treats test constants as literals, so this matches the symbol 'inst?
    (case type-val
      :uuid "BLOB"
      :string "TEXT"
      :int "INT"
      :double "REAL"
      :boolean "INT"
      :set "BLOB"
      :vector "BLOB"
      :map "BLOB"
      :enum "INT"
      inst? "INT"  ; inst? maps to epoch milliseconds
      (throw (ex-info (str "Cannot infer SQLite type for attribute " attr-key
                           ". Please use a supported malli type: :uuid, :string, :int, :double, "
                           ":boolean, :set, :vector, :map, :enum, or inst?")
                      {:attr attr-key :malli-type type-val})))))

(defn- get-value-ast
  "Get the nested value AST if present, or the AST itself."
  [ast]
  (or (:value ast) ast))

(defn- extract-enum-values
  "Extract enum values from a malli AST, returning map of {0 :val1, 1 :val2, ...}"
  [ast]
  (let [value-ast (get-value-ast ast)]
    (when (= :enum (:type value-ast))
      (into {} (map-indexed (fn [i v] [i v]) (:children value-ast))))))

(defn- infer-coercion-type
  "Infer the coercion type for an attribute from its malli AST."
  [malli-opts attr-key ast attr-schema]
  (let [type-val (get-schema-type ast)]
    ;; Note: inst? here is a symbol (from malli schema), not the function.
    ;; case treats test constants as literals, so this matches the symbol 'inst?
    (case type-val
      :uuid :uuid
      :boolean :bool
      :set :nippy
      :vector :nippy
      :map :nippy
      :enum {:enum (extract-enum-values ast)}
      inst? :inst
      nil)))

;; ============================================================================
;; Type Coercion: SQLite -> Clojure
;; ============================================================================

(defn bytes->uuid
  "Convert a 16-byte array back to a UUID."
  [^bytes byte-array]
  (when byte-array
    (let [bb (ByteBuffer/wrap byte-array)]
      (UUID. (.getLong bb) (.getLong bb)))))

(defn epoch-ms->inst
  "Convert epoch milliseconds to an Instant."
  [ms]
  (when ms
    (Instant/ofEpochMilli ms)))

(defn int->bool
  "Convert 0/1 integer to boolean. Throws for unexpected values."
  [n]
  (when (some? n)
    (case n
      0 false
      1 true
      (throw (ex-info "Invalid boolean value, expected 0 or 1"
                      {:value n})))))

(defn fast-thaw
  "Thaw a nippy-frozen blob using fast-thaw."
  [blob]
  (when blob
    (nippy/fast-thaw blob)))

(defn make-enum-reader
  "Create an enum reader function from a db-value->clojure-value map."
  [enum-map]
  (fn [db-val]
    (when (some? db-val)
      (or (get enum-map db-val)
          (throw (ex-info "Unknown enum value"
                          {:value db-val
                           :available-values enum-map}))))))

;; ============================================================================
;; Coercion
;; ============================================================================

(defn uuid->bytes
  "Convert a UUID to a 16-byte array for SQLite BLOB storage."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn inst->epoch-ms
  "Convert an Instant to epoch milliseconds."
  [x]
  (when x
    (.toEpochMilli ^Instant x)))

(defn bool->int
  "Convert a boolean to 0 or 1 for SQLite."
  [b]
  (if b 1 0))

(defn fast-freeze
  "Freeze a value using nippy fast-freeze."
  [v]
  (when v
    (nippy/fast-freeze v)))

(defn make-enum-writer
  "Create an enum writer from a clojure-value->db-value map."
  [enum-map]
  (let [reverse-map (into {} (map (fn [[k v]] [v k]) enum-map))]
    (fn [clj-val]
      (when (some? clj-val)
        (or (get reverse-map clj-val)
            (throw (ex-info "Unknown enum value for write"
                            {:value clj-val
                             :available-values reverse-map})))))))

(defn- get-coerce-read-fn
  "Get the read coercion function for a coercion type."
  [coerce-type]
  (case coerce-type
    :uuid bytes->uuid
    :inst epoch-ms->inst
    :bool int->bool
    :nippy fast-thaw
    (when (map? coerce-type)
      (when-let [enum-map (:enum coerce-type)]
        (make-enum-reader enum-map)))))

(defn- get-coerce-write-fn
  "Get the write coercion function for a coercion type."
  [coerce-type]
  (case coerce-type
    :uuid uuid->bytes
    :inst inst->epoch-ms
    :bool bool->int
    :nippy fast-freeze
    (when (map? coerce-type)
      (when-let [enum-map (:enum coerce-type)]
        (make-enum-writer enum-map)))))

(defn build-coercions
  "Build coercion maps for a table's attributes.
   Returns {:read {attr coerce-fn} :write {attr coerce-fn}}"
  [table-key attrs malli-opts]
  (let [id-key (table-id-key table-key)]
    (reduce
     (fn [acc [attr ast]]
       (let [attr-schema (:value ast)
             coerce-type (infer-coercion-type malli-opts attr ast attr-schema)]
         (if coerce-type
           (let [read-fn (get-coerce-read-fn coerce-type)
                 write-fn (get-coerce-write-fn coerce-type)]
             (cond-> acc
               read-fn (assoc-in [:read attr] read-fn)
               write-fn (assoc-in [:write attr] write-fn)))
           acc)))
     {:read {} :write {}}
     attrs)))

(defn make-column-reader
  "Create a custom column reader that applies coercions.
   Coercions is a map of column-keyword -> coerce-fn."
  [table-key coercions]
  (let [id-key (table-id-key table-key)]
    (fn column-reader [builder ^ResultSet rs ^Integer i]
      (let [col-kw (nth (:cols builder) (dec i))
            ;; Map :id to :table/id
            attr (if (= col-kw :id)
                   id-key
                   (keyword (name table-key) (name col-kw)))
            value (.getObject rs i)
            coerce-fn (get coercions attr)
            coerced-value (if (and coerce-fn (some? value))
                            (coerce-fn value)
                            value)]
        (rs/read-column-by-index coerced-value (:rsmeta builder) i)))))

;; ============================================================================
;; SQLite DDL Generation
;; ============================================================================

(defn- attr->column-name
  "Convert an attribute keyword to SQLite column name."
  [table-key attr]
  (let [id-key (table-id-key table-key)]
    (if (= attr id-key)
      "id"
      (str/replace (name attr) "-" "_"))))

(defn- generate-column-def
  "Generate a single column definition."
  [table-key malli-opts [attr ast]]
  (let [col-name (attr->column-name table-key attr)
        col-type (infer-sqlite-type attr ast)
        optional? (get-in ast [:properties :optional])
        id-key (table-id-key table-key)
        is-primary? (= attr id-key)
        ;; Handle enums
        enum-map (extract-enum-values ast)
        check-constraint (when enum-map
                           (str " CHECK (" col-name " IN ("
                                (str/join ", " (keys enum-map))
                                "))"))
        enum-comment (when enum-map
                       (str " -- " (str/join ", " (map (fn [[k v]] (str (name v) " (" k ")"))
                                                       (sort-by key enum-map)))))]
    (str col-name " " col-type
         (when is-primary? " PRIMARY KEY")
         (when (and (not optional?) (not is-primary?)) " NOT NULL")
         check-constraint
         enum-comment)))

(defn- generate-foreign-keys
  "Generate FOREIGN KEY constraints for a table."
  [table-key attrs]
  (into []
        (keep (fn [[attr ast]]
                (when-let [ref-target (get-in ast [:properties :biff/ref])]
                  (let [col-name (attr->column-name table-key attr)]
                    (str "FOREIGN KEY(" col-name ") REFERENCES " (name ref-target) "(id)")))))
        attrs))

(defn generate-create-table
  "Generate a CREATE TABLE statement for a table."
  [malli-opts table-key attrs]
  (let [col-defs (mapv #(generate-column-def table-key malli-opts %) attrs)
        fk-constraints (generate-foreign-keys table-key attrs)
        all-lines (concat col-defs fk-constraints)]
    (str "CREATE TABLE " (str/replace (name table-key) "-" "_") " (\n  "
         (str/join ",\n  " all-lines)
         "\n) STRICT;")))

(defn- infer-table-order
  "Infer table creation order from foreign key dependencies using topological sort.
   Tables without dependencies are appended at the end."
  [info]
  (let [tables (set (keys info))
        graph (reduce (fn [g table-key]
                        (let [attrs (get info table-key)
                              refs (->> (vals attrs)
                                        (keep #(get-in % [:properties :biff/ref]))
                                        (distinct))]
                          (reduce (fn [g ref-table]
                                    (dep/depend g table-key ref-table))
                                  g
                                  refs)))
                      (dep/graph)
                      tables)
        sorted (dep/topo-sort graph)
        sorted-set (set sorted)]
    (into (filterv tables sorted)
          (remove sorted-set tables))))

(defn generate-schema-sql
  "Generate the complete schema.sql from malli schema."
  ([]
   (generate-schema-sql malli-opts))
  ([malli-opts]
   (let [info (schema-info malli-opts)
         table-order (infer-table-order info)]
     (str "-- Generated from malli schema\n"
          "-- test:    `sqlite3def storage/sqlite/test.db --dry-run -f resources/schema.sql`\n"
          "-- migrate: `sqlite3def storage/sqlite/test.db --apply -f resources/schema.sql`\n\n"
          (str/join "\n\n"
                    (for [table table-order
                          :let [attrs (get info table)]
                          :when attrs]
                      (generate-create-table malli-opts table attrs)))))))

;; ============================================================================
;; SQLite Pathom Resolvers
;; ============================================================================

(def table-whitelist
  "Tables that should have resolvers generated."
  #{:feed :ad-credit :bulk-send :mv-sub :user-item :sub :digest-item
    :item :mv-user :digest :reclist :ad-click :deleted-user :redirect :ad :user :skip})

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

(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables from malli schema.

   For reference attributes ending in -id:
   - Returns the raw ID as :table/ref-id
   - Also returns a join without the -id suffix as {:ref-table/id uuid}

   Example: {:ad/user-id #uuid \"...\", :ad/user {:user/id #uuid \"...\"}}"
  ([]
   (sqlite-resolvers malli-opts))
  ([malli-opts]
   (for [[table-key attrs] (schema-info malli-opts)
         :let [id-key (table-id-key table-key)
               coercions (build-coercions table-key attrs malli-opts)
               read-coercions (:read coercions)
               column-reader (make-column-reader table-key read-coercions)

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
                   (fn [{:keys [biff/conn]} inputs]
                     (let [ids (mapv id-key inputs)
                           id-write-fn (get-in coercions [:write id-key])
                           db-ids (if id-write-fn
                                    (mapv id-write-fn ids)
                                    ids)
                           sql-map {:select :*
                                    :from table-key
                                    :where [:in :id db-ids]}
                           raw-results (jdbc/execute! conn
                                                      (sql/format sql-map)
                                                      {:builder-fn (rs/builder-adapter
                                                                    rs/as-unqualified-kebab-maps
                                                                    column-reader)})
                           ;; Post-process to add join keys
                           process-row (fn [row]
                                         (reduce
                                          (fn [row [ref-attr target]]
                                            (let [target-id-key (table-id-key target)
                                                  join-key (strip-id-suffix ref-attr)
                                                  ref-val (get row ref-attr)]
                                              (assoc row join-key (when (some? ref-val)
                                                                    {target-id-key ref-val}))))
                                          row
                                          ref-attrs))
                           results (mapv process-row raw-results)
                           id->result (into {} (map (juxt id-key identity)) results)]
                       (mapv (fn [input]
                               (let [id (get input id-key)]
                                 (-> (get id->result id {})
                                     lib.core/some-vals
                                     (assoc id-key id))))
                             inputs)))))))

