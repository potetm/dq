(ns build
  (:require
    [clojure.edn :as edn]
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as deploy]))

(def basis
  (delay
    (b/create-basis {:project "deps.edn"})))


(def lib 'com.potetm/dq)
(def version "1.0.2")
(def jar-file (str "target/dq-" version ".jar"))
(def sources ["src"])
(def classes "target/classes")


(defn clean [& _]
  (b/delete {:path "target"}))


(defn jar [& _]
  (b/write-pom {:basis @basis
                :lib 'com.potetm/dq
                :version version
                :class-dir classes
                :src-dirs sources
                :pom-data [[:licenses
                            [:license
                             [:name "Eclipse Public License - v 2.0"]
                             [:url "https://opensource.org/license/epl-2-0/"]]]]})
  (b/copy-dir {:src-dirs sources
               :target-dir classes})
  (b/jar {:class-dir classes
          :jar-file jar-file}))


(defn deploy [& _]
  (deploy/deploy {:installer :remote
                  :sign-releases? false
                  :artifact jar-file
                  :pom-file (str classes "/META-INF/maven/com.potetm/dq/pom.xml")
                  :repository (edn/read-string (slurp (str (System/getProperty "user.home")
                                                           "/.clojars")))}))


(defn run [& _]
  (clean)
  (jar)
  (deploy))

(comment
  (clean)
  (jar)
  (deploy)

  ;; Did you update the README?
  (run))
