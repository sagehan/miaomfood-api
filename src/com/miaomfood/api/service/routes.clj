(ns com.miaomfood.api.service.routes
  "Main restful api logic for miaomfood.com"
  (:require
   [ring.util.response :as ring-resp]
   [com.miaomfood.api.service.db :as db :refer [datomic-intc]]
   [com.miaomfood.api.service.qpi :as q]
   [datomic.api :as d]
   [clojure.walk :as walk]
   [cognitect.transit :as t]
   [clojure.data.json :as json]
   [io.pedestal.http :as http :refer [html-body]]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :refer [interceptor]]
   [io.pedestal.interceptor.helpers :refer [defhandler]]
   [io.pedestal.http.body-params :refer [body-params]]
   [io.pedestal.http.content-negotiation :as conneg])
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
    "text/plain"       body
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
;; defhandler and its macro brothers will break AOT, will be removed on next commit
;; see https://github.com/pedestal/pedestal/issues/308
(defhandler greeting [req] (ring-resp/response "Welcome to miaomfood!"))

(def get-cuisines
  (interceptor
   {:name ::list-cuisines
    :enter
    (fn [{{:keys [conn]} :request :as ctx}]
      (assoc ctx :response (ring-resp/response (q/cuisines-entities conn))))}))

(def view-order
  (interceptor
   {:name ::view-order
    :leave
    (fn [{{db :db :as req} :request :as ctx}]
      (if-let [oid (get-in req [:path-params :order-id])]
        (if-let [order-entity  (q/order-entity db oid)]
          (if-let [url (get req :resource-url)]
            (assoc ctx :response (ring-resp/created url order-entity))
            (assoc ctx :response (ring-resp/response order-entity)))
          ctx)
        ctx))}))

(def create-order
  (interceptor
   {:name ::create-order
    :enter
    (fn [{{db :db :as req} :request :as ctx}]
      (let [get-eid (comp #(update % :orderItem/cid  (fn [cid] (d/entid db [:cuisine/id cid])))
                          #(update % :orderItem/spec (fn [spec] (d/entid db [:db/ident (keyword "spec.name" spec)]))))
            transit (walk/postwalk-replace
                     {"cid" :orderItem/cid
                      "spec" :orderItem/spec
                      "qty"  :orderItem/qty}
                     (:transit-params req))
            dbid    (d/tempid :db.part/user)
            slug    (str (d/squuid)) ; just for demo
            url     "https://miaomfood.com"
            datoms  (-> transit
                        (assoc :db/id dbid)
                        (assoc :order/tokenSlug slug)
                        (update :order/items (partial mapv get-eid))
                        vector)]
        (-> ctx
            (assoc :tx-data datoms)
            (assoc :response (ring-resp/response datoms))
            (assoc-in [:request :path-params :order-id] slug)
            (assoc-in [:request :resource-url] url))))}))

(def routes
  (route/expand-routes
   #{["/"         :get  [html-body greeting]]
     ["/cuisines" :get  (conj common-intc datomic-intc get-cuisines)
      :route-name :get-cuisines]
     ["/orders"   :post (conj common-intc view-order datomic-intc create-order)
      :route-name :place-order]
     ["/orders/:order-id" :get (conj common-intc datomic-intc view-order)
      :route-name :view-order]
     }))
