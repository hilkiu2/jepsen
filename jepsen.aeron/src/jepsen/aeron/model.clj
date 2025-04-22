(ns jepsen.aeron.model
  (:require [knossos.model :as model])
  (:import (knossos.model Model)))

(defrecord AuctionModel [winner price]
  Model
  (step [this op]
    (let [f (:f op)
          val (:value op)]
      (case f
        :bid
        (if (:succeeded val)
          (if (> (:price val) price)
            (->AuctionModel (:id val) (:price val))
            (model/inconsistent "Accepted bid not higher than winning price"))
          (if (<= (:price val) price)
            this
            (model/inconsistent "Rejected bid higher than current price")))

        :status
        (if (= (:price val) price)
          this
          (model/inconsistent "Status reported stale winning price"))

        this))))

(defn auction-model []
  (->AuctionModel -1 0))
