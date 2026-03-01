(ns com.yakread.app.admin.infer-email-url
  (:require
   [clj-http.client :as http]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.biffweb :as biff]
   [com.yakread.lib.middleware :as lib.mid]
   [com.yakread.lib.smtp :as lib.smtp])
  (:import
   [org.jsoup Jsoup]))

(defonce issues #{})

(defn clean-url [url]
  (str/replace (or (last
                    (:trace-redirects
                     (biff/catchall
                      (http/head url
                                 {:redirect-strategy :lax
                                  :throw-exceptions false}))))
                   url)
               #"\?.*"
               ""))

(comment
  
  (with-open [r (io/reader "sample-export/likes.edn")]
    (def issues
      (into #{}
            (map (comp :event/issue edn/read-string))
            (line-seq r))))

  )

(defonce s3-cache (atom {}))

(defn strip-brackets [s]
  (str/replace s #"(^<|>$)" ""))

(defn infer-post-url [headers html]
  (biff/pprint headers)
  (biff/pprint (select-keys headers ["list-post" "list-url" "list-archive" "url" "html"]))
  (println " ")
  (let [jsoup-parsed (Jsoup/parse html)
        url (some-> (or (some-> (get-in headers ["list-post" 0]) strip-brackets)
                        (some-> (.select jsoup-parsed "a.post-title-link")
                                first
                                (.attr "abs:href"))
                        (some->> (.select jsoup-parsed "a")
                                 (filter #(re-find #"(?i)read online" (.text %)))
                                 first
                                 (#(.attr % "abs:href"))))
                    clean-url)]
    (when-not (some-> url (str/includes? "link.mail.beehiiv.com"))
      url)))


(defn eh [{:keys [params] :as ctx}]
  (if-not (:issue params)
    {:status 303
     :headers {"location" (str "/admin/parse-email?issue=" (rand-nth (vec issues)))}}
    (let [{:keys [issue]} params
          issue (parse-uuid issue)
          _ (when-not (contains? @s3-cache issue)
              (swap! s3-cache assoc issue
                     (do
                       (println "hitting s3")
                       (biff/catchall
                        (-> (biff/s3-request
                             {:biff/secret {:biff.s3/secret-key ""}
                              :biff.s3/access-key ""
                              :biff.s3/origin ""
                              :biff.s3/bucket ""
                              :biff.s3/method "GET"
                              :biff.s3/key (str issue "-raw")})
                            :body
                            lib.smtp/parse
                            lib.smtp/datafy-message)))))
          message (get @s3-cache issue)
          {:keys [headers]} message
          html (some-> message lib.smtp/extract-html)]
      (if (nil? message)
        {:status 303
         :headers {"location" (str "/admin/parse-email?issue=" (rand-nth (vec issues)))}}
        (do
          (println)
          (infer-post-url headers html)
          (println)
          {:status 200
           :headers {"content-type" "text/html"}
           :body html})))))

(def infer-email-url-route
  ["/admin/parse-email"
   {:middleware [lib.mid/wrap-profiled]
    :get #(eh %)}])

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            infer-email-url-route]})
