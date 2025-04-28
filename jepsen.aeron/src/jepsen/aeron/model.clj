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
              stored-price (get items item-id 0)
              given-price (:price val)
              succeeded (:succeeded val)]
                (if succeeded
                  (do
                    (if (> given-price stored-price)
                      (->AuctionModel (assoc items item-id given-price))
                      (model/inconsistent (str "Accepted bid lowered price for item " item-id
                                              ": old price " stored-price
                                              ", reported new winning price " given-price))))
                  (do
                    (if (<= given-price stored-price)
                      this
                      (model/inconsistent (str "Rejected bid incorrectly for item " item-id
                                              ": winning price " stored-price
                                              ", reported price " given-price))))))
        
        :item
        (let [item-id (:id val)
              winning-price (:price val)
              current-price (get items item-id 0)]
          (if (= winning-price current-price)
            this
            (model/inconsistent (str "Item status stale: reported " winning-price
                                     ", expected " current-price))))

        this))))

(defn auction-model []
  (->AuctionModel {}))
