(ns com.yakread.lib.form
  (:require
   [com.biffweb :as biff]
   [com.yakread.lib.core :as lib.core]
   [fast-edn.core :as fedn]))

;; TODO handle multiple checkboxes with same name

(defn- memoize-latest
  "Like memoize but only cache the latest result"
  [f]
  (let [cache (atom {})]
    (fn [& args]
      (let [cache* @cache]
        (if (contains? cache* args)
          (get cache* args)
          (let [result (apply f args)]
            (reset! cache {args result})
            result))))))

(defn- parse-form [field->parser form-params]
  (reduce-kv (fn [params k v]
               (if-some [field-or-path (biff/catchall (fedn/read-string k))]
                 (let [path (if (vector? field-or-path) field-or-path [field-or-path])
                       field (last path)
                       parser (get field->parser field identity)
                       value (biff/catchall (some-> v not-empty parser))]
                   (cond-> params
                     value (assoc-in path value)))
                 params))
             {}
             form-params))

(defn- parsers [columns]
  (into {}
        (for [[field col-def] columns
              :let [type (:type col-def)]]
          [field (case type
                   :boolean #(= "on" %)
                   :int Long/parseLong
                   :uuid parse-uuid
                   :text identity
                   :inst #(java.time.Instant/parse %)
                   :enum identity
                   :edn identity
                   :real #(Double/parseDouble %)
                   identity)])))

(def ^:private memo-parsers (memoize-latest parsers))

(defn wrap-parse-form [handler & {:keys [overrides]}]
  (fn [{:keys [biff.sqlite/columns biff.form/parser-overrides form-params query-params] :as ctx}]
     (let [params (merge form-params query-params)
           new-params (lib.core/->DerefMap
                       (delay (-> (memo-parsers columns)
                                  (merge parser-overrides overrides)
                                  (parse-form params))))]
       (handler (cond-> ctx
                  (not-empty params)
                  (assoc :biff.form/params new-params))))))
