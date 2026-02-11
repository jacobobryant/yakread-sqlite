(ns com.yakread.lib.sqlite
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.java.process :as proc]
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
   [com.zaxxer.hikari HikariConfig HikariDataSource]
   [java.nio ByteBuffer]
   [java.sql ResultSet]
   [java.time Instant ZonedDateTime]
   [java.util UUID]))

;; ============================================================================
;; Schema Info Extraction
;; ============================================================================

(defn- table-id-key
  "Get the ID key for a table (e.g., :user -> :user/id)"
  [table-key]
  (keyword (name table-key) "id"))

(defn- table-ast?
  "Check if an AST represents a table (has an id column)."
  [table-key ast]
  (and (= :map (:type ast))
       (contains? (:keys ast) (table-id-key table-key))))

(defn- deref-ast
  "Dereference a schema and get its AST."
  [schema malli-opts]
  (some-> (try (malli/deref-recursive schema malli-opts) (catch Exception _))
          malli/ast))

(defn- table-asts
  "Get all table ASTs from a schema."
  [table-key malli-opts]
  (when-let [ast (deref-ast table-key malli-opts)]
    (if (table-ast? table-key ast)
      [ast]
      (->> (tree-seq (constantly true) :children ast)
           (filterv #(table-ast? table-key %))))))

(comment
  
  (let [table-key :user]
    (deref-ast table-key malli-opts)
    #_(when-let [ast (deref-ast table-key malli-opts)]
      (if (table-ast? table-key ast)
        [ast]
        (->> (tree-seq (constantly true) :children ast)
             (filterv #(table-ast? table-key %)))))))

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

(comment
  (table-asts :user malli-opts)
  )

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
      (into {} (map-indexed (fn [i v] [i v]) (:values value-ast))))))

(defn- infer-coercion-type
  "Infer the coercion type for an attribute from its malli AST."
  [ast]
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
;; Type Coercion
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

(defn uuid->bytes
  "Convert a UUID to a 16-byte array for SQLite BLOB storage."
  [^UUID uuid]
  (let [bb (ByteBuffer/allocate 16)]
    (.putLong bb (.getMostSignificantBits uuid))
    (.putLong bb (.getLeastSignificantBits uuid))
    (.array bb)))

(defn- inst->epoch-ms
  "Convert an Instant or ZonedDateTime to epoch milliseconds."
  [x]
  (when x
    (if (instance? ZonedDateTime x)
      (.toEpochMilli (.toInstant ^ZonedDateTime x))
      (.toEpochMilli ^Instant x))))

(defn- bool->int
  "Convert a boolean to 0 or 1 for SQLite."
  [b]
  (if b 1 0))

(defn fast-freeze
  "Freeze a value using nippy fast-freeze."
  [v]
  (when v
    (nippy/fast-freeze v)))

(defn- make-enum-writer
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

(defn- build-coercions
  "Build coercion maps for a table's attributes.
   Returns {:read {attr coerce-fn} :write {attr coerce-fn}}"
  [attrs]
  (reduce
   (fn [acc [attr ast]]
     (let [coerce-type (infer-coercion-type ast)]
       (if coerce-type
         (let [read-fn (get-coerce-read-fn coerce-type)
               write-fn (get-coerce-write-fn coerce-type)]
           (cond-> acc
             read-fn (assoc-in [:read attr] read-fn)
             write-fn (assoc-in [:write attr] write-fn)))
         acc)))
   {:read {} :write {}}
   attrs))

(defn write-coercions
  "Build write coercion map for a table: {attr-keyword -> coerce-fn}.
   Used for converting Clojure values to SQLite values (enums, UUIDs, etc.)."
  [malli-opts table-key]
  (let [attrs (->> (table-asts table-key malli-opts)
                   (mapv :keys)
                   (reduce attr-union {}))]
    (:write (build-coercions attrs))))

(defn read-coercions
  "Build read coercion map for a table: {attr-keyword -> coerce-fn}.
   Used for converting SQLite values to Clojure values (enums, UUIDs, etc.)."
  [malli-opts table-key]
  (let [attrs (->> (table-asts table-key malli-opts)
                   (mapv :keys)
                   (reduce attr-union {}))]
    (:read (build-coercions attrs))))

(defn- make-column-reader
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

(defn- sql-name
  "Convert an attribute keyword to SQLite column name."
  [k]
  (str/replace (name k) "-" "_"))

(defn- generate-column-def
  "Generate a single column definition."
  [table-key attr ast]
  (let [col-name (sql-name attr)
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
    {:line (str col-name " " col-type
                (when is-primary? " PRIMARY KEY")
                (when-not optional? " NOT NULL")
                check-constraint)
     :comment enum-comment}))

(defn- generate-foreign-keys
  "Generate FOREIGN KEY constraints for a table."
  [attrs]
  (into []
        (keep (fn [[attr ast]]
                (when-let [ref-target (get-in ast [:properties :biff/ref])]
                  (let [col-name (sql-name attr)]
                    {:line (str "FOREIGN KEY(" (sql-name col-name) ") REFERENCES "
                                (sql-name ref-target) "(id)")}))))
        attrs))

(defn- generate-create-table
  "Generate a CREATE TABLE statement for a table."
  [table-key attrs]
  (let [col-defs (->> attrs
                      (sort-by (comp :order second))
                      (mapv (fn [[attr-key ast]]
                              (generate-column-def table-key
                                                   attr-key
                                                   ast))))
        fk-constraints (generate-foreign-keys attrs)
        lines (concat col-defs fk-constraints)
        lines (into []
                        (map-indexed (fn [i {:keys [line ] comment* :comment}]
                                       (str "  "
                                            line
                                            (when (not= (inc i) (count lines))
                                              ",")
                                            comment*)))
                        lines)]
    (str "CREATE TABLE " (sql-name table-key) " (\n"
         (str/join "\n" lines)
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
  [malli-opts]
  (let [info (schema-info malli-opts)
        table-order (infer-table-order info)]
    (str/join "\n\n"
              (for [table table-order
                    :let [attrs (get info table)]
                    :when attrs]
                (generate-create-table table attrs)))))

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

(defn sqlite-resolvers
  "Create Pathom resolvers for SQLite tables from malli schema.

   For reference attributes ending in -id:
   - Returns the raw ID as :table/ref-id
   - Also returns a join without the -id suffix as {:ref-table/id uuid}

   Example: {:ad/user-id #uuid \"...\", :ad/user {:user/id #uuid \"...\"}}"
  [malli-opts]
  (for [[table-key attrs] (schema-info malli-opts)
        :let [id-key (table-id-key table-key)
              coercions (build-coercions attrs)
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
                            inputs))))))


;; ============================================================================
;; Other stuff
;; ============================================================================


(defn use-sqlite
  "Biff component that starts a HikariCP connection pool for SQLite
   and puts it in the :biff/conn* key."
  [{:biff/keys [malli-opts*]
    :biff.sqlite/keys [db-path schema-path indexes-path generate-schema]
    :or {db-path "storage/sqlite/main.db"
         schema-path "resources/schema.sql"
         indexes-path "resources/indexes.sql"}
    :as ctx}]
  (when-not (fs/which "sqlite3def")
    (throw (ex-info "sqlite3def must be installed. See https://github.com/sqldef/sqldef" {})))
  (doseq [path [db-path schema-path]]
    (io/make-parents path))
  (when generate-schema
    (spit schema-path (str "-- Auto-generated; do not edit.\n\n"
                           (generate-schema-sql malli-opts*)
                           "\n\n"
                           (slurp indexes-path))))
  (print (proc/exec "sqlite3def" db-path "--apply" "-f" schema-path))
  (let [datasource (HikariDataSource.
                    (doto (HikariConfig.)
                      (.setJdbcUrl (str "jdbc:sqlite:" db-path))
                      (.setConnectionInitSql
                       (str/join ";" ["PRAGMA journal_mode=WAL"
                                      "PRAGMA busy_timeout = 5000"
                                      "PRAGMA foreign_keys = ON"
                                      "PRAGMA synchronous = NORMAL"]))))]
    (-> ctx
        (assoc :biff/conn* datasource)
        (update :biff/stop conj #(.close datasource)))))
