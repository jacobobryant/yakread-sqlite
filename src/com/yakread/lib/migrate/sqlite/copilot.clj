(ns com.yakread.lib.migrate.sqlite.copilot
  "Import XTDB v1 data into SQLite.

   This namespace provides functions to read serialized XTDB v1 documents
   (typically stored as nippy files) and insert them into SQLite.

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

(defn extract-digest-items
  "Extract digest items from a digest document that uses the old schema
   with :digest/icymi and :digest/discover vectors."
  [doc]
  (let [digest-id (:xt/id doc)]
    (concat
     (for [item-id (:digest/icymi doc)]
       {:table :digest_item
        :row {:id (uuid->bytes (java.util.UUID/nameUUIDFromBytes
                                (.getBytes (str digest-id item-id :icymi))))
              :digest_id (uuid->bytes digest-id)
              :item_id (uuid->bytes item-id)
              :kind (digest-item-kind-enum :icymi)}})
     (for [item-id (:digest/discover doc)]
       {:table :digest_item
        :row {:id (uuid->bytes (java.util.UUID/nameUUIDFromBytes
                                (.getBytes (str digest-id item-id :discover))))
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
       :row {:id (uuid->bytes (java.util.UUID/nameUUIDFromBytes
                               (.getBytes (str reclist-id item-id))))
             :reclist_id (uuid->bytes reclist-id)
             :item_id (uuid->bytes item-id)}})))

;; ============================================================================
;; Reading data
;; ============================================================================

(defn extract-docs-from-txes
  "Extract documents from XTDB v1 transactions.
   Each transaction is a map with :xtdb.api/tx-ops containing vectors like
   [:xtdb.api/put <doc>] or [:xtdb.api/delete <doc-id>].
   Returns a sequence of documents from :put operations."
  [txes]
  (for [tx txes
        tx-op (:xtdb.api/tx-ops tx)
        :when (= :xtdb.api/put (first tx-op))]
    (second tx-op)))

(defn read-edn-file
  "Read XTDB v1 transactions from an EDN file and extract documents.
   The file should contain a vector of transactions, where each transaction
   has :xtdb.api/tx-ops and :xtdb.api/tx-time keys."
  [file]
  (let [txes (edn/read-string {:readers (merge time-literals/tags
                                               {'inst #(Instant/parse %)})}
                              (slurp file))]
    (extract-docs-from-txes txes)))

(defn read-nippy-file
  "Read XTDB v1 transactions from a nippy file.
   Returns the raw transaction data (a vector of transactions)."
  [file]
  (nippy/thaw-from-file file))

;; ============================================================================
;; SQLite operations
;; ============================================================================

(defn insert-row!
  "Insert a single row into a SQLite table."
  [conn table row]
  (let [row (into {} (filter (comp some? val)) row)
        sql-map {:insert-into table
                 :values [row]}
        [sql-str & params] (sql/format sql-map)]
    (jdbc/execute! conn (into [sql-str] params))))

(defn insert-rows!
  "Insert multiple rows into SQLite in batches."
  [conn rows & {:keys [batch-size] :or {batch-size 100}}]
  (doseq [batch (partition-all batch-size rows)]
    (jdbc/with-transaction [tx conn]
      (doseq [{:keys [table row]} batch]
        (insert-row! tx table row)))))

;; ============================================================================
;; Main import functions
;; ============================================================================

(defn docs->rows
  "Convert a sequence of XTDB v1 documents to SQLite rows.
   Returns a sequence of {:table <keyword> :row <map>}."
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

(defn import-from-edn!
  "Import data from an EDN file into SQLite.

   The file should contain a vector of XTDB v1 transactions in the same
   format as the nippy files in storage/migrate-export/. Each transaction
   has :xtdb.api/tx-ops and :xtdb.api/tx-time keys.

   Options:
   - :edn-file - Path to EDN file containing XTDB v1 transactions"
  [{:keys [conn edn-file]}]
  (let [docs (read-edn-file edn-file)
        rows (docs->rows docs)]
    (log/info "Importing" (count rows) "rows from" edn-file)
    (insert-rows! conn rows)
    {:imported (count rows)}))

(defn import-from-nippy-files!
  "Import data from multiple nippy files (XTDB v1 transaction exports).

   This expects the files to be in the format written by
   com.biffweb.migrate.xtdb1/export! - each file contains a vector
   of XTDB v1 transactions.

   Options:
   - :dir - Directory containing nippy files"
  [{:keys [conn dir]}]
  (let [files (->> (io/file dir)
                   file-seq
                   (filter #(.isFile %))
                   (sort-by #(parse-long (.getName %))))]
    (log/info "Found" (count files) "nippy files in" dir)
    (doseq [f files
            :let [file-index (parse-long (.getName f))
                  _ (log/info "Processing file" file-index)
                  txes (read-nippy-file f)
                  docs (extract-docs-from-txes txes)
                  rows (docs->rows docs)]]
      (insert-rows! conn rows))
    :done))

;; ============================================================================
;; Pure function for testing document conversion
;; ============================================================================

(defn convert-docs
  "Pure function to convert a sequence of XTDB v1 documents to SQLite rows.
   Used for testing - does not perform any I/O."
  [docs]
  (docs->rows docs))
