;; Copyright 2017 Sage Han <zongshian@gmail.com> https://heysage.com
;; All right reserved

(ns com.miaomfood.api.service.qpi
  "Merely some query interfaces for datomic backend"
  (:require
   [datomic.api :as d]
   [clojure.walk :refer [prewalk]]))

(def dissoc-dbid #(if (map? %) (dissoc % :db/id) %))

(def stringify-enum
  #(if (and (map? %) (= 1 (count %)) (:db/ident %))
     (-> % :db/ident name) %))

(def expand-enum-dbid
  (fn [db col]
    (if (and (map? col) (= 1 (count col)) (:db/id col))
      (->> col :db/id (d/entity db)) col)))

(def render-entity
  #(if (instance? datomic.query.EntityMap %) (into {} %) %))

(defn restaurant-entities
  [conn]
  (let [db (d/db conn)]
    (prewalk
     (comp dissoc-dbid
           stringify-enum
           render-entity
           (partial expand-enum-dbid db))
     (d/pull db '[*]
      (first (d/q '[:find [?e ...] :where [?e :Restaurant/name _]] db))))))

(defn cuisines-entities
  [conn]
  (let [db (d/db conn)]
    (prewalk
     stringify-enum
     (d/pull-many db
      '[:MenuItem/productID
        :MenuItem/name
        :MenuItem/image
        {:MenuItem/offers
         [{:Offer/name [:db/ident]}
          :Offer/price
          {:Offer/priceCurrency [:db/ident]}
          :Offer/inventoryLevel
          :spec/duplexable]}]
      (d/q '[:find [?e ...] :in $ :where [?e :MenuItem/productID _]] db)))))

(defn user-entity
  [conn uid]
  (d/pull (d/db conn) '[:customer/id uid]))

(defn order-entity
  [db oid]
  (prewalk
   (comp stringify-enum
         render-entity
         (partial expand-enum-dbid db))
   (d/pull db
    '[:Order/orderNumber
      :Order/orderStatus
      :Order/comment
      :Order/isGift
      :Order/orderDate
      :Order/confirmedTime
      :Order/deliveredTime
      :Order/completeTime
      {:Order/OrderedItems
       [:OrderItem/orderQuantity
        {:OrderItem/productID [:MenuItem/name]}
        {:OrderItem/offers [:Offer/name :Offer/price]}]}
      {:Order/customer
       [:Customer/name
        :Customer/telephone
        :Customer/address]}
      {:Order/charge
       [:charge/id
        :charge/created
        :charge/timeExpire
        :charge/amount
        :charge/paymentMethod]}]
    [:Order/orderNumber oid])))

