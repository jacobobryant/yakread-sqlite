(ns com.yakread.lib.user
  (:require
   [clojure.string :as str]
   [com.yakread.lib.core :as lib.core]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]))

(let [reserved #{"hello"
                 "support"
                 "contact"
                 "admin"
                 "administrator"
                 "webmaster"
                 "hostmaster"
                 "postmaster"}]
  (defn normalize-email-username [username]
    (let [username (-> (or username "")
                       (str/lower-case)
                       (str/replace #"[^a-z0-9\.]" "")
                       (->> (take 20)
                            (apply str)))]
      (when-not (reserved username)
        username))))

(defn email-username-taken? [conn username]
  (-> (jdbc/execute-one! conn
                         (sql/format
                          {:select [[[:or
                                      [:exists
                                       {:select [[[:inline 1]]]
                                        :from :user
                                        :where [:= :email-username username]
                                        :limit [:inline 1]}]
                                      [:exists
                                       {:select [[[:inline 1]]]
                                        :from :deleted-user
                                        :where [:= :email-username-hash (lib.core/sha256 username)]
                                        :limit [:inline 1]}]]
                                     :taken]]}))
      :taken
      (= 1)))
