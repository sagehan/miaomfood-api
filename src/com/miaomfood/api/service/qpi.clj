(ns com.miaomfood.api.service.qpi
  "Merely some query interfaces for datomic backend"
  (:require
    [datomic.api :as d]
    [clojure.algo.generic.functor :refer [fmap]]))

;; Copyright (c) https://github.com/danielneal/  ==== start =========================

(defn- load-entity
  "Loads an entity and its attributes. Keep in the db/id
  and replace references with ids (for use by DataScript)"
  [db entity]
  (as->
   (d/entity db entity) e
   (d/touch e)
   (into {:db/id (:db/id e)} e) ; needs to be a hash-map, not an entity map
   (fmap (fn [v]
           (cond (set? v) (mapv #(if (instance? datomic.query.EntityMap %) (:db/id %) %) v)
                 (instance? datomic.query.EntityMap v) (:db/id v)
                 :else v)) e)))

;; Copyright (c) https://github.com/danielneal/  ==== end =========================

(defn- anonymous-visible-ids?
  "Get all IDs of entities that could be accessible to anonymous customers"
  [db]
  (d/q '[:find ?e
         :in $ %
         :where (visible? ?e)]
       db
       '[[(visible? ?wt) [?wt :website/title]]
         [(visible? ?wn) [?wn :website/notices]]
         [(visible? ?rn) [?rn :restaurant/name]]
         [(visible? ?nc) [?nc :notice/content]]
         [(visible? ?gm) [?gm :group/menus]]
         [(visible? ?mc) [?mc :menu/categories]]
         [(visible? ?cc) [?cc :category/cuisines]]
         [(visible? ?cs) [?cs :cuisine/species]]
         [(visible? ?sn) [?sn :spec/name]]
         ]))

(defn anonymous-visible-entities
  [conn]
  (let [db (d/db conn)]
    (map (comp (partial load-entity db) first)
         (anonymous-visible-ids? db))))

(defn cuisine-entity
  [conn cid]
  (d/pull
   (d/db conn)
   '[:cuisine/id
     :cuisine/name
     :cuisine/depict
     {:cuisine/species
      [{:spec/name [:db/ident]}
       {:currency/Abbr [:db/ident]}
       :spec/duplexable
       :spec/price
       :spec/inventory]}]
   [:cuisine/id cid]))

(defn cuisines-entities
  [conn]
  (let [db   (d/db conn)
        cids (d/q '[:find [?cid ...] :in $ :where [_ :cuisine/id ?cid]] db)]
    (into [] (map (partial cuisine-entity conn) cids))))

(defn user-entities
  [conn uid]
  (d/pull (d/db conn) '[:customer/id] uid))

(defn submit-order!
  [conn order-rawdata]
  (let [filter #(-> %
                    (update :db/id (fn [n] (d/tempid :db.part/user (- n))) )
                    (update :cuisineItem/ID (fn [ID] (d/entid (d/db conn) [:cuisine/id ID])))
                    (select-keys [:db/id
                                  :cuisineItem/ID
                                  :cuisineItem/spec
                                  :cuisineItem/qty]) )
        items  (vec (map filter (:cart/cuisineItems order-rawdata)))
        token  (str (d/squuid)) ; just for demo
        id     (d/tempid :db.part/user)
        tx     [(-> order-rawdata
                    (select-keys [:order/customerName
                                  :order/customerPhone
                                  :order/streetAddress
                                  :order/comment
                                  :order/schedule-day
                                  :order/schedule-time ])
                    (assoc :db/id id
                           :order/cuisineItems items
                           :order/tokenSlug token))] ]
    @(d/transact conn tx) ))
