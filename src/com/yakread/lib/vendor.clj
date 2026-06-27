(ns com.yakread.lib.vendor
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.tools.logging :as log])
  (:import
   [java.net URI]
   [java.nio.file Files StandardCopyOption]))

(def dependencies
  {"cdn.jsdelivr.net/npm/htmx.org@2.0.5/dist/htmx.min.js"
   "https://cdn.jsdelivr.net/npm/htmx.org@2.0.5/dist/htmx.min.js"

   "unpkg.com/idiomorph@0.7.3.js"
   "https://unpkg.com/idiomorph@0.7.3"

   "unpkg.com/idiomorph@0.7.3/dist/idiomorph-ext.min.js"
   "https://unpkg.com/idiomorph@0.7.3/dist/idiomorph-ext.min.js"

   "unpkg.com/hyperscript.org@0.9.14.js"
   "https://unpkg.com/hyperscript.org@0.9.14"

   "cdnjs.cloudflare.com/ajax/libs/dompurify/3.2.6/purify.min.js"
   "https://cdnjs.cloudflare.com/ajax/libs/dompurify/3.2.6/purify.min.js"

   "cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js"
   "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-beta.9/bundles/datastar.js"})

(def vendor-dir "resources/public/vendor")

(def local-function-dirs
  ["cloud-fns/packages/yakread/readability"
   "cloud-fns/packages/yakread/juice"])

(defn- download! [relative-path url]
  (let [target (io/file vendor-dir relative-path)
        temp   (io/file (str (.getPath target) ".tmp"))]
    (.mkdirs (.getParentFile target))
    (log/info "Downloading vendored dependency" url)
    (with-open [in (.openStream (.toURL (URI/create url)))]
      (io/copy in temp))
    (Files/move (.toPath temp)
                (.toPath target)
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))))

(defn ensure! []
  (doseq [[relative-path url] dependencies
          :when (not (.isFile (io/file vendor-dir relative-path)))]
    (download! relative-path url)))

(defn- install-local-function-dependencies! [dir]
  (when-not (.isDirectory (io/file dir "node_modules"))
    (log/info "Installing local Cloud Function dependencies" dir)
    (let [{:keys [exit err]} (shell/sh "npm" "install" "--no-audit" "--no-fund" :dir dir)]
      (when-not (zero? exit)
        (throw (ex-info "Failed to install local Cloud Function dependencies"
                        {:dir dir :exit exit :err err}))))))

(defn ensure-local-function-dependencies! []
  (doseq [dir local-function-dirs]
    (install-local-function-dependencies! dir)))

(defn use-vendor-dependencies [{:com.yakread.fx.js/keys [local] :as ctx}]
  (ensure!)
  (when local
    (ensure-local-function-dependencies!))
  ctx)
