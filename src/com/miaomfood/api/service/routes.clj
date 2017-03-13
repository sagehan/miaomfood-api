(ns com.miaomfood.api.service.routes
  (:require [io.pedestal.http.route :as route]
            [io.pedestal.http :as http]
            [io.pedestal.http.body-params :as body-params]
            [ring.util.response :as ring-resp]
            [datomic.api :as d]
            [com.miaomfood.api.service.db :as db]))

(def common-interceptors [(body-params/body-params) http/html-body])

(defn greeting
  [request]
  (ring-resp/response "Hello, miaomfood!"))

(def routes
  (route/expand-routes
   #{["/" :get (conj common-interceptors `greeting)]}))
