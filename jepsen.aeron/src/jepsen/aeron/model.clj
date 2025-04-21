(ns jepsen.aeron.model
  (:require [knossos.model :as model])
  (:import (knossos.model Model)))

(defrecord AuctionModel [auctions]
  Model
  (step [this op]
    (let [auction-id (case (:f op)
                       :bid (first (:value op))
                       :close (:value op)
                       :create (:value op))]
      (cond
        ;; Only consider successful ops
        (= :ok (:type op))
        (case (:f op)
          :create
          (if (some #(= (:value %) auction-id) auctions)
            (model/inconsistent (str "Duplicate create for auction: " auction-id))
            (assoc this :auctions (conj auctions op)))

          :bid
          (let [created? (some #(and (= (:f %) :create) (= (:value %) auction-id)) auctions)
                closed?  (some #(and (= (:f %) :close) (= (:value %) auction-id)) auctions)]
            (cond
              (not created?) (model/inconsistent (str "Bid on missing auction: " auction-id))
              closed?        (model/inconsistent (str "Bid on closed auction: " auction-id))
              :else          (assoc this :auctions (conj auctions op))))

          :close
          (let [created? (some #(and (= (:f %) :create) (= (:value %) auction-id)) auctions)
                closed?  (some #(and (= (:f %) :close) (= (:value %) auction-id)) auctions)]
            (cond
              (not created?)  (model/inconsistent (str "Close on missing auction: " auction-id))
              closed?         (model/inconsistent (str "Duplicate close for auction: " auction-id))
              :else           (assoc this :auctions (conj auctions op))))

          this)

        ;; Ignore non-:ok ops (e.g., :fail, :info)
        :else this))))
