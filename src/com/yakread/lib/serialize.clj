(ns com.yakread.lib.serialize
  (:require [com.biffweb :as biff]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [taoensso.nippy :as nippy]
            [com.yakread.util.biff-staging :as biffs]))

(defn edn->base64 [edn]
  (biff/base64-encode (.getBytes (pr-str edn))))

(defn base64->edn [base64]
  (edn/read-string (String. (biff/base64-decode base64))))

(defn uuid->url [uuid]
  (assert (uuid? uuid))
  (let [ba (.. (java.nio.ByteBuffer/allocate 16)
               (putLong (.getMostSignificantBits uuid))
               (putLong (.getLeastSignificantBits uuid))
               array)]
    (subs (.encodeToString (java.util.Base64/getUrlEncoder) ba) 0 22)))

(defn url->uuid [s]
  (biff/catchall
   (let [buffer (-> (java.util.Base64/getUrlDecoder)
                    (.decode (str s "=="))
                    (java.nio.ByteBuffer/wrap))]
     (java.util.UUID. (.getLong buffer) (.getLong buffer)))))

(defn ewt-encode [secret params]
  (let [payload (biffs/base64-url-encode (nippy/fast-freeze params))]
    (str payload "." (biffs/signature secret payload))))

(defn ewt-decode [secret token]
  (let [[payload sig] (str/split token #"\." 2)]
    (when (= sig (biffs/signature secret payload))
      (let [bytes (biffs/base64-url-decode payload)]
        (try
          (nippy/fast-thaw bytes)
          (catch Exception _
            (nippy/thaw bytes)))))))
