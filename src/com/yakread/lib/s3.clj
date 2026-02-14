(ns com.yakread.lib.s3
  (:require
   [buddy.core.mac :as mac]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :as biff]))

(defn hmac-sha1-base64 [secret s]
  (-> (mac/hash s {:key secret :alg :hmac+sha1})
      biff/base64-encode))

;; use the filesystem instead of S3
(defn mock-request [_ input]
  (let [file (io/file "storage/mock-s3" (:key input))]
    (case (:method input)
      "PUT" (spit (doto file io/make-parents)
                  (pr-str (select-keys input [:headers :body])))
      "GET" (edn/read-string (slurp file))
      "DELETE" (do (.delete file) nil))))

(defn translate-config [{:keys [biff.s3/config-ns biff/secret] :as ctx}]
  (cond-> ctx
    config-ns (merge (biff/select-ns-as ctx config-ns 'biff.s3)
                     {:biff/secret
                      (fn [k]
                        (or (when (= k :biff.s3/secret-key)
                              (secret (keyword (str config-ns) "secret-key")))
                            (secret k)))})))

(defn request [ctx input]
  (let [merged (translate-config (merge ctx (biff/select-ns-as input nil 'biff.s3)))]
    (if (:biff.s3/origin merged)
      (biff/s3-request merged)
      (mock-request ctx input))))

(defn presigned-url*
  "Generate a presigned S3 URL using Signature V2"
  [{:biff/keys [secret now]
    :biff.s3/keys [origin access-key bucket key method expires-at headers]}]
  (let [expires (or (quot (inst-ms expires-at) 1000)
                    (+ (quot (inst-ms now) 1000) (* 60 10))) ;; default 10 min from now
        path (str "/" bucket "/" key)
        headers' (->> headers
                      (mapv (fn [[k v]] [(str/lower-case (str/trim k)) (str/trim v)]))
                      (into {}))
        content-type (get headers' "content-type")
        canonicalized-amz-headers (->> headers'
                                       (filterv (fn [[k v]] (str/starts-with? k "x-amz-")))
                                       (sort-by first)
                                       (mapv (fn [[k v]] (str k ":" v "\n")))
                                       (apply str))
        string-to-sign (str method "\n\n" content-type "\n" expires "\n" canonicalized-amz-headers path)
        signature (hmac-sha1-base64 (secret :biff.s3/secret-key) string-to-sign)
        params (merge {"AWSAccessKeyId" access-key
                       "Expires" (str expires)
                       "Signature" signature}
                      (when content-type
                        {"response-content-type" content-type}))
        query-str (->> params
                       (map (fn [[k v]] (str (java.net.URLEncoder/encode k "UTF-8")
                                             "="
                                             (java.net.URLEncoder/encode v "UTF-8"))))
                       (str/join "&"))
        url (str origin path "?" query-str)]
    url))

(defn presigned-url [ctx input]
  (->> (biff/select-ns-as input nil 'biff.s3)
       (merge ctx)
       translate-config
       (merge {:biff.s3/method "GET"})
       presigned-url*))
