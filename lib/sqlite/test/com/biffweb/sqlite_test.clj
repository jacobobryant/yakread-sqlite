(ns com.biffweb.sqlite-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [com.biffweb.sqlite :as biff.sqlite]
   [malli.core :as malli]
   [malli.registry :as malr]
   [next.jdbc :as jdbc])
  (:import
   [java.time Instant]))

(def test-malli-opts
  {:registry (malr/composite-registry
              (malli/default-schemas)
              {:user [:map {:closed true}
                      [:user/id :string]
                      [:user/name :string]
                      [:user/joined-at inst?]]})})

(def ^:dynamic *conn* nil)

(defn with-sqlite [f]
  (with-open [conn (jdbc/get-connection "jdbc:sqlite::memory:")]
    (jdbc/execute! conn ["CREATE TABLE user (id TEXT PRIMARY KEY, name TEXT NOT NULL, joined_at INT NOT NULL) STRICT"])
    (jdbc/execute! conn ["INSERT INTO user (id, name, joined_at) VALUES (?, ?, ?)"
                         "u1" "Alice" 1700000000000])
    (binding [*conn* conn]
      (f))))

(use-fixtures :each with-sqlite)

(deftest inference-fallback-coercion-test
  (let [ctx {:biff/conn *conn* :biff/malli-opts test-malli-opts}]
    (testing "direct column name matches coercion"
      (is (= [{:user/id "u1" :user/name "Alice" :user/joined-at (Instant/ofEpochMilli 1700000000000)}]
             (biff.sqlite/execute ctx ["SELECT id, name, joined_at FROM user"]))))

    (testing "aliased column falls back to inference"
      (is (= [{:user/joined-at-alias (Instant/ofEpochMilli 1700000000000)}]
             (biff.sqlite/execute ctx ["SELECT joined_at AS joined_at_alias FROM user"]))))

    (testing "non-SELECT statement does not fail on inference"
      (is (= [{:next.jdbc/update-count 0}]
             (biff.sqlite/execute ctx ["DELETE FROM user WHERE id = ?" "nonexistent"]))))))
