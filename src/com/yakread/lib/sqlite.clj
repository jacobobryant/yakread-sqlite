(ns com.yakread.lib.sqlite
  "SQLite integration utilities including Pathom resolvers.
   
   This namespace provides:
   - Schema parsing from resources/schema.sql
   - Column metadata extraction including comment annotations
   - Coercion functions for reading SQLite values back to Clojure values
   - Pathom resolvers for SQLite tables (similar to xtdb2-resolvers)"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.planner :as-alias pcp]
   [com.yakread.lib.core :as lib.core]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.nippy :as nippy]
   [tick.core :as tick])
  (:import
   [java.nio ByteBuffer]
   [java.time Instant LocalTime ZonedDateTime]
   [java.util UUID]))

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
  [db->clj]
  (fn [db-val]
    (when (some? db-val)
      (or (get db->clj db-val)
          (throw (ex-info "Unknown enum value"
                          {:value db-val
                           :available-values db->clj}))))))

;; ============================================================================
;; Schema Parsing
;; ============================================================================

(defn- parse-column-line
  "Parse a single column definition line from a CREATE TABLE statement.
   Returns a map with :name, :type, :nullable, :comment, :foreign-key."
  [line table-name]
  (let [line (str/trim line)
        ;; Skip FOREIGN KEY and other non-column lines
        non-column? (or (str/starts-with? line "FOREIGN KEY")
                        (str/starts-with? line "PRIMARY KEY")
                        (str/starts-with? line "UNIQUE")
                        (str/starts-with? line "CHECK")
                        (str/blank? line)
                        (str/starts-with? line ")"))]
    (when-not non-column?
      (let [;; Parse column name and type
            [_ name type-and-rest] (re-matches #"(\w+)\s+(\S+.*)" line)
            ;; Extract comment if present
            [_ rest-no-comment comment] (if-let [m (re-matches #"(.*?)\s*--\s*(.*)" type-and-rest)]
                                          m
                                          [nil type-and-rest nil])
            ;; Parse type, modifiers, and CHECK constraint
            type-str (first (str/split rest-no-comment #"\s+"))
            nullable? (not (str/includes? rest-no-comment "NOT NULL"))
            ;; Check for enum via CHECK constraint
            check-match (re-find #"CHECK\s*\(\s*\w+\s+IN\s*\(([^)]+)\)" rest-no-comment)
            enum-values (when check-match
                          (->> (str/split (second check-match) #",")
                               (mapv #(Integer/parseInt (str/trim %)))))]
        (when name
          {:column-name name
           :column-type (keyword (str/lower-case type-str))
           :nullable? nullable?
           :comment comment
           :enum-values enum-values
           :table-name table-name})))))

(defn- parse-table-statement
  "Parse a CREATE TABLE statement and return table metadata."
  [sql-text]
  (let [;; Extract table name
        [_ table-name] (re-find #"CREATE TABLE\s+(\w+)" sql-text)
        ;; Extract column definitions between ( and )
        [_ columns-text] (re-find #"CREATE TABLE\s+\w+\s*\((.*)\)\s*STRICT" sql-text)
        ;; Split by comma (but be careful with CHECK constraints that contain commas)
        lines (when columns-text
                (-> columns-text
                    (str/replace #"\n" " ")
                    (str/split #",(?![^()]*\))")))]
    (when table-name
      {:table-name table-name
       :columns (->> lines
                     (keep #(parse-column-line % table-name))
                     vec)})))

(defn parse-schema
  "Parse schema.sql and return a map of table-name -> column-info."
  [schema-sql]
  (let [;; Split by CREATE TABLE statements
        statements (->> (str/split schema-sql #"(?=CREATE TABLE)")
                        (filter #(str/includes? % "CREATE TABLE")))]
    (into {}
          (keep (fn [stmt]
                  (when-let [{:keys [table-name columns]} (parse-table-statement stmt)]
                    [(keyword table-name) columns])))
          statements)))

(defn load-schema
  "Load and parse the schema from resources/schema.sql."
  []
  (parse-schema (slurp (io/resource "schema.sql"))))

;; ============================================================================
;; Column Coercion Configuration
;; ============================================================================

(defn- infer-coercion
  "Infer the coercion function for a column based on its type and metadata."
  [{:keys [column-name column-type comment enum-values table-name]}]
  (let [;; Check if it's a uuid column (BLOB named 'id' or ends with '_id')
        uuid-column? (and (= column-type :blob)
                          (or (= column-name "id")
                              (str/ends-with? column-name "_id")
                              (str/ends-with? column-name "_key")))
        ;; Check if it's a timestamp column (INT ending in _at)
        timestamp-column? (and (= column-type :int)
                               (str/ends-with? column-name "_at"))
        ;; Check if it's a boolean column (INT that's not an enum or timestamp)
        bool-column? (and (= column-type :int)
                          (nil? enum-values)
                          (not (str/ends-with? column-name "_at"))  ;; Not a timestamp
                          ;; Known boolean columns
                          (#{"from_the_sample" "use_original_links" "paywalled"
                             "email_maybe_confirmation" "paused" "payment_failed"} column-name))
        ;; Check for special handling via comments
        nippy-column? (and (= column-type :blob)
                           (some? comment)
                           (or (str/includes? comment "[:set")
                               (str/includes? comment "[:vector")
                               (str/includes? comment "[:map")))
        ;; Check for time type (send_digest_at stores LocalTime as TEXT)
        local-time-column? (and (= column-type :text)
                                (= column-name "send_digest_at"))]
    (cond
      uuid-column? :uuid
      nippy-column? :nippy
      timestamp-column? :zdt
      bool-column? :bool
      local-time-column? :local-time
      enum-values [:enum column-name]
      :else nil)))

(defn- make-column-coercions
  "Create coercion configuration for all columns in a table."
  [columns]
  (into {}
        (keep (fn [{:keys [column-name] :as col}]
                (when-let [coercion (infer-coercion col)]
                  [column-name coercion])))
        columns))

;; ============================================================================
;; SQLite to Clojure Attribute Mapping
;; ============================================================================

(def ^:private special-column-mappings
  "Special cases where SQLite column names don't follow the standard conversion pattern.
   Maps [table column] -> clojure-attribute"
  {[:user "timezone"] :user/timezone*
   [:mv_sub "n_read"] :mv.sub/read
   [:sub "feed_id"] :sub.feed/feed
   [:sub "email_from"] :sub.email/from
   [:sub "email_unsubscribed_at"] :sub.email/unsubscribed-at
   [:item "feed_id"] :item.feed/feed
   [:item "feed_guid"] :item.feed/guid
   [:item "email_sub_id"] :item.email/sub
   [:item "email_raw_content_key"] :item.email/raw-content-key
   [:item "email_list_unsubscribe"] :item.email/list-unsubscribe
   [:item "email_list_unsubscribe_post"] :item.email/list-unsubscribe-post
   [:item "email_reply_to"] :item.email/reply-to
   [:item "email_maybe_confirmation"] :item.email/maybe-confirmation
   [:item "direct_candidate_status"] :item.direct/candidate-status
   [:digest_item "digest_id"] :digest-item/digest
   [:digest_item "item_id"] :digest-item/item
   [:digest_item "kind"] :digest-item/kind
   [:ad "approve_state"] :ad/approve-state
   [:ad "recent_cost"] :ad/recent-cost
   [:ad "payment_failed"] :ad/payment-failed
   [:ad "customer_id"] :ad/customer-id
   [:ad "session_id"] :ad/session-id
   [:ad "payment_method"] :ad/payment-method
   [:ad "card_details"] :ad/card-details
   [:ad "image_url"] :ad/image-url
   [:ad_click "user_id"] :ad.click/user
   [:ad_click "ad_id"] :ad.click/ad
   [:ad_click "created_at"] :ad.click/created-at
   [:ad_click "cost"] :ad.click/cost
   [:ad_click "source"] :ad.click/source
   [:ad_credit "ad_id"] :ad.credit/ad
   [:ad_credit "source"] :ad.credit/source
   [:ad_credit "amount"] :ad.credit/amount
   [:ad_credit "created_at"] :ad.credit/created-at
   [:ad_credit "charge_status"] :ad.credit/charge-status
   [:mv_sub "sub_id"] :mv.sub/sub
   [:mv_sub "affinity_low"] :mv.sub/affinity-low
   [:mv_sub "affinity_high"] :mv.sub/affinity-high
   [:mv_sub "last_published"] :mv.sub/last-published
   [:mv_sub "unread"] :mv.sub/unread
   [:mv_user "user_id"] :mv.user/user
   [:mv_user "current_item_id"] :mv.user/current-item
   [:deleted_user "email_username_hash"] :deleted-user/email-username-hash
   [:bulk_send "sent_at"] :bulk-send/sent-at
   [:bulk_send "payload_size"] :bulk-send/payload-size
   [:bulk_send "mailersend_id"] :bulk-send/mailersend-id
   [:bulk_send "digests"] :bulk-send/digests
   [:reclist "user_id"] :reclist/user
   [:reclist "created_at"] :reclist/created-at
   [:reclist "clicked"] :reclist/clicked
   [:skip "reclist_id"] :skip/reclist
   [:skip "item_id"] :skip/item
   [:user_item "user_id"] :user-item/user
   [:user_item "item_id"] :user-item/item
   [:user_item "viewed_at"] :user-item/viewed-at
   [:user_item "skipped_at"] :user-item/skipped-at
   [:user_item "bookmarked_at"] :user-item/bookmarked-at
   [:user_item "favorited_at"] :user-item/favorited-at
   [:user_item "disliked_at"] :user-item/disliked-at
   [:user_item "reported_at"] :user-item/reported-at
   [:user_item "report_reason"] :user-item/report-reason
   [:digest "user_id"] :digest/user
   [:digest "sent_at"] :digest/sent-at
   [:digest "subject_id"] :digest/subject
   [:digest "ad_id"] :digest/ad
   [:digest "bulk_send_id"] :digest/bulk-send})

(defn- sql-table->clj-namespace
  "Convert SQLite table name to Clojure namespace prefix."
  [table-name]
  (-> (name table-name)
      (str/replace "_" "-")))

(defn- sql-column->clj-attr
  "Convert SQLite column name to Clojure attribute keyword.
   Uses special mappings when available, otherwise follows standard naming convention."
  [table-name column-name]
  (let [table-kw (if (keyword? table-name) table-name (keyword table-name))
        col-str (if (keyword? column-name) (name column-name) column-name)]
    (or (get special-column-mappings [table-kw col-str])
        ;; Default conversion: table/column with underscores -> hyphens
        (if (= col-str "id")
          :xt/id
          (keyword (sql-table->clj-namespace table-kw)
                   (str/replace col-str "_" "-"))))))

;; ============================================================================
;; Reference (Foreign Key) Configuration  
;; ============================================================================

(def ^:private ref-columns
  "Set of attributes that are references to other entities.
   These should be wrapped as {:xt/id value} in resolver output."
  #{:sub/user :sub.feed/feed :item.feed/feed :item.email/sub
    :user-item/user :user-item/item
    :digest/user :digest/subject :digest/ad :digest/bulk-send
    :digest-item/digest :digest-item/item
    :reclist/user
    :skip/reclist :skip/item
    :ad/user
    :ad.click/user :ad.click/ad
    :ad.credit/ad
    :mv.sub/sub
    :mv.user/user :mv.user/current-item
    :redirect/item})

(defn- ref?
  "Check if an attribute is a reference to another entity."
  [attr]
  (contains? ref-columns attr))

;; ============================================================================
;; Enum Definitions by Column
;; ============================================================================

(def ^:private column-enums
  "Map column names to their enum mappings (db-value -> clojure-value)."
  {"plan" {0 :quarter, 1 :annual}
   "moderation" {0 :approved, 1 :blocked}
   "approve_state" {0 :pending, 1 :approved, 2 :rejected}
   "source" {0 :web, 1 :email}  ; Used in ad_click and ad_credit
   "charge_status" {0 :pending, 1 :confirmed, 2 :failed}
   "kind" {0 :icymi, 1 :discover}
   "record_type" {0 :feed, 1 :email, 2 :direct}
   "direct_candidate_status" {0 :ingest-failed, 1 :blocked, 2 :approved}})

;; ad_credit uses a different source enum than ad_click
(def ^:private ad-credit-source-enum
  {0 :charge, 1 :manual})

;; ============================================================================
;; Schema Metadata Cache
;; ============================================================================

(def ^:private schema-cache
  "Cached schema metadata. Loaded lazily."
  (delay (load-schema)))

(defn get-schema
  "Get the parsed schema (cached)."
  []
  @schema-cache)

;; ============================================================================
;; Row Coercion
;; ============================================================================

(defn- get-coercion-fn
  "Get the coercion function for a column."
  [coercion-type column-name table-name]
  (case coercion-type
    :uuid bytes->uuid
    :zdt epoch-ms->zdt
    :bool int->bool
    :nippy thaw-blob
    :local-time str->local-time
    ;; Handle enum with special case for ad_credit source
    (if (and (vector? coercion-type)
             (= :enum (first coercion-type)))
      (let [enum-map (if (and (= table-name :ad_credit)
                              (= column-name "source"))
                       ad-credit-source-enum
                       (get column-enums column-name))]
        (make-enum-reader enum-map))
      identity)))

(defn- coerce-row
  "Coerce a single database row to Clojure values.
   Takes the table name, column coercions map, and the raw row."
  [table-name column-coercions row]
  (reduce-kv
   (fn [result col-kw value]
     (let [col-str (name col-kw)
           attr (sql-column->clj-attr table-name col-str)
           coercion-type (get column-coercions col-str)
           coerce-fn (when coercion-type
                       (get-coercion-fn coercion-type col-str table-name))
           coerced-value (if coerce-fn
                           (coerce-fn value)
                           value)]
       (assoc result attr coerced-value)))
   {}
   row))

;; ============================================================================
;; Query Execution
;; ============================================================================

(defn- execute-query
  "Execute a query and coerce results."
  [conn table-name column-coercions sql-map]
  (let [[sql-str & params] (sql/format sql-map)
        ;; Use as-unqualified-maps to preserve original column names
        raw-results (jdbc/execute! conn (into [sql-str] params)
                                   {:builder-fn rs/as-unqualified-maps})]
    (mapv #(coerce-row table-name column-coercions %) raw-results)))

;; ============================================================================
;; SQLite Pathom Resolvers
;; ============================================================================

(defn- table-columns->output
  "Convert table columns to Pathom resolver output format."
  [table-name columns]
  (vec (for [{:keys [column-name]} columns
             :let [attr (sql-column->clj-attr table-name column-name)]
             :when (not= attr :xt/id)]
         (if (ref? attr)
           {attr [:xt/id]}
           attr))))

(defn- joinify
  "Wrap reference values as {:xt/id value}."
  [[k v]]
  (if (ref? k)
    [k (when v {:xt/id v})]
    [k v]))

(defn- joinify-map
  "Apply joinify to all entries in a map."
  [m]
  (into {} (map joinify) m))

(defn- expects
  "Get the expected outputs from Pathom environment."
  [env]
  (-> env
      ::pcp/node
      ::pcp/expects
      keys
      vec))

(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables.
   
   Unlike xtdb2-resolvers which tries to select only needed columns,
   this always does SELECT * since SQLite is row-oriented and selecting
   all columns is efficient.
   
   Each resolver:
   - Takes {:xt/id uuid} as input
   - Returns all columns for that table
   - Coerces SQLite values back to Clojure types
   - Wraps reference columns as {:xt/id value}"
  ([]
   (sqlite-resolvers (get-schema)))
  ([schema]
   (for [[table-kw columns] schema
         :let [table-name (name table-kw)
               column-coercions (make-column-coercions columns)
               output (table-columns->output table-kw columns)
               op-name (symbol "com.yakread.lib.sqlite"
                               (str table-name "-sqlite-resolver"))]]
     (pco/resolver op-name
                   {::pco/input [:xt/id]
                    ::pco/output output
                    ::pco/batch? true}
                   (fn [{:keys [biff/db] :as env} inputs]
                     (let [ids (mapv :xt/id inputs)
                           id-bytes (mapv uuid->bytes ids)
                           sql-map {:select :*
                                    :from (keyword table-name)
                                    :where [:in :id id-bytes]}
                           results (execute-query db (keyword table-name) column-coercions sql-map)
                           id->result (into {} (map (juxt :xt/id identity)) results)]
                       (mapv (fn [{:keys [xt/id]}]
                               (-> (get id->result id {})
                                   lib.core/some-vals
                                   joinify-map
                                   (assoc :xt/id id)))
                             inputs)))))))

;; ============================================================================
;; Utility Functions for Writing
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

(defn coerce-value-for-write
  "Coerce a Clojure value for SQLite storage based on its type."
  [v]
  (cond
    (uuid? v) (uuid->bytes v)
    (instance? Instant v) (instant->epoch-ms v)
    (instance? ZonedDateTime v) (instant->epoch-ms v)
    (boolean? v) (bool->int v)
    (set? v) (nippy/freeze v)
    (map? v) (nippy/freeze v)
    (vector? v) (nippy/freeze v)
    :else v))
