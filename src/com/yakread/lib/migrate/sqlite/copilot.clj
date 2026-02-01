(ns com.yakread.lib.migrate.sqlite.copilot
  "Import XTDB v1 data into SQLite.

   This namespace provides functions to read serialized XTDB v1 documents
   (typically stored as nippy files) and insert them into SQLite.

   Handles:
   - Put operations (insert/update)
   - Delete operations
   - Component entity tracking (digest-items, skips) with proper deletion
   - Valid time filtering (ignores operations with valid time ranges that don't overlap now)

   For testing purposes, plain EDN files can be used instead of nippy."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [taoensso.nippy :as nippy]
   [time-literals.read-write :as time-literals])
  (:import
   [java.nio ByteBuffer]
   [java.time Instant ZonedDateTime]
   [java.util UUID]))

;; ============================================================================
;; Utility functions for type coercion
;; ============================================================================

(defn uuid->bytes
  "Convert a UUID to a 16-byte array for SQLite BLOB storage."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn bytes->uuid
  "Convert a 16-byte array back to a UUID."
  [^bytes byte-array]
  (let [bb (ByteBuffer/wrap byte-array)]
    (UUID. (.getLong bb) (.getLong bb))))

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
;; Schema mapping from XTDB attributes to SQLite columns
;; ============================================================================

(def table-required-attrs
  "Map of required attribute -> SQLite table name.
   These attributes identify which table a document belongs to."
  {:user/email                       :user
   :sub/user                         :sub
   :item/ingested-at                 :item
   :feed/url                         :feed
   :user-item/user                   :user-item
   :digest/user                      :digest
   :bulk-send/sent-at                :bulk-send
   :reclist/user                     :reclist
   :ad/user                          :ad
   :ad.click/user                    :ad-click
   :ad.credit/ad                     :ad-credit
   :mv.sub/sub                       :mv-sub
   :mv.user/user                     :mv-user
   :deleted-user/email-username-hash :deleted-user})

;; Enum mappings from Clojure keywords to SQLite integers
(def plan-enum {:quarter 0 :annual 1})
(def moderation-enum {:approved 0 :blocked 1})
(def approve-state-enum {:pending 0 :approved 1 :rejected 2})
(def ad-source-enum {:web 0 :email 1})
(def ad-credit-source-enum {:charge 0 :manual 1})
(def charge-status-enum {:pending 0 :confirmed 1 :failed 2})
(def digest-item-kind-enum {:icymi 0 :discover 1})
(def candidate-status-enum {:ingest-failed 0 :blocked 1 :approved 2})

;; Sub record type enum - derived from which attributes are present
(def sub-record-type-enum {:feed 0 :email 1})

;; Item record type enum - derived from which attributes are present
(def item-record-type-enum {:feed 0 :email 1 :direct 2})

;; ============================================================================
;; Document conversion functions
;; ============================================================================

(defn determine-sub-record-type
  "Determine the sub record type from the document attributes."
  [doc]
  (cond
    (contains? doc :sub.feed/feed) :feed
    (contains? doc :sub.email/from) :email
    :else (throw (ex-info "Unknown sub type" {:doc doc}))))

(defn determine-item-record-type
  "Determine the item record type from the document attributes."
  [doc]
  (cond
    (contains? doc :item.feed/feed) :feed
    (contains? doc :item.email/sub) :email
    (= :item/direct (:item/doc-type doc)) :direct
    :else (throw (ex-info "Unknown item type" {:doc doc}))))

(defn coerce-value
  "Coerce a value for SQLite storage based on its type."
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

(defmulti convert-doc
  "Convert an XTDB v1 document to a SQLite row map.
   Returns {:table <keyword>, :row {<column> <value>}} or nil if doc should be skipped."
  (fn [doc]
    (some table-required-attrs (keys doc))))

(defmethod convert-doc :default [_doc]
  ;; Unknown document type, skip
  nil)

(defmethod convert-doc :user [doc]
  {:table :user
   :row {:id (coerce-value (:xt/id doc))
         :email (:user/email doc)
         :roles (when-some [v (:user/roles doc)] (nippy/freeze v))
         :joined_at (some-> (:user/joined-at doc) instant->epoch-ms)
         :digest_days (when-some [v (:user/digest-days doc)] (nippy/freeze v))
         :send_digest_at (some-> (:user/send-digest-at doc) str)
         :timezone (or (:user/timezone* doc)
                       (some-> (:user/timezone doc) str))
         :digest_last_sent (some-> (:user/digest-last-sent doc) instant->epoch-ms)
         :from_the_sample (some-> (:user/from-the-sample doc) bool->int)
         :use_original_links (some-> (:user/use-original-links doc) bool->int)
         :suppressed_at (some-> (:user/suppressed-at doc) instant->epoch-ms)
         :email_username (:user/email-username doc)
         :customer_id (:user/customer-id doc)
         :plan (some-> (:user/plan doc) plan-enum)
         :cancel_at (some-> (:user/cancel-at doc) instant->epoch-ms)}})

(defmethod convert-doc :feed [doc]
  {:table :feed
   :row {:id (coerce-value (:xt/id doc))
         :url (:feed/url doc)
         :synced_at (some-> (:feed/synced-at doc) instant->epoch-ms)
         :title (:feed/title doc)
         :description (:feed/description doc)
         :image_url (:feed/image-url doc)
         :etag (:feed/etag doc)
         :last_modified (:feed/last-modified doc)
         :failed_syncs (:feed/failed-syncs doc)
         :moderation (some-> (:feed/moderation doc) moderation-enum)}})

(defmethod convert-doc :sub [doc]
  (let [record-type (determine-sub-record-type doc)]
    {:table :sub
     :row {:id (coerce-value (:xt/id doc))
           :user_id (coerce-value (:sub/user doc))
           :created_at (instant->epoch-ms (:sub/created-at doc))
           :pinned_at (some-> (:sub/pinned-at doc) instant->epoch-ms)
           :record_type (sub-record-type-enum record-type)
           :feed_id (some-> (:sub.feed/feed doc) coerce-value)
           :email_from (:sub.email/from doc)
           :email_unsubscribed_at (some-> (:sub.email/unsubscribed-at doc) instant->epoch-ms)}}))

(defmethod convert-doc :item [doc]
  (let [record-type (determine-item-record-type doc)]
    {:table :item
     :row {:id (coerce-value (:xt/id doc))
           :ingested_at (instant->epoch-ms (:item/ingested-at doc))
           :title (:item/title doc)
           :url (:item/url doc)
           :redirect_urls (when-some [v (:item/redirect-urls doc)] (nippy/freeze v))
           :content (:item/content doc)
           :content_key (some-> (:item/content-key doc) coerce-value)
           :published_at (some-> (:item/published-at doc) instant->epoch-ms)
           :excerpt (:item/excerpt doc)
           :author_name (:item/author-name doc)
           :author_url (:item/author-url doc)
           :feed_url (:item/feed-url doc)
           :lang (:item/lang doc)
           :site_name (:item/site-name doc)
           :byline (:item/byline doc)
           :length (:item/length doc)
           :image_url (:item/image-url doc)
           :paywalled (some-> (:item/paywalled doc) bool->int)
           :record_type (item-record-type-enum record-type)
           :feed_id (some-> (:item.feed/feed doc) coerce-value)
           :feed_guid (:item.feed/guid doc)
           :email_sub_id (some-> (:item.email/sub doc) coerce-value)
           :email_raw_content_key (some-> (:item.email/raw-content-key doc) coerce-value)
           :email_list_unsubscribe (:item.email/list-unsubscribe doc)
           :email_list_unsubscribe_post (:item.email/list-unsubscribe-post doc)
           :email_reply_to (:item.email/reply-to doc)
           :email_maybe_confirmation (some-> (:item.email/maybe-confirmation doc) bool->int)
           :direct_candidate_status (some-> (:item.direct/candidate-status doc) candidate-status-enum)}}))

(defmethod convert-doc :user-item [doc]
  {:table :user_item
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (:user-item/user doc))
         :item_id (coerce-value (:user-item/item doc))
         :viewed_at (some-> (:user-item/viewed-at doc) instant->epoch-ms)
         :skipped_at (some-> (:user-item/skipped-at doc) instant->epoch-ms)
         :bookmarked_at (some-> (:user-item/bookmarked-at doc) instant->epoch-ms)
         :favorited_at (some-> (:user-item/favorited-at doc) instant->epoch-ms)
         :disliked_at (some-> (:user-item/disliked-at doc) instant->epoch-ms)
         :reported_at (some-> (:user-item/reported-at doc) instant->epoch-ms)
         :report_reason (:user-item/report-reason doc)}})

(defmethod convert-doc :digest [doc]
  {:table :digest
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (:digest/user doc))
         :sent_at (instant->epoch-ms (:digest/sent-at doc))
         :subject_id (some-> (:digest/subject doc) coerce-value)
         :ad_id (some-> (:digest/ad doc) coerce-value)
         :bulk_send_id (some-> (:digest/bulk-send doc) coerce-value)}})

(defmethod convert-doc :bulk-send [doc]
  {:table :bulk_send
   :row {:id (coerce-value (:xt/id doc))
         :sent_at (instant->epoch-ms (:bulk-send/sent-at doc))
         :payload_size (:bulk-send/payload-size doc)
         :mailersend_id (:bulk-send/mailersend-id doc)
         :digests (nippy/freeze (:bulk-send/digests doc))}})

(defmethod convert-doc :reclist [doc]
  {:table :reclist
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (or (:reclist/user doc)
                                     (:skip/user doc))) ; old schema
         :created_at (instant->epoch-ms (or (:reclist/created-at doc)
                                             (:skip/timeline-created-at doc))) ; old schema
         :clicked (nippy/freeze (or (:reclist/clicked doc)
                                     (:skip/clicked doc) ; old schema
                                     #{}))}})

(defmethod convert-doc :ad [doc]
  {:table :ad
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (:ad/user doc))
         :approve_state (approve-state-enum (:ad/approve-state doc))
         :updated_at (instant->epoch-ms (:ad/updated-at doc))
         :balance (:ad/balance doc)
         :recent_cost (:ad/recent-cost doc)
         :bid (:ad/bid doc)
         :budget (:ad/budget doc)
         :url (:ad/url doc)
         :title (:ad/title doc)
         :description (:ad/description doc)
         :image_url (:ad/image-url doc)
         :paused (some-> (:ad/paused doc) bool->int)
         :payment_failed (some-> (:ad/payment-failed doc) bool->int)
         :customer_id (:ad/customer-id doc)
         :session_id (:ad/session-id doc)
         :payment_method (:ad/payment-method doc)
         :card_details (when-some [v (:ad/card-details doc)]
                         (nippy/freeze (set/rename-keys v {:exp_year :exp-year
                                                           :exp_month :exp-month})))}})

(defmethod convert-doc :ad-click [doc]
  {:table :ad_click
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (:ad.click/user doc))
         :ad_id (coerce-value (:ad.click/ad doc))
         :created_at (instant->epoch-ms (:ad.click/created-at doc))
         :cost (:ad.click/cost doc)
         :source (ad-source-enum (:ad.click/source doc))}})

(defmethod convert-doc :ad-credit [doc]
  {:table :ad_credit
   :row {:id (coerce-value (:xt/id doc))
         :ad_id (coerce-value (:ad.credit/ad doc))
         :source (ad-credit-source-enum (:ad.credit/source doc))
         :amount (:ad.credit/amount doc)
         :created_at (instant->epoch-ms (:ad.credit/created-at doc))
         :charge_status (some-> (:ad.credit/charge-status doc) charge-status-enum)}})

(defmethod convert-doc :mv-sub [doc]
  {:table :mv_sub
   :row {:id (coerce-value (:xt/id doc))
         :sub_id (coerce-value (:mv.sub/sub doc))
         :affinity_low (:mv.sub/affinity-low doc)
         :affinity_high (:mv.sub/affinity-high doc)
         :last_published (some-> (:mv.sub/last-published doc) instant->epoch-ms)
         :unread (:mv.sub/unread doc)
         :n_read (:mv.sub/read doc)}})

(defmethod convert-doc :mv-user [doc]
  {:table :mv_user
   :row {:id (coerce-value (:xt/id doc))
         :user_id (coerce-value (:mv.user/user doc))
         :current_item_id (some-> (:mv.user/current-item doc) coerce-value)}})

(defmethod convert-doc :deleted-user [doc]
  {:table :deleted_user
   :row {:id (coerce-value (:xt/id doc))
         :email_username_hash (:deleted-user/email-username-hash doc)}})

;; ============================================================================
;; Digest item handling - these are split out from digest docs in old schema
;; ============================================================================

(defn name-uuid
  "Generate a deterministic UUID from strings."
  [& strs]
  (java.util.UUID/nameUUIDFromBytes (.getBytes (apply str strs))))

(defn digest-item-id
  "Generate a deterministic ID for a digest item."
  [digest-id item-id kind]
  (name-uuid digest-id item-id kind))

(defn skip-item-id
  "Generate a deterministic ID for a skip item."
  [reclist-id item-id]
  (name-uuid reclist-id item-id))

(defn extract-digest-item-ids
  "Extract the set of digest-item IDs from a digest document."
  [doc]
  (let [digest-id (:xt/id doc)]
    (into #{}
          (concat
           (for [item-id (:digest/icymi doc)]
             (digest-item-id digest-id item-id :icymi))
           (for [item-id (:digest/discover doc)]
             (digest-item-id digest-id item-id :discover))))))

(defn extract-skip-item-ids
  "Extract the set of skip IDs from a reclist document."
  [doc]
  (let [reclist-id (:xt/id doc)]
    (into #{}
          (for [item-id (:skip/items doc)]
            (skip-item-id reclist-id item-id)))))

(defn extract-digest-items
  "Extract digest items from a digest document that uses the old schema
   with :digest/icymi and :digest/discover vectors."
  [doc]
  (let [digest-id (:xt/id doc)]
    (concat
     (for [item-id (:digest/icymi doc)]
       {:table :digest_item
        :row {:id (uuid->bytes (digest-item-id digest-id item-id :icymi))
              :digest_id (uuid->bytes digest-id)
              :item_id (uuid->bytes item-id)
              :kind (digest-item-kind-enum :icymi)}})
     (for [item-id (:digest/discover doc)]
       {:table :digest_item
        :row {:id (uuid->bytes (digest-item-id digest-id item-id :discover))
              :digest_id (uuid->bytes digest-id)
              :item_id (uuid->bytes item-id)
              :kind (digest-item-kind-enum :discover)}}))))

;; ============================================================================
;; Skip handling - old schema stored skips inside reclist doc
;; ============================================================================

(defn extract-skip-items
  "Extract skip items from a reclist document that uses the old schema
   with :skip/items vector."
  [doc]
  (let [reclist-id (:xt/id doc)]
    (for [item-id (:skip/items doc)]
      {:table :skip
       :row {:id (uuid->bytes (skip-item-id reclist-id item-id))
             :reclist_id (uuid->bytes reclist-id)
             :item_id (uuid->bytes item-id)}})))

;; ============================================================================
;; Transaction normalization and valid time handling
;; ============================================================================

(defn valid-now?
  "Check if an operation's valid time range overlaps with the current time.
   If valid-from and valid-to are both nil, the operation is valid from now until forever.
   If only valid-from is set, it's valid from that time until forever.
   If valid-to is set, the operation has an end time.

   For SQLite (non-temporal), we only care about operations that are valid NOW.
   This means: valid-from <= now AND (valid-to is nil OR valid-to > now)"
  [valid-from valid-to now]
  (let [valid-from-ok (or (nil? valid-from)
                          (<= (instant->epoch-ms valid-from) (instant->epoch-ms now)))
        valid-to-ok (or (nil? valid-to)
                        (> (instant->epoch-ms valid-to) (instant->epoch-ms now)))]
    (and valid-from-ok valid-to-ok)))

(defn valid-operation?
  "Check if a transaction operation should be processed.
   Returns true for:
   - Delete operations with a non-nil ID
   - Put operations that are not evicted and not transaction functions"
  [op doc-or-id]
  (or (and (= op :xtdb.api/delete)
           (some? doc-or-id))
      (and (= op :xtdb.api/put)
           (not (:xtdb.api/evicted? doc-or-id))
           (not (:xt/fn doc-or-id)))))

(defn normalize-tx
  "Normalize a transaction into a sequence of operations.
   Each operation is a map with :op, :tx-time, :valid-from, :valid-to, :doc/:id.

   Filters out:
   - Operations with :xtdb.api/evicted? flag
   - Operations that are transaction functions (:xt/fn)
   - Operations with valid time ranges that don't overlap with current time"
  [{:xtdb.api/keys [tx-ops tx-time]} now]
  (for [tx-op tx-ops
        :let [[op & args] tx-op
              [doc-or-id valid-from valid-to] args]
        :when (and (valid-operation? op doc-or-id)
                   (valid-now? valid-from valid-to now))]
    {:op op
     :tx-time tx-time
     :valid-from valid-from
     :valid-to valid-to
     :doc (when (= op :xtdb.api/put) doc-or-id)
     :id (case op
           :xtdb.api/put (:xt/id doc-or-id)
           :xtdb.api/delete doc-or-id
           nil)}))

(defn read-edn-txes
  "Read XTDB v1 transactions from an EDN file.
   Returns the raw transaction data (a vector of transactions)."
  [file]
  (edn/read-string {:readers (merge time-literals/tags
                                    {'inst #(Instant/parse %)})}
                   (slurp file)))

(defn read-nippy-file
  "Read XTDB v1 transactions from a nippy file.
   Returns the raw transaction data (a vector of transactions)."
  [file]
  (nippy/thaw-from-file file))

;; ============================================================================
;; State management for component entities (pure functions)
;; ============================================================================

(def empty-state
  "Initial state for tracking documents and their components during import.
   Contains:
   - :component-id->table - Map from component entity ID to its table
   - :doc-id->component-ids - Map from parent document ID to set of component entity IDs
   - :doc-id->table - Map from document ID to its table"
  {:component-id->table {}
   :doc-id->component-ids {}
   :doc-id->table {}})

(defn get-component-ids
  "Get the component IDs for a document from a doc (digest-items, skips)."
  [doc table]
  (case table
    :digest (extract-digest-item-ids doc)
    :reclist (extract-skip-item-ids doc)
    #{}))

(defn get-component-table
  "Get the table name for component entities of a given parent table."
  [parent-table]
  (case parent-table
    :digest :digest_item
    :reclist :skip
    nil))

;; ============================================================================
;; SQLite transaction representation (pure data)
;; ============================================================================

(defn make-upsert-tx
  "Create an upsert transaction as pure data."
  [table row]
  {:type :upsert
   :table table
   :row (into {} (filter (comp some? val)) row)})

(defn make-delete-tx
  "Create a delete transaction as pure data."
  [table id-bytes]
  {:type :delete
   :table table
   :id id-bytes})

;; ============================================================================
;; Pure operation handlers (return transactions and state updates)
;; ============================================================================

(defn handle-put-op
  "Handle a put operation - returns SQLite transactions and new state.
   Pure function that doesn't perform any side effects.

   Returns {:txs [...], :state {...}, :xtdb1-op {...}} or nil if doc is unknown."
  [state {:keys [doc id] :as xtdb1-op}]
  (when-some [{:keys [table] :as converted} (convert-doc doc)]
    (let [component-table (get-component-table table)
          old-component-ids (get-in state [:doc-id->component-ids id] #{})
          new-component-ids (get-component-ids doc table)
          deleted-ids (set/difference old-component-ids new-component-ids)

          ;; Main document upsert
          main-tx (make-upsert-tx table (:row converted))

          ;; Component entity upserts
          component-txs (when component-table
                          (let [component-rows (case table
                                                 :digest (extract-digest-items doc)
                                                 :reclist (extract-skip-items doc)
                                                 [])]
                            (mapv #(make-upsert-tx component-table (:row %)) component-rows)))

          ;; Component entity deletes (for removed items)
          delete-txs (when component-table
                       (mapv #(make-delete-tx component-table (uuid->bytes %)) deleted-ids))

          ;; Update state
          new-state (cond-> state
                      true
                      (assoc-in [:doc-id->table id] table)

                      component-table
                      (->
                       ;; Remove deleted component IDs from tracking
                       (update :component-id->table #(apply dissoc % deleted-ids))
                       ;; Add new component IDs to tracking
                       (update :component-id->table into (for [cid new-component-ids]
                                                           [cid component-table]))
                       ;; Update doc-id->component-ids
                       (assoc-in [:doc-id->component-ids id] new-component-ids)))]
      {:txs (vec (concat [main-tx] component-txs delete-txs))
       :state new-state
       :xtdb1-op xtdb1-op})))

(defn handle-delete-op
  "Handle a delete operation - returns SQLite transactions and new state.
   Pure function that doesn't perform any side effects.

   Returns {:txs [...], :state {...}, :xtdb1-op {...}} or nil if doc is unknown."
  [state {:keys [id] :as xtdb1-op}]
  (when-some [table (get-in state [:doc-id->table id])]
    (let [component-ids (get-in state [:doc-id->component-ids id] #{})

          ;; Main document delete
          main-tx (make-delete-tx table (uuid->bytes id))

          ;; Component entity deletes
          component-txs (vec (for [cid component-ids
                                   :let [ctable (get-in state [:component-id->table cid])]
                                   :when ctable]
                               (make-delete-tx ctable (uuid->bytes cid))))

          ;; Update state
          new-state (-> state
                        (update :doc-id->table dissoc id)
                        (update :doc-id->component-ids dissoc id)
                        (update :component-id->table #(apply dissoc % component-ids)))]
      {:txs (into [main-tx] component-txs)
       :state new-state
       :xtdb1-op xtdb1-op})))

(defn process-op
  "Process a single normalized operation.
   Pure function that returns {:txs [...], :state {...}, :xtdb1-op {...}} or nil."
  [state op]
  (case (:op op)
    :xtdb.api/put (handle-put-op state op)
    :xtdb.api/delete (handle-delete-op state op)
    nil))

(defn process-ops
  "Process a sequence of operations, threading state through.
   Pure function that returns a sequence of {:txs [...], :state {...}, :xtdb1-op {...}}.
   Each result includes the state after that operation."
  [initial-state ops]
  (loop [state initial-state
         remaining ops
         results []]
    (if (empty? remaining)
      results
      (let [op (first remaining)
            result (process-op state op)]
        (if result
          (recur (:state result)
                 (rest remaining)
                 (conj results result))
          (recur state
                 (rest remaining)
                 results))))))

;; ============================================================================
;; SQLite execution (side effects)
;; ============================================================================

(defn execute-tx!
  "Execute a single SQLite transaction."
  [conn {:keys [type table row id]}]
  (case type
    :upsert
    (let [sql-map {:insert-into table
                   :values [row]
                   :on-conflict {:do-update-set (keys row)}}
          [sql-str & params] (sql/format sql-map)]
      (jdbc/execute! conn (into [sql-str] params)))

    :delete
    (let [sql-map {:delete-from table
                   :where [:= :id id]}
          [sql-str & params] (sql/format sql-map)]
      (jdbc/execute! conn (into [sql-str] params)))))

(defn execute-txs!
  "Execute a sequence of SQLite transactions."
  [conn txs]
  (doseq [tx txs]
    (execute-tx! conn tx)))

;; ============================================================================
;; Main import functions
;; ============================================================================

(defn tx-files
  "Get sorted list of transaction files from a directory."
  [dir]
  (->> (io/file dir)
       file-seq
       (filter #(.isFile %))
       (sort-by #(parse-long (.getName %)))))

(defn compute-txs-for-file
  "Compute all SQLite transactions for a single file.
   Pure function that returns {:txs [...], :state <new-state>, :tx-results [...]}.

   :tx-results contains the individual results with :txs, :state, and :xtdb1-op for each operation."
  [initial-state txes now]
  (let [ops (vec (mapcat #(normalize-tx % now) txes))
        results (process-ops initial-state ops)
        all-txs (vec (mapcat :txs results))
        final-state (or (:state (last results)) initial-state)]
    {:txs all-txs
     :state final-state
     :tx-results results}))

(defn import-from-edn!
  "Import data from an EDN file into SQLite.

   The file should contain a vector of XTDB v1 transactions in the same
   format as the nippy files in storage/migrate-export/. Each transaction
   has :xtdb.api/tx-ops and :xtdb.api/tx-time keys.

   Handles:
   - Put operations (insert/update)
   - Delete operations
   - Component entity tracking (digest-items, skips)
   - Valid time filtering

   Options:
   - :conn - SQLite connection
   - :edn-file - Path to EDN file containing XTDB v1 transactions
   - :now - (optional) Reference time for valid time filtering, defaults to current time"
  [{:keys [conn edn-file now]}]
  (let [now (or now (Instant/now))
        txes (read-edn-txes edn-file)
        {:keys [txs]} (compute-txs-for-file empty-state txes now)]
    (log/info "Importing from" edn-file)
    (jdbc/with-transaction [tx conn]
      (execute-txs! tx txs))
    (log/info "Processed" (count txs) "transactions from" edn-file)
    {:processed (count txs)}))

(defn import-from-nippy-files!
  "Import data from multiple nippy files (XTDB v1 transaction exports).

   This is the only function that performs side effects (file I/O, database writes).
   All helper functions it calls are pure.

   This expects the files to be in the format written by
   com.biffweb.migrate.xtdb1/export! - each file contains a vector
   of XTDB v1 transactions.

   Handles:
   - Put operations (insert/update)
   - Delete operations
   - Component entity tracking (digest-items, skips)
   - Valid time filtering

   Options:
   - :conn - SQLite connection
   - :dir - Directory containing nippy files
   - :now - (optional) Reference time for valid time filtering, defaults to current time"
  [{:keys [conn dir now]}]
  (let [now (or now (Instant/now))
        files (tx-files dir)]
    (log/info "Found" (count files) "nippy files in" dir)
    (loop [remaining-files files
           state empty-state]
      (if (empty? remaining-files)
        :done
        (let [f (first remaining-files)
              file-index (parse-long (.getName f))
              _ (log/info "Processing file" file-index)
              txes (read-nippy-file f)  ; Side effect: file I/O
              {:keys [txs state]} (compute-txs-for-file state txes now)]
          ;; Side effect: database write
          (jdbc/with-transaction [tx conn]
            (execute-txs! tx txs))
          (recur (rest remaining-files) state))))))

;; ============================================================================
;; Pure functions for testing
;; ============================================================================

(defn docs->rows
  "Convert a sequence of XTDB v1 documents to SQLite rows.
   Returns a sequence of {:table <keyword> :row <map>}.
   Note: This is a simplified function for testing that doesn't handle deletes."
  [docs]
  (mapcat (fn [doc]
            (when-some [{:keys [table] :as converted} (convert-doc doc)]
              (concat
               [converted]
               ;; Handle old schema embedded items
               (when (= table :digest)
                 (extract-digest-items doc))
               (when (= table :reclist)
                 (extract-skip-items doc)))))
          docs))

(defn convert-docs
  "Pure function to convert a sequence of XTDB v1 documents to SQLite rows.
   Used for testing - does not perform any I/O.
   Note: This is a simplified function that doesn't handle deletes."
  [docs]
  (docs->rows docs))

(defn extract-docs-from-txes
  "Extract documents from XTDB v1 transactions (put operations only).
   Used for testing - doesn't handle deletes or valid time filtering."
  [txes]
  (for [tx txes
        tx-op (:xtdb.api/tx-ops tx)
        :when (= :xtdb.api/put (first tx-op))]
    (second tx-op)))

;; ============================================================================
;; Dry run function
;; ============================================================================

(defn group-txs-by-table
  "Group transactions by their table.
   Returns a map of table -> [txs...]."
  [txs]
  (group-by :table txs))

(defn sample-n
  "Take up to n random items from a collection.
   Returns a vector of the sampled items."
  [n coll]
  (let [items (vec coll)
        cnt (count items)]
    (if (<= cnt n)
      items
      (vec (take n (shuffle items))))))

(defn tx-result->sample-data
  "Convert a tx-result to a map suitable for dry-run output.
   Returns {:xtdb1-op {...}, :sqlite-txs [...]}."
  [{:keys [txs xtdb1-op]}]
  {:xtdb1-op xtdb1-op
   :sqlite-txs txs})

(defn dry-run
  "Process a single random nippy file and return sample SQLite transactions.

   This function:
   1. Picks a single nippy file at random from the directory
   2. Computes all the SQLite transactions resulting from that file (as pure data)
   3. Returns 3 random SQLite transactions for each table, along with their
      corresponding XTDB v1 operations

   This is useful for debugging and verifying the migration logic without
   actually writing to the database.

   Options:
   - :dir - Directory containing nippy files
   - :now - (optional) Reference time for valid time filtering, defaults to current time
   - :samples-per-table - (optional) Number of samples to return per table, defaults to 3

   Returns a map with:
   - :file - The name of the file that was processed
   - :total-txs - Total number of SQLite transactions computed
   - :tables - Map of table -> {:sample-count N, :total-count M, :samples [...]}
               where each sample is {:xtdb1-op {...}, :sqlite-txs [...]}"
  [{:keys [dir now samples-per-table]}]
  (let [now (or now (Instant/now))
        samples-per-table (or samples-per-table 3)
        files (tx-files dir)]
    (when (empty? files)
      (throw (ex-info "No nippy files found in directory" {:dir dir})))

    (let [;; Pick a random file
          file (rand-nth files)
          file-name (.getName file)

          ;; Read and process the file (pure operations)
          txes (read-nippy-file file)
          {:keys [tx-results]} (compute-txs-for-file empty-state txes now)

          ;; Group results by the tables they affect
          ;; Each tx-result has :txs (list of sqlite txs) and :xtdb1-op
          ;; We want to group by which tables each result touches
          results-by-table (reduce (fn [acc {:keys [txs] :as result}]
                                     (let [tables (distinct (map :table txs))]
                                       (reduce (fn [a t]
                                                 (update a t (fnil conj []) result))
                                               acc
                                               tables)))
                                   {}
                                   tx-results)

          ;; Sample from each table
          table-samples (into {}
                              (for [[table results] results-by-table]
                                (let [sampled (sample-n samples-per-table results)]
                                  [table {:sample-count (count sampled)
                                          :total-count (count results)
                                          :samples (mapv tx-result->sample-data sampled)}])))]
      {:file file-name
       :total-txs (count (mapcat :txs tx-results))
       :tables table-samples})))
