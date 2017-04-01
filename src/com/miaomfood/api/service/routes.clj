(ns com.miaomfood.api.service.routes
  "Main restful api logic for miaomfood.com"
  (:require
   [ring.util.response :as ring-resp]
   [com.miaomfood.api.service.db :as db]
   [com.miaomfood.api.service.qpi :as q]
   ;; [datomic.api :as d]
   [cognitect.transit :as t]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :refer [interceptor]]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.content-negotiation :as conneg])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(def json-packaged
  (atom #(let [out (ByteArrayOutputStream. 4096)]
           (t/write (t/writer out :json-verbose) %)
           (.toString out "UTF-8"))))

(def supported-types
  ["text/plain"
   "text/html"
   "application/json"
   "application/transit+json"])

(defn transform-content
  [body content-type]
  (case content-type
    "text/html"        body
    "text/plain"       body
    "application/edn"  (pr-str body)
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

(def common-interceptors
  [(body-params/body-params)
   http/html-body])

(def api-interceptors
  [coerce-body
   db/db-interceptor
   (body-params/body-params)
   (conneg/negotiate-content supported-types)])

(defn greeting [req] (ring-resp/response "Welcome to miaomfood!"))

(defn fetch-cuisines
  [req]
  (ring-resp/response (q/cuisines-entities (:conn req))))

(def routes
  (route/expand-routes
   #{["/" :get (conj common-interceptors `greeting)]
     ["/cuisines" :get (conj api-interceptors 'fetch-cuisines)]}))
