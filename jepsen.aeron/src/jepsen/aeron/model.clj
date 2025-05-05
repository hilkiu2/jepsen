(ns jepsen.aeron.model
  (:require [knossos.model :as model]
            [clojure.tools.logging :refer :all])
  (:import (knossos.model Model)))

(defrecord AuctionModel [items]
  Model
  (step [this op]
    (let [f (:f op)
          val (:value op)]
      (case f
        :bid
        (let [item-id (:id val)
              given-price (:price val)
              stored-price (get items item-id 0)]
          (if (> given-price stored-price)
            (->AuctionModel (assoc items item-id given-price))
            this))
        
        :item
        (let [item-id (:id val)
              winning-price (:price val)
              current-price (get items item-id 0)]
          (if (= winning-price current-price)
            this
            (model/inconsistent (str "Item status stale: cluster winning price " winning-price
                                     ", tracked winning price " current-price))))

        this))))

(defn auction-model []
  (->AuctionModel {}))
