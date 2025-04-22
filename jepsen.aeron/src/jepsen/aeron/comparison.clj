(defrecord AuctionModel [winner price]
  Object
  (toString [this] (str "v" winner ": " price))

  Model
  (step [model op]
    (let [[op-winner op-price] (:price op)
          winner' (inc winner)]
      (condp = (:f op)
        :write (if (and (not (nil? op-winner))
                        (not= winner' op-winner))
                 (model/inconsistent
                   (str "can't go from winner " winner " to " op-winner))
                 (AuctionModel. winner' op-price))

        :cas   (let [[v v'] op-price]
                 (cond (and (not (nil? op-winner))
                            (not= winner' op-winner))
                       (model/inconsistent
                         (str "can't go from winner " winner " to "
                              op-winner))

                       (not= price v)
                       (model/inconsistent (str "can't CAS " price " from " v
                                                " to " v'))

                       true
                       (AuctionModel. winner' v')))

        :read (cond (and (not (nil? op-winner))
                         (not= winner op-winner))
                    (model/inconsistent
                      (str "can't read winner " op-winner " from winner "
                           winner))

                    (and (not (nil? op-price))
                         (not= price op-price))
                    (model/inconsistent
                      (str "can't read " op-price " from register " price))

                    true
                    model)))))