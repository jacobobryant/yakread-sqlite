(ns com.yakread.lib.auth
  "Local copy of com.biffweb.experimental.auth, rewritten to use SQLite
   instead of XTDB. This avoids alter-var-root monkey-patching."
  (:require [com.biffweb :as biff]
            [com.yakread.util.biff-staging :as biffs]
            [clj-http.client :as http]
            [tick.core :as tick])
  (:import [java.util UUID]))

(defn passed-recaptcha? [{:keys [biff/secret biff.recaptcha/threshold params]
                          :or   {threshold 0.5}}]
  (or (nil? (secret :recaptcha/secret-key))
      (let [{:keys [success score]}
            (:body
             (http/post "https://www.google.com/recaptcha/api/siteverify"
                        {:form-params {:secret   (secret :recaptcha/secret-key)
                                       :response (:g-recaptcha-response params)}
                         :as          :json}))]
        (and success (or (nil? score) (<= threshold score))))))

(defn email-valid? [_ctx email]
  (and email
       (re-matches #".+@.+\..+" email)
       (not (re-find #"\s" email))))

(defn new-link [{:keys [biff.auth/check-state
                        biff/base-url
                        biff/secret
                        anti-forgery-token]}
                email]
  (str base-url "/auth/verify-link/"
       (biff/jwt-encrypt
        (cond-> {:intent "signin"
                 :email  email
                 :exp-in (* 60 60)}
          check-state (assoc :state (biff/sha256 anti-forgery-token)))
        (secret :biff/jwt-secret))))

(defn new-code [length]
  (let [rng (java.security.SecureRandom.)]
    (format (str "%0" length "d")
            (.nextInt rng (dec (int (Math/pow 10 length)))))))

(defn get-user-id
  "Look up user ID by email from SQLite."
  [conn* email]
  (-> (biffs/q conn*
        {:select :user/id
         :from :user
         :where [:= :user/email email]})
      first
      :user/id))

(defn new-user-tx [_ctx email]
  [[:put-docs :user {:xt/id          (random-uuid)
                     :user/email     email
                     :user/joined-at (tick/zoned-date-time)}]])

(defn send-link! [{:keys [biff.auth/email-validator
                          biff/conn*
                          biff.auth/get-user-id
                          biff/send-email
                          params]
                   :as   ctx}]
  (let [email   (biff/normalize-email (:email params))
        url     (new-link ctx email)
        user-id (delay (get-user-id conn* email))]
    (cond
      (not (passed-recaptcha? ctx))
      {:success false :error "recaptcha"}

      (not (email-validator ctx email))
      {:success false :error "invalid-email"}

      (not (send-email ctx
                       {:template    :signin-link
                        :to          email
                        :url         url
                        :user-exists (some? @user-id)}))
      {:success false :error "send-failed"}

      :else
      {:success true :email email :user-id @user-id})))

(defn verify-link [{:keys [biff.auth/check-state
                           biff/secret
                           path-params
                           params
                           anti-forgery-token]}]
  (let [{:keys [intent email state]} (-> (merge params path-params)
                                         :token
                                         (biff/jwt-decrypt (secret :biff/jwt-secret)))
        valid-state                  (= state (biff/sha256 anti-forgery-token))
        valid-email                  (= email (:email params))]
    (cond
      (not= intent "signin")
      {:success false :error "invalid-link"}

      (or (not check-state) valid-state valid-email)
      {:success true :email email}

      (some? (:email params))
      {:success false :error "invalid-email"}

      :else
      {:success false :error "invalid-state"})))

(defn send-code! [{:keys [biff.auth/email-validator
                          biff/conn*
                          biff/send-email
                          biff.auth/get-user-id
                          params]
                   :as   ctx}]
  (let [email   (biff/normalize-email (:email params))
        code    (new-code 6)
        user-id (delay (get-user-id conn* email))]
    (cond
      (not (passed-recaptcha? ctx))
      {:success false :error "recaptcha"}

      (not (email-validator ctx email))
      {:success false :error "invalid-email"}

      (not (send-email ctx
                       {:template    :signin-code
                        :to          email
                        :code        code
                        :user-exists (some? @user-id)}))
      {:success false :error "send-failed"}

      :else
      {:success true :email email :code code :user-id @user-id})))

;;; HANDLERS -------------------------------------------------------------------

(defn- uuid-from [s]
  (UUID/nameUUIDFromBytes (.getBytes s)))

(defn send-link-handler [{:keys [biff.auth/single-opt-in
                                 biff.auth/new-user-tx
                                 params]
                          :as   ctx}]
  (let [{:keys [success error email user-id]} (send-link! ctx)]
    (when (and success single-opt-in (not user-id))
      (biffs/submit-tx ctx (new-user-tx ctx email)))
    {:status  303
     :headers {"location" (if success
                            (str "/link-sent?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-link-handler [{:keys [biff.auth/app-path
                                   biff.auth/invalid-link-path
                                   biff.auth/new-user-tx
                                   biff.auth/get-user-id
                                   biff/conn*
                                   session
                                   params
                                   path-params]
                            :as   ctx}]
  (let [{:keys [success error email]} (verify-link ctx)
        existing-user-id              (when success (get-user-id conn* email))
        token                         (:token (merge params path-params))]
    (when (and success (not existing-user-id))
      (biffs/submit-tx ctx (new-user-tx ctx email)))
    {:status  303
     :headers {"location" (cond
                            success
                            app-path

                            (= error "invalid-state")
                            (str "/verify-link?token=" token)

                            (= error "invalid-email")
                            (str "/verify-link?error=incorrect-email&token=" token)

                            :else
                            invalid-link-path)}
     :session (cond-> session
                success (assoc :uid (or existing-user-id
                                        (get-user-id conn* email))))}))

(defn send-code-handler [{:keys [biff.auth/single-opt-in
                                 biff.auth/new-user-tx
                                 biff/conn*
                                 biff.auth/get-user-id
                                 params]
                          :as   ctx}]
  (let [{:keys [success error email code user-id]} (send-code! ctx)]
    (when success
      (biffs/submit-tx ctx
        (concat [[:put-docs :biff.auth/code
                  {:xt/id                          (uuid-from email)
                   :biff.auth.code/email           email
                   :biff.auth.code/code            code
                   :biff.auth.code/created-at      (tick/zoned-date-time)
                   :biff.auth.code/failed-attempts 0}]]
                (when (and single-opt-in (not user-id))
                  (new-user-tx ctx email)))))
    {:status  303
     :headers {"location" (if success
                            (str "/verify-code?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-code-handler [{:keys [biff.auth/app-path
                                   biff.auth/new-user-tx
                                   biff.auth/get-user-id
                                   biff/conn*
                                   params
                                   session]
                            :as   ctx}]
  (let [email            (biff/normalize-email (:email params))
        code-id          (uuid-from email)

        [{:biff-auth-code/keys [code created-at failed-attempts]}]
        (biffs/q conn*
                 {:select [:biff-auth-code/code
                           :biff-auth-code/created-at
                           :biff-auth-code/failed-attempts]
                  :from :biff-auth-code
                  :where [:= :biff-auth-code/id code-id]})

        success          (and (passed-recaptcha? ctx)
                              (some? code)
                              (< failed-attempts 3)
                              (< (tick/between created-at (tick/zoned-date-time) :minutes) 3)
                              (= (:code params) code))
        existing-user-id (when success (get-user-id conn* email))
        tx               (cond
                           success
                           (concat [[:delete-docs :biff.auth/code code-id]]
                                   (when-not existing-user-id
                                     (new-user-tx ctx email)))

                           (and (not success)
                                (some? code)
                                (< failed-attempts 3))
                           [[:put-docs :biff.auth/code
                             {:xt/id                          code-id
                              :biff.auth.code/email           email
                              :biff.auth.code/code            code
                              :biff.auth.code/created-at      created-at
                              :biff.auth.code/failed-attempts (inc failed-attempts)}]])]
    (biffs/submit-tx ctx tx)
    (if success
      {:status  303
       :headers {"location" app-path}
       :session (assoc session :uid (or existing-user-id
                                        (get-user-id conn* email)))}
      {:status  303
       :headers {"location" (str "/verify-code?error=invalid-code&email=" email)}})))

(defn signout [{:keys [session]}]
  {:status  303
   :headers {"location" "/"}
   :session (dissoc session :uid)})

;;; ----------------------------------------------------------------------------

(def default-options
  #:biff.auth{:app-path          "/app"
              :invalid-link-path "/signin?error=invalid-link"
              :check-state       true
              :new-user-tx       new-user-tx
              :get-user-id       get-user-id
              :single-opt-in     false
              :email-validator   email-valid?})

(defn wrap-options [handler options]
  (fn [ctx]
    (handler (merge options ctx))))

(defn module [options]
  {:routes [["/auth" {:middleware [[wrap-options (merge default-options options)]]}
             ["/send-link"          {:post send-link-handler}]
             ["/verify-link/:token" {:get verify-link-handler}]
             ["/verify-link"        {:post verify-link-handler}]
             ["/send-code"          {:post send-code-handler}]
             ["/verify-code"        {:post verify-code-handler}]
             ["/signout"            {:post signout}]]]})
