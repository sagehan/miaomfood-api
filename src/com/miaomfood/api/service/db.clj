(ns com.miaomfood.api.service.db
  "Datomic bootstrap and Datomic + Pedestal interceptor"
  (:require [datomic.api :as d]
            [io.pedestal.interceptor :refer [interceptor]]
            [clojure.java.io :as io]
            [io.rkn.conformity :as c]
            [environ.core :refer [env]]))

(defonce uri (get env :datomic-uri (str "datomic:mem://" (d/squuid))))

(defn bootstrap!
  "Bootstrap schema into the database."
  [uri]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    (doseq [rsc ["schema.edn", "data.edn"]]
      (let [norms (c/read-resource rsc)]
        (c/ensure-conforms conn norms)))))

(def datomic-intc
   "Provide a Datomic conn and db in all incoming requests"
   (interceptor
     {:name ::insert-datomic
      :enter (fn [ctx]
               (let [conn (d/connect uri)]
                 (-> ctx
                     (assoc-in [:request :conn] conn)
                     (assoc-in [:request :db] (d/db conn)))))
      :leave (fn [{{:keys [conn]} :request :as ctx}]
               (if-let [tx (:tx-data ctx)]
                 (do (assoc-in ctx [:request :db] (:db-after @(d/transact conn tx))))
                 ctx))}))
