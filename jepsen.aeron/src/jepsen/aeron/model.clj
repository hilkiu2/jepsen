(ns jepsen.aeron.model
  (:require [knossos.model :as model])
  (:import (knossos.model Model)))

(defrecord AuctionModel [items]
  Model
  (step [this op]
    (let [f (:f op)
          val (:value op)]
      (case f
        :bid
        (let [item-id (:itemId val)
              winning-price (:price val)
              success (:success val)
              current-price (get items item-id 0)]
          (if success
            (if (>= winning-price current-price)
              ;; Update model to reflect new winning price
              (->AuctionModel (assoc items item-id winning-price))
              ;; Server claims success but price went backwards??
              (model/inconsistent (str "Accepted bid lowered price for item " item-id
                                       ": old price " current-price
                                       ", reported new winning price " winning-price)))
            ;; Bid failed: should have failed because price too low
            (if (<= winning-price current-price)
              this
              (model/inconsistent (str "Rejected bid incorrectly for item " item-id
                                       ": winning price " current-price
                                       ", reported price " winning-price)))))

        :item
        (let [item-id (:itemId val)
              winning-price (:price val)
              current-price (get items item-id 0)]
          (if (= winning-price current-price)
            this
            (model/inconsistent (str "Item status stale: reported " winning-price
                                     ", expected " current-price))))

        this))))

(defn auction-model []
  (->AuctionModel {}))
