(set-env!
 :source-paths   #{"src"}
 :test-paths     #{"test"}
 :resource-paths #{"resources" "config"}
 :dependencies
 '[[org.clojure/clojure "1.8.0"]
   [io.pedestal/pedestal.service "0.5.2"]
   [io.pedestal/pedestal.jetty "0.5.2"]
   [org.clojure/data.json       "0.2.6"]
   ;; [com.taoensso/nippy "2.13.0"]

   [ns-tracker "0.3.1"]
   [environ "1.1.0"]

   ;; HTTP client to request 3rd part payment RESTful APIs
   [http-kit "2.2.0"]

   ;; Datomic, if your heart desires it
   [com.datomic/datomic-pro "0.9.5561"
    :exclusions [joda-time
                 org.slf4j/slf4j-nop
                 org.slf4j/slf4j-log4j12]]
   [io.rkn/conformity "0.4.0"
    :exclusions [com.datomic/datomic-free]]
   [org.postgresql/postgresql "9.3-1102-jdbc41"]

   [org.clojure/algo.generic  "0.1.2"]
   ;; Logging
   [ch.qos.logback/logback-classic "1.1.2"
    :exclusions [org.slf4j/slf4j-api]]
   [org.slf4j/jul-to-slf4j "1.7.7"]
   [org.slf4j/jcl-over-slf4j "1.7.7"]
   [org.slf4j/log4j-over-slf4j "1.7.7"]])

(def version "0.0.1-SNAPSHOT")
(task-options! pom {:project 'miaomfood-api
                    :version (str version "-standalone")
                    :description "FIXME: write description"
                    :license {"miaomfood.com" "All Rights Reserved"}})

;; == Datomic =============================================
(load-data-readers!)

(deftask bootstrap
  "Bootstrap the Datomic database"
  []
  (require '[com.miaomfood.api.service.db :as db])
  ((resolve 'db/bootstrap!) @(resolve 'com.miaomfood.api.service.db/uri)))

;; == Testing tasks ========================================
(deftask with-test
  "Add test to source paths"
  []
  (set-env! :source-paths #(clojure.set/union % (get-env :test-paths)))
  identity)

;; Include test/ in REPL sources
(replace-task!
  [r repl] (fn [& xs] (with-test) (apply r xs)))

(require '[clojure.test :refer [run-tests]])

(deftask test
  "Run project tests"
  []
  (with-test)
  (bootstrap)
  (set-env! :dependencies #(conj % '[org.clojure/tools.namespace "0.2.8"]))
  (require '[clojure.tools.namespace.find :refer [find-namespaces-in-dir]])
  (let [find-namespaces-in-dir (resolve 'clojure.tools.namespace.find/find-namespaces-in-dir)
        test-nses              (->> (get-env :test-paths)
                                    (mapcat #(find-namespaces-in-dir (clojure.java.io/file %)))
                                    distinct)]
    (doall (map require test-nses))
    (apply clojure.test/run-tests test-nses)))

;; == Build Tasks =========================================
(deftask build
  "Build my project."
  []
  (comp (aot :namespace '#{ com.miaomfood.api.server})
        (pom)
        (uber)
        (jar :main 'com.miaomfood.api.server)))

(require '[com.miaomfood.api.server :as com.miaomfood.api.server])

;; == Server Tasks =========================================
(deftask server
  "Run a web server"
  []
  (com.miaomfood.api.server/start :io.pedestal.http/join? true))
