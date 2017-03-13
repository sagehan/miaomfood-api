(ns com.miaomfood.api.server
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [ns-tracker.core :refer [ns-tracker]]
            [environ.core :refer [env]]
            [com.miaomfood.api.service.routes :refer [routes]]
            [com.miaomfood.api.service.db :as db]))

(defonce modified-namespaces
  (if (env :prod)
    (constantly nil)
    (ns-tracker ["src"])))

(def service
  {::http/host (or (env :host) "0.0.0.0")
   ::http/port (Integer/parseInt (or (env :port) "8080"))
   ::http/type :jetty
   ::http/join? false
   ::http/resource-path "/public"
   ::http/allowed-origins ["http://miaomfood.com:80", "http://miaomfood.com:8000"]
   ::http/routes (fn []
                   (doseq [ns-sym (modified-namespaces)]
                     (require ns-sym :reload))
                   routes)})

(defonce server nil)

(defn start [& args]
  (db/bootstrap! db/uri)
  (let [service-overrides (apply hash-map args)]
    (alter-var-root #'server (fn [_] (http/create-server (merge service
                                                                service-overrides))))
    (http/start server)))

(defn stop []
  (http/stop server)
  (alter-var-root #'server (constantly nil)))

(defn restart []
  (stop)
  (start))

(defn -main [& args]
  (start ::http/join? true
         ::http/routes routes))
