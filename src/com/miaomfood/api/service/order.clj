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

(defonce open-id (get env :open-id "gualala"))
(defonce app-id (get env :app-id ""))
(defonce api-key (get env :api-key ""))
(defonce charge-api "https://api.pingxx.com/v1/charges")

(def view-order
  (interceptor
   {:name ::view-order
    :leave
    (fn [{{db :db :as req} :request :as ctx}]
      (if-let [oid (get-in req [:path-params :order-id])]
        (if-let [[entity] (:tx-data ctx)]
          (if-let [url (get req :resource-url)]
            (if-let [chg (:tx-charge-raw ctx)]
              (assoc ctx :response (ring-resp/created url (assoc entity :charge_raw chg)))
              (assoc ctx :response (ring-resp/created url entity)))
            (assoc ctx :response (ring-resp/response entity)))
          ctx)
        ctx))}))

(def review-order
  (interceptor
   {:name ::review-order
    :enter
    (fn [{{db :db conn :conn :as req} :request :as ctx}]
      (if-let [oid (get-in req [:path-params :order-id])]
        (if-let [eid (d/entid db [:Order/orderNumber oid])]
          (let [order (q/order-entity db oid)]
            (assoc ctx :response (ring-resp/response order)))
          (assoc ctx :response (ring-resp/not-found "此订单不存在！")))
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
            dbid    (d/tempid :db.part/user)
            slug    (str (/ (d/squuid-time-millis (d/squuid)) 1000)) ; just for demo
            gratuity 3 ; just hardcode gratuity fee here for demo
            total   (+ (->> (map
                             #(* (:OrderItem/orderQuantity %) (get-in % [:OrderItem/offers :Offer/price]))
                             (:Order/OrderedItems transit))
                            (reduce +))
                       gratuity)
            url     (route/url-for :view-order :params {:order-id slug})
            datoms  (-> transit
                        (assoc  :db/id dbid
                                :Order/orderNumber slug
                                :Order/amount total
                                :Order/orderStatus :OrderStatus/OrderProcessing
                                :Order/client_ip (:remote-addr req))
                        (update :Order/OrderedItems (partial mapv get-eid))
                        vector)]
        (-> ctx
            (assoc :tx-data datoms)
            (assoc-in [:request :path-params :order-id] slug)
            (assoc-in [:request :resource-url] url))))
    :leave
    (fn [ctx]
      (if-let [chg (:tx-charge ctx)]
        (update-in ctx [:tx-data 0 :Order/charge] merge chg)
        ctx))}))

(def make-charge
  (interceptor
   {:name ::make-charge
    :enter
    (fn [ctx]
      (if-let [{ [{{chn :charge/paymentMethod} :Order/charge :as tx}] :tx-data} ctx]
        (if-not (= "Cash" (name chn))
          (let [cb_url      "https://miaomfood.com/orders/"
                assoc-extra #(case (name chn)
                               "wx_pub" (assoc-in % [:form-params :extra :open_id] open-id)
                               "alipay_wap" (assoc-in % [:form-params :extra :success_url] cb_url))
                opts (-> {:insecure? true
                          :basic-auth [api-key ""]
                          :header {"Authorization" (str "Bearer " api-key)}
                          :form-params
                          {:order_no  (:Order/orderNumber tx)
                           (keyword "app[id]") app-id
                           :channel   (name chn)
                           :amount    (* 100 (:Order/amount tx))
                           :client_ip "127.0.0.1"
                           :currency  "cny"
                           :subject   "买买买"
                           :body      "好好好"}} assoc-extra)
                {:keys [status headers body error] :as resp} @(http/post charge-api opts)]
            (if error
              (println "Failed, exception is " error)
              (let [chg-json (json/read-str body)]
                (assoc ctx
                       :tx-charge-raw chg-json
                       :tx-charge
                       {:charge/id (get chg-json "id")
                        :charge/amount (get chg-json "amount")
                        :charge/created (get chg-json "created")
                        :charge/timeExpire (get chg-json "time_expire")}))))
          ctx)
        ctx))}))

(def charge-order
  (interceptor
   {:name ::charge-order
    :enter
    (fn [{ {db :db, :as req} :request :as ctx }]
      (if-let [event (:json-params req)]
        (if (= "charge.succeeded" (:type event))
          (let [data (get-in event [:data :object])]
            (assoc ctx
                   :tx-data
                   [{:db/id [:Order/orderNumber (:order_no data)]
                     :Order/charge
                     {:db/id [:charge/id (:id data)]
                      :charge/transaction_no (:transaction_no data)
                      :charge/amount (:amount data)
                      :charge/timePaid (:time_paid data)
                      :charge/paymentMethod (:channel data)}}]
                   :response
                   (ring-resp/response "OK!")))
          (assoc ctx :response (ring-resp/status (ring-resp/response "Wrong Type") 500)))
        ctx
        ))}))
