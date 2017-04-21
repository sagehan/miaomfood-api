;; Copyright 2017 Sage Han <zongshian@gmail.com> https://heysage.com
;; All right reserved

(ns com.miaomfood.api.service.routes
  "Main restful api logic for miaomfood.com"
  (:require
   [cognitect.transit :as t]
   [clojure.data.json :as json]
   [ring.util.response :as ring-resp]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :refer [interceptor]]
   [io.pedestal.http :refer [html-body]]
   [io.pedestal.http.body-params :refer [body-params]]
   [io.pedestal.http.content-negotiation :as conneg]
   [com.miaomfood.api.service.qpi :as q]
   [com.miaomfood.api.service.db :refer [datomic-intc]]
   [com.miaomfood.api.service.order :refer [create-order make-charge view-order]])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

;;
;; Helpler Functions & Interceptors
;;
(def json-packaged
  (atom #(let [out (ByteArrayOutputStream. 4096)]
           (t/write (t/writer out :json) %)
           (.toString out))))

(def supported-types
  ["text/plain"
   "text/html"
   "application/json"
   "application/transit+json"])

(defn transform-content
  [body content-type]
  (case content-type
    "text/html"        body
    "text/plain"       (json/write-str body) ;; here use json as body for test
    "application/edn"  (pr-str body)
    "application/json" (json/write-str body)
    "application/transit+json" (@json-packaged body)))

(defn coerce-to
  [rsp content-type]
  (-> rsp
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  "Return a different body format depending on the accepted content type"
  (interceptor
   {:name ::coerce-body
    :leave
    (fn [ctx]
      (let [accepted-type (get-in ctx [:request :accept :field] "text/plain")]
        (cond-> ctx
          (nil? (get-in ctx [:response :body :headers "Content-Type"]))
          (update-in [:response] coerce-to accepted-type))))}))

(def content-neg-intc (conneg/negotiate-content supported-types))
(def common-intc
  [coerce-body
   (body-params)
   content-neg-intc])

;;
;; API special Interceptors & Handlers
;;
(def get-all
  (interceptor
   {:name ::get-all
    :enter
    (fn [{{:keys [conn]} :request :as ctx}]
      (assoc ctx :response (ring-resp/response (q/restaurant-entities conn))))}))

(def get-cuisines
  (interceptor
   {:name ::list-cuisines
    :enter
    (fn [{{:keys [conn]} :request :as ctx}]
      (assoc ctx :response (ring-resp/response (q/cuisines-entities conn))))}))

;;
;; Main route interceptor
;;
(def routes
  (route/expand-routes
   #{["/api" :get (conj common-intc datomic-intc get-all) :route-name :about]
     ["/api/v1/cuisines" :get  (conj common-intc datomic-intc get-cuisines)
      :route-name :get-cuisines]
     ["/api/v1/orders"   :post (conj common-intc view-order datomic-intc create-order make-charge)
      :route-name :place-order]
     ["/api/v1/orders/:order-id" :get (conj common-intc datomic-intc view-order)
      :route-name :view-order]
     }))
