(ns com.yakread.lib.migrate.sqlite
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [com.biffweb.experimental :as biffx]
   [malli.core :as m]
   [malli.error :as me]
   [taoensso.nippy :as nippy]
   [tick.core :as tick]
   [xtdb.api :as xt]
   [xtdb.node :as xtn]
   [next.jdbc :as jdbc]
   [honey.sql :as sql]
   [next.jdbc.prepare :as p]
   [next.jdbc.result-set :as rs]
   [next.jdbc.date-time :as jdt]
   [taoensso.nippy :as nippy])
  (:import
   [java.util UUID]
   [com.zaxxer.hikari HikariConfig HikariDataSource]
    
   ))

(comment

  (def db-spec
    {:dbtype "sqlite"
     :dbname "storage/sqlite/test.db"})

  (def datasource
    (HikariDataSource.
     (doto (HikariConfig.)
       (.setJdbcUrl "jdbc:sqlite:storage/sqlite/main.db")
       (.setConnectionInitSql (str/join ";" ["PRAGMA journal_mode=WAL"
                                             "PRAGMA busy_timeout = 5000"
                                             "PRAGMA foreign_keys = ON"
                                             "PRAGMA synchronous = NORMAL"])))))

  (defn uuid->bytes [uuid]
    (let [bb (java.nio.ByteBuffer/allocate 16)]
      (.putLong bb (.getMostSignificantBits uuid))
      (.putLong bb (.getLeastSignificantBits uuid))
      (.array bb)))

  (defn bytes->uuid [byte-array]
    (let [bb (java.nio.ByteBuffer/wrap byte-array)]
      (java.util.UUID. (.getLong bb) (.getLong bb))))

  ;(extend-protocol p/SettableParameter
  ;  java.util.UUID
  ;  (set-parameter [^UUID uuid ^java.sql.PreparedStatement stmt ^long idx]
  ;    (let [bytes (uuid->bytes uuid)]
  ;      (.setBytes stmt idx bytes))))

  ;(extend-protocol rs/ReadableColumn
  ;  java.sql.Date
  ;  (read-column-by-label [^java.sql.Date v _]     v)
  ;  (read-column-by-index [^java.sql.Date v _2 _3] v)

  ;  java.sql.Timestamp
  ;  (read-column-by-label [^java.sql.Timestamp v _]     (.toInstant v))
  ;  (read-column-by-index [^java.sql.Timestamp v _2 _3] (.toInstant v)))


  ;(extend-protocol rs/ReadableColumn
  ;  (Class/forName "[B")
  ;  (read-column-by-label [v x]
  ;    (bytes->uuid v))
  ;  (read-column-by-index [v x y]
  ;    (if (= 16 (count v))
  ;      (bytes->uuid v)
  ;      v)))

  (defn my-format [sql-map]
    (sql/format
     (cond-> sql-map
       (contains? sql-map :values)
       (update :values #(mapv coerce-map-for-write %))

       (contains? sql-map :set)
       ;; This won't work for DML stuff
       (update :set coerce-map-for-write))))

  (with-open [conn (jdbc/get-connection datasource)]
    (jdbc/execute! conn (my-format
                         {:insert-into :users
                          :values [{:users/id 1
                                    :users/name "steve"
                                    :users/age 14
                                    :users/created-at (tick/instant)
                                    :users/external-id #uuid "08e19fd2-cecd-4283-9314-69d161f8f4ea"
                                    :users/likes-cheese true
                                    :users/plan :annual
                                    :users/days #{:monday :wednesday}
                                    :users/urls #{"abc" "123"}}]})))

  (jdbc/execute! datasource
                 (sql/format
                  {:update :users
                   :set {:users/age 16
                         :users/likes-cheese false}
                   :where [:= :users/id 1]}))

  (nippy/thaw (nippy/freeze #uuid "bfe7507e-96dc-4eb2-bfef-a53fbe376390"))
  (nippy/thaw (count (nippy/freeze true)))

  (count (nippy/freeze "abcdefghijklmnopqrstuvwxyz"))

  (jdt/read-as-instant)

  ;; set -> nippy
  ;; map -> nippy
  ;; vector -> nippy
  ;; instant -> long
  ;; boolean -> long
  ;; uuid -> bytes
  ;; enum -> long

  (= (type (nippy/fast-freeze "hello")) (Class/forName "[B")
     )

  (.getBytes "hello")
  (nippy/freeze #uuid "fe8a3c31-37a6-4873-8be3-e2b809686343")
  (uuid->bytes #uuid "fe8a3c31-37a6-4873-8be3-e2b809686343")


  (def k->coerce-fn
    {:users/days nippy/thaw
     :users/plan {0 :quarter 1 :annual}
     :users/created-at tick/instant
     :users/likes-cheese #(case % 0 false 1 true)
     :users/external-id bytes->uuid
     :users/urls nippy/thaw})

  (def nippy {:write nippy/fast-freeze
              :read nippy/fast-thaw})

  (let [check-value (fn [x coercion-map]
                      (when (and (some? x) (not (contains? coercion-map x)))
                        (throw (ex-info "Invalid enum value"
                                        {:value x
                                         :available-values (set (keys coercion-map))}))))]
    (defn enum [db->clj]
      (let [clj->db (into {}
                          (map (fn [[k v]]
                                 [v k]))
                          db->clj)]
        {:write (fn [x]
                  (check-value x clj->db)
                  (clj->db x))
         :read (fn [x]
                 (check-value x db->clj)
                 (db->clj x))})))

  (let [f java.time.Instant/ofEpochMilli]
    (f (inst-ms (java.util.Date.))))

  (def instant {:write inst-ms :read java.time.Instant/ofEpochMilli})
  (def bool (enum {0 false 1 true}))

  (def uuid {:write uuid->bytes :read bytes->uuid})

  (def coercions
    {:users/days nippy
     :users/urls nippy
     :users/plan (enum {0 :quarter
                        1 :annual})
     :users/created-at instant
     :users/likes-cheese bool
     :users/external-id uuid})

  (defn register-coercions! [coercions]
    ...)

  (coerce :users/likes-cheese true)

  (defn default-column-by-index-fn
    [builder ^java.sql.ResultSet rs ^Integer i]
    (let [k (nth (:cols builder) (dec i))
          value (.getObject rs i)
          coerce-fn (get-in coercions [k :read])
          value (if coerce-fn
                  (coerce-fn value)
                  value)]
      (rs/read-column-by-index value (:rsmeta builder) i)))

  (defn coerce-map-for-write [m]
    (into {}
          (map (fn [[k v]]
                 [k (if-some [coerce-fn (get-in coercions [k :write])]
                      (coerce-fn v)
                      v)]))
          m))

  (def conn (jdbc/get-connection datasource))

  (time
   (jdbc/execute! conn
                 (sql/format {:select :* :from :users})
                 {:builder-fn (rs/builder-adapter
                               rs/as-kebab-maps
                               default-column-by-index-fn)}))

  (time
   (dotimes [_ 50]
     (with-open [conn (jdbc/get-connection datasource)]
       :done)))

  (require '[next.jdbc.connection :as conn]
           '[hikari-cp.core :as hikari])

  (def db-spec
    {:jdbcUrl "jdbc:sqlite:your-database-file.db"})

  ;; Use this datasource with next.jdbc
  (jdbc/execute! datasource ["SELECT * FROM your_table"])

  )
