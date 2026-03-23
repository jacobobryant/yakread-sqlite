(ns com.yakread.lib.auth
  (:require [com.biffweb :as biff]
            [com.biffweb.sqlite :as biff.sqlite]
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

(defn- get-user-id [ctx email]
  (-> (biff.sqlite/execute ctx {:select :user/id
                               :from :user
                               :where [:= :user/email email]})
      first
      :user/id))

(defn- create-user! [ctx email]
  (let [user-id (random-uuid)]
    (biff.sqlite/execute ctx
      {:insert-into :user
       :values [{:user/id user-id
                 :user/email email
                 :user/joined-at (tick/instant)}]
       :on-conflict [:user/id]
       :do-nothing true})
    user-id))

(defn- uuid-from [s]
  (UUID/nameUUIDFromBytes (.getBytes s)))

(defn- upsert-code! [ctx email code]
  (biff.sqlite/execute ctx
    {:insert-into :auth-code
     :values [{:auth-code/id (uuid-from email)
               :auth-code/email email
               :auth-code/code code
               :auth-code/created-at (tick/instant)
               :auth-code/failed-attempts 0}]
     :on-conflict [:auth-code/id]
     :do-update-set {:fields [:code :created-at :failed-attempts]}}))

(defn- get-code [ctx email]
  (first
   (biff.sqlite/execute ctx
     {:select :*
      :from :auth-code
      :where [:= :auth-code/id (uuid-from email)]})))

(defn- delete-code! [ctx email]
  (biff.sqlite/execute ctx
    {:delete-from :auth-code
     :where [:= :auth-code/id (uuid-from email)]}))

(defn- increment-failed-attempts! [ctx email]
  (biff.sqlite/execute ctx
    {:update :auth-code
     :set {:auth-code/failed-attempts [:+ :auth-code/failed-attempts 1]}
     :where [:= :auth-code/id (uuid-from email)]}))

;;; --- Send / Verify logic ---

(defn send-link! [{:keys [biff.auth/email-validator
                          biff/send-email
                          params]
                   :as   ctx}]
  (let [email   (biff/normalize-email (:email params))
        url     (new-link ctx email)
        user-id (delay (get-user-id ctx email))]
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
                          biff/send-email
                          params]
                   :as   ctx}]
  (let [email   (biff/normalize-email (:email params))
        code    (new-code 6)
        user-id (delay (get-user-id ctx email))]
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

;;; --- Route handlers ---

(defn send-link-handler [{:keys [biff.auth/single-opt-in
                                 params]
                          :as   ctx}]
  (let [{:keys [success error email user-id]} (send-link! ctx)]
    (when (and success single-opt-in (not user-id))
      (create-user! ctx email))
    {:status  303
     :headers {"location" (if success
                            (str "/link-sent?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-link-handler [{:keys [biff.auth/app-path
                                   biff.auth/invalid-link-path
                                   session
                                   params
                                   path-params]
                            :as   ctx}]
  (let [{:keys [success error email]} (verify-link ctx)
        existing-user-id              (when success (get-user-id ctx email))
        token                         (:token (merge params path-params))]
    (when (and success (not existing-user-id))
      (create-user! ctx email))
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
                                        (get-user-id ctx email))))}))

(defn send-code-handler [{:keys [biff.auth/single-opt-in
                                 params]
                          :as   ctx}]
  (let [{:keys [success error email code user-id]} (send-code! ctx)]
    (when success
      (upsert-code! ctx email code)
      (when (and single-opt-in (not user-id))
        (create-user! ctx email)))
    {:status  303
     :headers {"location" (if success
                            (str "/verify-code?email=" (:email params))
                            (str (:on-error params "/") "?error=" error))}}))

(defn verify-code-handler [{:keys [biff.auth/app-path
                                   params
                                   session]
                            :as   ctx}]
  (let [email    (biff/normalize-email (:email params))
        {:auth-code/keys [code created-at failed-attempts]} (get-code ctx email)
        success  (and (passed-recaptcha? ctx)
                      (some? code)
                      (< failed-attempts 3)
                      (< (tick/between created-at (tick/instant) :minutes) 3)
                      (= (:code params) code))
        existing-user-id (when success (get-user-id ctx email))]
    (cond
      success
      (do
        (delete-code! ctx email)
        (when-not existing-user-id
          (create-user! ctx email)))

      (and (not success) (some? code) (< failed-attempts 3))
      (increment-failed-attempts! ctx email))
    (if success
      {:status  303
       :headers {"location" app-path}
       :session (assoc session :uid (or existing-user-id
                                        (get-user-id ctx email)))}
      {:status  303
       :headers {"location" (str "/verify-code?error=invalid-code&email=" email)}})))

(defn signout [{:keys [session]}]
  {:status  303
   :headers {"location" "/"}
   :session (dissoc session :uid)})

;;; --- Module ---

(def default-options
  #:biff.auth{:app-path          "/app"
              :invalid-link-path "/signin?error=invalid-link"
              :check-state       true
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
