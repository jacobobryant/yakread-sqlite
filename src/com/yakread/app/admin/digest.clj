(ns com.yakread.app.admin.digest
  (:require
   [clojure.string :as str]
   [com.yakread.lib.graph :as lib.graph]
   [com.yakread.lib.middleware :as lib.mid]))

(defonce resolver-cache (atom nil))
(comment (reset! resolver-cache nil))

(def digest-template-route
  ["/admin/digest"
   {:middleware [lib.mid/wrap-profiled]
    :get
    (fn [{:keys [params] :as ctx}]
      (swap! resolver-cache
             (fn [cache]
               (into {}
                     (remove (fn [[[op-name _ _] _]]
                               (str/starts-with? (str op-name) "com.yakread.ui-components")))
                     cache)))
      (let [[output-key content-type] (if (= (:content-type params) "text")
                                        [:digest/text "text/plain"]
                                        [:digest/html "text/html"])
            result (lib.graph/query ctx [{:session/user [output-key]}])
            content   (get-in result [:session/user output-key])]
        {:status 200
         :headers {"content-type" content-type}
         :body content}))}])

(def module
  {:routes ["" {:middleware [lib.mid/wrap-admin]}
            digest-template-route]})
