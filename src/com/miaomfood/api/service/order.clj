;; Copyright 2017 Sage Han <zongshian@gmail.com> https://heysage.com
;; All right reserved

(ns com.miaomfood.api.service.order
  (:require
   [datomic.api :as d]
   [clojure.string :as s]
   [clojure.walk :as walk]
   [environ.core :refer [env]]
   [clojure.data.json :as json]
   [org.httpkit.client :as http]
   [ring.util.response :as ring-resp]
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :refer [interceptor]]
   [com.miaomfood.api.service.qpi :as q]
   [clojure.string :as str]))

(defonce app-id (get env :app-id ""))
(defonce api-key (get env :api-key ""))
(defonce charge-api "https://api.pingxx.com/v1/charges")

(def view-order
  (interceptor
   {:name ::view-order
    :leave
    (fn [{{db :db :as req} :request :as ctx}]
      (if-let [oid (get-in req [:path-params :order-id])]
        (if-let [entity  (:tx-charge ctx)]
          (if-let [url (get req :resource-url)]
            (assoc ctx :response (ring-resp/created url entity))
            (assoc ctx :response (ring-resp/response entity)))
          ctx)
        ctx))}))

(def create-order
  (interceptor
   {:name ::create-order
    :enter
    (fn [{{db :db, :as req} :request :as ctx}]
      (let [get-eid (comp #(update % :OrderItem/productID
                                   (fn [pid] (d/entid db [:MenuItem/productID pid])))
                          #(update-in % [:OrderItem/offers :Offer/name]
                                      (fn [spec] (keyword "spec.name" spec))))
            transit (walk/postwalk-replace
                     {:Order/CartItems :Order/OrderedItems
                      "productID" :OrderItem/productID
                      "qty"  :OrderItem/orderQuantity
                      "offers" :OrderItem/offers
                      "price" :Offer/price
                      "name" :Offer/name}
                     (:transit-params req))
            dbid (d/tempid :db.part/user)
            slug    (str (d/squuid)) ; just for demo
            url     (route/url-for :view-order :params {:order-id slug})
            datoms  (-> transit
                        (assoc  :db/id dbid
                                :Order/orderNumber slug
                                :Order/orderStatus :OrderStatus/OrderProcessing
                                :Order/client_ip (:remote-addr req))
                        (update :Order/OrderedItems (partial mapv get-eid))
                        vector)]
        (-> ctx
            (assoc :tx-data datoms)
            (assoc-in [:request :path-params :order-id] slug)
            (assoc-in [:request :resource-url] url))))}))

(def make-charge
  (interceptor
   {:name ::make-charge
    :enter
    (fn [ctx]
      (if-let [{{chn :charge/paymentMethod, :as tx} :tx-data} ctx]
        (let [opts {:insecure? true
                    :basic-auth [api-key ""]
                    :header {"Authorization" (str "Bearer " api-key)}
                    :form-params
                    {:order_no
                     (s/replace (get-in tx [0 :Order/orderNumber]) #"-" {"-" ""})
                     (keyword "app[id]") app-id
                     :channel   "alipay_wap"
                     :extra     {:success_url "https://miaomfood.com/"}
                     :amount    12156
                     :client_ip "127.0.0.1"
                     :currency  "cny"
                     :subject   "买买买"
                     :body      "好好好"}}
              {:keys [status headers body error] :as resp} @(http/post charge-api opts)]
          (if error
            (println "Failed, exception is " error)
            (assoc ctx :tx-charge (json/read-str body))))
        ctx))}))
