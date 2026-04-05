(ns com.yakread.lib.rss
  (:require
   [clj-xpath.core :as xpath]
   [clojure.java.io :as io]
   [com.biffweb :as biff]
   [remus])
  (:import
   [org.jsoup Jsoup]
   [org.xml.sax SAXParseException]))

(defn- remus-parse [response]
  (biff/catchall
   (remus/parse-http-resp (cond-> response
                            true
                            (update :body #(io/input-stream (.getBytes %)))

                            (contains? (:headers response) "Content-Type")
                            (assoc-in [:headers "Content-Type"] "application/xml")))))

(defn parse-urls* [base-url html]
  (->> (.select (Jsoup/parse html base-url)
                (str "link[type=application/rss+xml], "
                     "link[type=application/atom+xml]"))
       (mapv (fn [element]
               {:url (.attr element "abs:href")
                :title (.attr element "title")}))
       (filterv :url)))

(defn parse-urls [{:keys [url body] :as http-response}]
  (if (not-empty (:entries (remus-parse http-response)))
    [{:url url}]
    (when body
      (parse-urls* url body))))

(defn extract-opml-urls [body]
  ;; NOTE: by default java.xml prints all parsing errors to the console.
  (some->> (try (xpath/xml->doc body) (catch SAXParseException _))
           (xpath/$x:attrs* "//outline")
           (keep :xmlUrl)
           distinct))
