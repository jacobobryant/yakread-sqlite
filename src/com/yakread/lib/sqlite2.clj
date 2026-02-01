(ns com.yakread.lib.sqlite2
  "SQLite integration using malli schema as the source of truth.
   
   This namespace provides an alternative approach where:
   1. Malli schema is the source of truth (with SQLite-compatible attribute names)
   2. SQLite DDL is generated from the malli schema
   3. Pathom resolvers are generated from the malli schema (like xtdb2-resolvers)
   
   Note: next.jdbc automatically converts underscores to hyphens in column names,
   and honeysql converts hyphens to underscores. So attribute names use hyphens
   but map to underscore column names in SQLite."
  (:require
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.wsscode.pathom3.connect.runner :as-alias pcr]
   [com.yakread.lib.core :as lib.core]
   [com.biffweb.experimental :as biffx]
   [honey.sql :as sql]
   [malli.core :as malli]
   [malli.registry :as malr]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.nippy :as nippy]
   [tick.core :as tick])
  (:import
   [java.nio ByteBuffer]
   [java.time Instant LocalTime ZonedDateTime]
   [java.util UUID]))

;; ============================================================================
;; SQLite Malli Schema
;; ============================================================================
;; Attributes are named to match SQLite columns (with hyphens instead of underscores
;; since next.jdbc/honeysql handle the conversion automatically)

(defn table [& args]
  (let [[options map-args] (if (map? (first args))
                             [(first args) (rest args)]
                             [{} args])
        map-schema (into [:map (merge {:closed true} options)] map-args)]
    (if-some [prefix-fn (:biff/prefixed-by options)]
      [:and
       map-schema
       [:fn {:error/message ":xt/id should be prefixed properly"}
        (fn [m]
          (= (:xt/id m)
             (biffx/prefix-uuid (prefix-fn m) (:xt/id m))))]]
      map-schema)))

(defn inherit [base-schema & table-args]
  [:merge base-schema (apply table table-args)])

(def ? {:optional true})
(defn r [target] {:biff/ref (if (coll? target) target #{target})})
(defn ?r [target] (assoc (r target) :optional true))

;; Type markers for SQLite type inference
(def ::string  [:string {:max 2000 :sqlite/type :text}])
(def ::day     [:enum :sunday :monday :tuesday :wednesday :thursday :friday :saturday])
(def ::cents   [:int {:sqlite/type :int}])
(def ::zdt     [:fn {:sqlite/type :int :sqlite/coerce :zdt} tick/zoned-date-time?])
(def ::nippy   [:any {:sqlite/type :blob :sqlite/coerce :nippy}])

;; SQLite schema definition with attribute names matching SQLite columns
;; (using hyphens which next.jdbc/honeysql convert to/from underscores)
(def schema
  {::string  ::string
   ::day     ::day
   ::cents   ::cents
   ::zdt     ::zdt

   :user (table
           [:xt/id                   :uuid]
           [:user/email              ::string]
           [:user/roles            ? [:set {:sqlite/coerce :nippy} [:enum :admin]]]
           [:user/joined-at        ? ::zdt]
           [:user/digest-days      ? [:set {:sqlite/coerce :nippy} ::day]]
           [:user/send-digest-at   ? [:fn {:sqlite/type :text :sqlite/coerce :local-time}
                                      #(instance? LocalTime %)]]
           [:user/timezone         ? ::string]
           [:user/digest-last-sent ? ::zdt]
           [:user/from-the-sample  ? [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
           [:user/use-original-links ? [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
           [:user/suppressed-at    ? ::zdt]
           [:user/email-username   ? ::string]
           [:user/customer-id      ? :string]
           [:user/plan             ? [:enum {:sqlite/type :int :sqlite/enum {0 :quarter 1 :annual}}
                                      :quarter :annual]]
           [:user/cancel-at        ? ::zdt])

   :feed (table
           [:xt/id              :uuid]
           [:feed/url           ::string]
           [:feed/synced-at   ? ::zdt]
           [:feed/title       ? ::string]
           [:feed/description ? ::string]
           [:feed/image-url   ? ::string]
           [:feed/etag        ? ::string]
           [:feed/last-modified ? ::string]
           [:feed/failed-syncs ? :int]
           [:feed/moderation  ? [:enum {:sqlite/type :int :sqlite/enum {0 :approved 1 :blocked}}
                                 :approved :blocked]])

   :sub (table
          {:biff/prefixed-by :sub/user}
          [:xt/id                   :uuid]
          [:sub/user      (r :user) :uuid]
          [:sub/created-at          ::zdt]
          [:sub/pinned-at ?         ::zdt]
          ;; record-type discriminator: 0=feed, 1=email
          [:sub/record-type         [:enum {:sqlite/type :int :sqlite/enum {0 :feed 1 :email}}
                                     :feed :email]]
          ;; feed sub fields
          [:sub/feed-id   ?  (r :feed) :uuid]
          ;; email sub fields
          [:sub/email-from        ? ::string]
          [:sub/email-unsubscribed-at ? ::zdt])

   :item (table
           [:xt/id                :uuid]
           [:item/ingested-at     ::zdt]
           [:item/title         ? ::string]
           [:item/url           ? ::string]
           [:item/redirect-urls ? [:set {:sqlite/coerce :nippy} ::string]]
           [:item/content       ? ::string]
           [:item/content-key   ? :uuid]
           [:item/published-at  ? ::zdt]
           [:item/excerpt       ? ::string]
           [:item/author-name   ? ::string]
           [:item/author-url    ? ::string]
           [:item/feed-url      ? ::string]
           [:item/lang          ? ::string]
           [:item/site-name     ? ::string]
           [:item/byline        ? ::string]
           [:item/length        ? :int]
           [:item/image-url     ? ::string]
           [:item/paywalled     ? [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
           ;; record-type discriminator: 0=feed, 1=email, 2=direct
           [:item/record-type      [:enum {:sqlite/type :int :sqlite/enum {0 :feed 1 :email 2 :direct}}
                                    :feed :email :direct]]
           ;; feed item fields
           [:item/feed-id       ? (r :feed) :uuid]
           [:item/feed-guid     ? ::string]
           ;; email item fields
           [:item/email-sub-id  ? (r :sub) :uuid]
           [:item/email-raw-content-key ? :uuid]
           [:item/email-list-unsubscribe ? [:string {:max 5000}]]
           [:item/email-list-unsubscribe-post ? ::string]
           [:item/email-reply-to ? ::string]
           [:item/email-maybe-confirmation ? [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
           ;; direct item fields
           [:item/direct-candidate-status ? [:enum {:sqlite/type :int 
                                                    :sqlite/enum {0 :ingest-failed 1 :blocked 2 :approved}}
                                             :ingest-failed :blocked :approved]])

   :redirect (table
               [:xt/id         :uuid]
               [:redirect/url  ::string]
               [:redirect/item-id (r :item) :uuid])

   :user-item (table
                {:biff/prefixed-by :user-item/user}
                [:xt/id                        :uuid]
                [:user-item/user     (r :user) :uuid]
                [:user-item/item     (r :item) :uuid]
                [:user-item/viewed-at     ?    ::zdt]
                [:user-item/skipped-at    ?    ::zdt]
                [:user-item/bookmarked-at ?    ::zdt]
                [:user-item/favorited-at  ?    ::zdt]
                [:user-item/disliked-at   ?    ::zdt]
                [:user-item/reported-at   ?    ::zdt]
                [:user-item/report-reason ?    ::string])

   :digest (table
             {:biff/prefixed-by :digest/user}
             [:xt/id                          :uuid]
             [:digest/user     (r :user)      :uuid]
             [:digest/sent-at                 ::zdt]
             [:digest/subject-id (?r :item)   :uuid]
             [:digest/ad-id      (?r :ad)     :uuid]
             [:digest/bulk-send-id (?r :bulk-send) :uuid])

   :digest-item (table
                  {:biff/prefixed-by :digest-item/item}
                  [:xt/id               :uuid]
                  [:digest-item/digest-id (r :digest) :uuid]
                  [:digest-item/item-id (r :item) :uuid]
                  [:digest-item/kind    [:enum {:sqlite/type :int :sqlite/enum {0 :icymi 1 :discover}}
                                         :icymi :discover]])

   :bulk-send (table
                [:xt/id                    :uuid]
                [:bulk-send/sent-at        ::zdt]
                [:bulk-send/payload-size   :int]
                [:bulk-send/mailersend-id  :string]
                [:bulk-send/digests        [:vector {:sqlite/coerce :nippy} :uuid]])

   :reclist (table
              {:biff/prefixed-by :reclist/user}
              [:xt/id                     :uuid]
              [:reclist/user    (r :user) :uuid]
              [:reclist/created-at        ::zdt]
              [:reclist/clicked           [:set {:sqlite/coerce :nippy} :uuid]])

   :skip (table
           {:biff/prefixed-by :skip/reclist}
           [:xt/id                        :uuid]
           [:skip/reclist-id (r :reclist) :uuid]
           [:skip/item-id    (r :item)    :uuid])

   :ad (table
         {:biff/prefixed-by :ad/user}
         [:xt/id                     :uuid]
         [:ad/user         (r :user) :uuid]
         [:ad/approve-state          [:enum {:sqlite/type :int
                                             :sqlite/enum {0 :pending 1 :approved 2 :rejected}}
                                      :pending :approved :rejected]]
         [:ad/updated-at             ::zdt]
         [:ad/balance                ::cents]
         [:ad/recent-cost            ::cents]
         [:ad/bid          ?         ::cents]
         [:ad/budget       ?         ::cents]
         [:ad/url          ?         ::string]
         [:ad/title        ?         [:string {:max 75}]]
         [:ad/description  ?         [:string {:max 250}]]
         [:ad/image-url    ?         ::string]
         [:ad/paused       ?         [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
         [:ad/payment-failed ?       [:boolean {:sqlite/type :int :sqlite/coerce :bool}]]
         [:ad/customer-id  ?         :string]
         [:ad/session-id   ?         :string]
         [:ad/payment-method ?       :string]
         [:ad/card-details ?         [:map {:closed true :sqlite/coerce :nippy}
                                      [:brand     :string]
                                      [:last4     :string]
                                      [:exp-year  :int]
                                      [:exp-month :int]]])

   :ad-click (table
               {:biff/prefixed-by :ad-click/ad}
               [:xt/id                        :uuid]
               [:ad-click/user      (r :user) :uuid]
               [:ad-click/ad        (r :ad)   :uuid]
               [:ad-click/created-at          ::zdt]
               [:ad-click/cost                ::cents]
               [:ad-click/source              [:enum {:sqlite/type :int :sqlite/enum {0 :web 1 :email}}
                                               :web :email]])

   :ad-credit (table
                {:biff/prefixed-by :ad-credit/ad}
                [:xt/id                       :uuid]
                [:ad-credit/ad      (r :ad)   :uuid]
                [:ad-credit/source            [:enum {:sqlite/type :int :sqlite/enum {0 :charge 1 :manual}}
                                               :charge :manual]]
                [:ad-credit/amount            ::cents]
                [:ad-credit/created-at        ::zdt]
                [:ad-credit/charge-status ?   [:enum {:sqlite/type :int
                                                      :sqlite/enum {0 :pending 1 :confirmed 2 :failed}}
                                               :pending :confirmed :failed]])

   :mv-sub (table
             {:biff/prefixed-by :mv-sub/sub}
             [:xt/id                       :uuid]
             [:mv-sub/sub        (r :sub)  :uuid]
             [:mv-sub/affinity-low   ?     :double]
             [:mv-sub/affinity-high  ?     :double]
             [:mv-sub/last-published ?     ::zdt]
             [:mv-sub/unread         ?     :int]
             [:mv-sub/read           ?     :int])

   :mv-user (table
              {:biff/prefixed-by :mv-user/user}
              [:xt/id                          :uuid]
              [:mv-user/user       (r :user)   :uuid]
              [:mv-user/current-item-id (?r :item) :uuid])

   :deleted-user (table
                   [:xt/id                         :uuid]
                   [:deleted-user/email-username-hash :string])})

;; ============================================================================
;; Malli Registry and Options
;; ============================================================================

(def malli-opts
  {:registry (malr/composite-registry
              (malli/default-schemas)
              (malr/mutable-registry schema))})

;; ============================================================================
;; Schema Info Extraction (from biff-staging)
;; ============================================================================

(defn table-ast? [ast]
  (and (= :map (:type ast))
       (contains? (:keys ast) :xt/id)))

(defn deref-ast [schema malli-opts]
  (some-> (try (malli/deref-recursive schema malli-opts) (catch Exception _))
          malli/ast))

(defn table-asts [schema malli-opts]
  (->> (deref-ast schema malli-opts)
       (tree-seq (constantly true) :children)
       (filterv table-ast?)))

(defn- attr-union [m1 m2]
  (let [shared-keys (into [] (filter #(contains? m2 %)) (keys m1))]
    (when-some [conflicting-attr (first (filter #(not= (m1 %) (m2 %)) shared-keys))]
      (throw (ex-info "An attribute has a conflicting definition"
                      {:attr conflicting-attr
                       :definition-1 (m1 conflicting-attr)
                       :definition-2 (m2 conflicting-attr)})))
    (merge m1 m2)))

(defn schema-info [malli-opts]
  (into {}
        (keep (fn [schema-k]
                (let [attrs (->> (table-asts schema-k malli-opts)
                                 (mapv :keys)
                                 (reduce attr-union {}))]
                  (when (not-empty attrs)
                    [schema-k attrs]))))
        (keys (malr/schemas (:registry malli-opts)))))

;; ============================================================================
;; Type Coercion: SQLite -> Clojure
;; ============================================================================

(defn bytes->uuid
  "Convert a 16-byte array back to a UUID."
  [^bytes byte-array]
  (when byte-array
    (let [bb (ByteBuffer/wrap byte-array)]
      (UUID. (.getLong bb) (.getLong bb)))))

(defn uuid->bytes
  "Convert a UUID to a 16-byte array for SQLite BLOB storage."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn epoch-ms->instant
  "Convert epoch milliseconds to an Instant."
  [ms]
  (when ms
    (Instant/ofEpochMilli ms)))

(defn epoch-ms->zdt
  "Convert epoch milliseconds to a ZonedDateTime in UTC."
  [ms]
  (when ms
    (tick/in (epoch-ms->instant ms) "UTC")))

(defn int->bool
  "Convert 0/1 integer to boolean."
  [n]
  (when (some? n)
    (case n
      0 false
      1 true)))

(defn str->local-time
  "Parse a string to LocalTime."
  [s]
  (when s
    (LocalTime/parse s)))

(defn thaw-blob
  "Thaw a nippy-frozen blob."
  [blob]
  (when blob
    (nippy/thaw blob)))

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
;; Type Coercion: Clojure -> SQLite
;; ============================================================================

(defn instant->epoch-ms
  "Convert an Instant or ZonedDateTime to epoch milliseconds."
  [x]
  (cond
    (instance? Instant x) (.toEpochMilli ^Instant x)
    (instance? ZonedDateTime x) (.toEpochMilli (.toInstant ^ZonedDateTime x))
    :else x))

(defn bool->int
  "Convert a boolean to 0 or 1 for SQLite."
  [b]
  (if b 1 0))

;; ============================================================================
;; Coercion Configuration from Malli Schema
;; ============================================================================

(defn- get-sqlite-coerce
  "Get the :sqlite/coerce value from a malli schema properties, if any."
  [malli-opts attr-schema]
  (let [schema (try (malli/deref-recursive attr-schema malli-opts) (catch Exception _ attr-schema))
        ast (malli/ast schema)
        props (:properties ast)]
    (or (:sqlite/coerce props)
        ;; Check if it's an enum with sqlite/enum mapping
        (when (:sqlite/enum props)
          {:enum (:sqlite/enum props)}))))

(defn- get-coerce-fn
  "Get the coercion function for an attribute based on its malli schema."
  [coerce-type]
  (cond
    (= coerce-type :zdt) epoch-ms->zdt
    (= coerce-type :bool) int->bool
    (= coerce-type :nippy) thaw-blob
    (= coerce-type :local-time) str->local-time
    (map? coerce-type) (when-let [enum-map (:enum coerce-type)]
                         (make-enum-reader enum-map))
    :else nil))

(defn- build-attr-coercions
  "Build a map of attribute -> coercion-fn from the schema info."
  [attrs malli-opts]
  (into {}
        (keep (fn [[attr ast]]
                (let [;; Check if it's a UUID (BLOB storage)
                      type-val (:type ast)
                      is-uuid? (= type-val :uuid)
                      ;; Get explicit sqlite coerce
                      coerce-type (get-sqlite-coerce malli-opts (:value ast))]
                  (cond
                    is-uuid? [attr bytes->uuid]
                    coerce-type (when-let [f (get-coerce-fn coerce-type)]
                                  [attr f])
                    :else nil))))
        attrs))

;; ============================================================================
;; SQLite DDL Generation
;; ============================================================================

(defn- malli-type->sqlite-type
  "Convert a malli type to SQLite type."
  [malli-opts attr-key ast]
  (let [type-val (:type ast)
        props (:properties ast)
        explicit-type (:sqlite/type props)]
    (cond
      explicit-type (str/upper-case (name explicit-type))
      (= type-val :uuid) "BLOB"
      (= type-val :string) "TEXT"
      (= type-val :int) "INT"
      (= type-val :double) "REAL"
      (= type-val :boolean) "INT"
      (= type-val :set) "BLOB"
      (= type-val :vector) "BLOB"
      (= type-val :map) "BLOB"
      (= type-val :enum) "INT"
      (= type-val :fn) (or (some-> props :sqlite/type name str/upper-case) "TEXT")
      :else "TEXT")))

(defn- attr->column-name
  "Convert an attribute keyword to SQLite column name."
  [attr]
  (let [n (name attr)]
    (if (= attr :xt/id)
      "id"
      (str/replace n "-" "_"))))

(defn- generate-column-def
  "Generate a single column definition."
  [malli-opts [attr ast]]
  (let [col-name (attr->column-name attr)
        col-type (malli-type->sqlite-type malli-opts attr ast)
        optional? (get-in ast [:properties :optional])
        is-primary? (= attr :xt/id)
        props (:properties ast)
        ;; Generate CHECK constraint for enums
        enum-map (:sqlite/enum props)
        check-constraint (when enum-map
                           (str " CHECK (" col-name " IN ("
                                (str/join ", " (keys enum-map))
                                "))"))
        ;; Generate comment for enum values
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
  [attrs]
  (into []
        (keep (fn [[attr ast]]
                (when-let [ref (get-in ast [:properties :biff/ref])]
                  (let [col-name (attr->column-name attr)
                        ref-table (first (if (set? ref) ref [ref]))]
                    (str "FOREIGN KEY(" col-name ") REFERENCES " (name ref-table) "(id)")))))
        attrs))

(defn generate-create-table
  "Generate a CREATE TABLE statement for a table."
  [malli-opts table-key attrs]
  (let [col-defs (mapv #(generate-column-def malli-opts %) attrs)
        fk-constraints (generate-foreign-keys attrs)
        all-lines (concat col-defs fk-constraints)]
    (str "CREATE TABLE " (name table-key) " (\n  "
         (str/join ",\n  " all-lines)
         "\n) STRICT;")))

(defn generate-schema-sql
  "Generate the complete schema.sql from malli schema."
  ([]
   (generate-schema-sql malli-opts))
  ([malli-opts]
   (let [info (schema-info malli-opts)
         ;; Order tables to handle foreign key dependencies
         table-order [:user :feed :sub :item :redirect :user-item :bulk-send :digest 
                      :digest-item :reclist :skip :ad :ad-click :ad-credit 
                      :mv-sub :mv-user :deleted-user]]
     (str "-- Generated from malli schema\n"
          "-- test:    `sqlite3def storage/sqlite/test.db --dry-run -f resources/schema.sql`\n"
          "-- migrate: `sqlite3def storage/sqlite/test.db --apply -f resources/schema.sql`\n\n"
          (str/join "\n\n"
                    (for [table table-order
                          :let [attrs (get info table)]
                          :when attrs]
                      (generate-create-table malli-opts table attrs)))))))

;; ============================================================================
;; SQLite Pathom Resolvers (similar to xtdb2-resolvers)
;; ============================================================================

(def table-whitelist
  "Tables that should have resolvers generated."
  #{:feed :ad-credit :bulk-send :mv-sub :user-item :sub :digest-item
    :item :mv-user :digest :reclist :ad-click :deleted-user :redirect :ad :user :skip})

(defn- expects
  "Get the expected outputs from Pathom environment."
  [env]
  (-> env
      ::pcp/node
      ::pcp/expects
      keys
      vec))

(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables from malli schema.
   
   Similar to xtdb2-resolvers but adapted for SQLite:
   - Uses SELECT * since SQLite is row-oriented
   - Coerces values based on malli schema annotations
   - Wraps reference columns as {:xt/id value}"
  ([]
   (sqlite-resolvers malli-opts))
  ([malli-opts]
   (for [[schema-key attrs] (schema-info malli-opts)
         :when (contains? table-whitelist schema-key)
         :let [ref? (fn [attr]
                      (boolean (get-in attrs [attr :properties :biff/ref])))
               joinify (fn [[k v]]
                         (if (ref? k)
                           [k (when v {:xt/id v})]
                           [k v]))
               joinify-map (fn [m]
                             (into {} (map joinify) m))
               ;; Build coercion map
               attr-coercions (build-attr-coercions attrs malli-opts)
               ;; Add UUID coercion for :xt/id
               attr-coercions (assoc attr-coercions :xt/id bytes->uuid)
               op-name (symbol "com.yakread.lib.sqlite2"
                               (str (name schema-key) "-sqlite-resolver"))]]
     (pco/resolver op-name
                   {::pco/input [:xt/id]
                    ::pco/output (vec (for [k (keys attrs)
                                            :when (not= k :xt/id)]
                                        (if (ref? k)
                                          {k [:xt/id]}
                                          k)))
                    ::pco/batch? true}
                   (fn [{:keys [biff/db] :as env} inputs]
                     (let [ids (mapv :xt/id inputs)
                           id-bytes (mapv uuid->bytes ids)
                           ;; Use honeysql which converts hyphens to underscores
                           sql-map {:select :*
                                    :from schema-key
                                    :where [:in :id id-bytes]}
                           [sql-str & params] (sql/format sql-map)
                           ;; Use as-unqualified-kebab-maps which converts underscores to hyphens
                           raw-results (jdbc/execute! db (into [sql-str] params)
                                                      {:builder-fn rs/as-unqualified-kebab-maps})
                           ;; Apply coercions
                           coerce-row (fn [row]
                                        (reduce-kv
                                         (fn [result col-kw value]
                                           (let [;; Convert column name back to namespaced attr
                                                 attr (if (= col-kw :id)
                                                        :xt/id
                                                        (keyword (name schema-key) (name col-kw)))
                                                 ;; Apply coercion if available
                                                 coerce-fn (get attr-coercions attr)
                                                 coerced-value (if coerce-fn
                                                                 (coerce-fn value)
                                                                 value)]
                                             (assoc result attr coerced-value)))
                                         {}
                                         row))
                           results (mapv coerce-row raw-results)
                           id->result (into {} (map (juxt :xt/id identity)) results)]
                       (mapv (fn [{:keys [xt/id]}]
                               (-> (get id->result id {})
                                   lib.core/some-vals
                                   joinify-map
                                   (assoc :xt/id id)))
                             inputs)))))))
