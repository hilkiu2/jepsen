(ns jepsen.aeron.model
  (:require [knossos.model :as model])
  (:import (knossos.model Model)))

(defrecord KVModel [state]
  Model
  (step [this op]
    (let [f (:f op)
          val (:value op)]
      (case f
        :put
          (let [k (:key val)]
            (KVModel. (assoc state k (:value val))))

        :get
          (let [k (:key val)
              expected (get state k -1)
              observed (:value val)]
          (if (= expected observed)
            this
            (model/inconsistent
              (str "Expected '" expected "' for key " k ", got '" observed "'"))))
          
        :delete
          (let [k (:key val)]
            (KVModel. (dissoc state k)))

        :cas
          (let [k (:key val)
              expected (:expected val)
              new-val (:new val)
              succeeded (:succeeded val)
              current (get state k)]
          (cond
            ;; if CAS succeeded, but values didn't match: invalid
            (and succeeded (not= current expected))
            (model/inconsistent (str "CAS claimed success but expected value " expected
                               " didn't match actual " current))

            ;; if CAS failed, but values actually matched: invalid
            (and (not succeeded) (= current expected))
            (model/inconsistent (str "CAS claimed failure but expected " expected
                               " matched actual " current))

            ;; CAS succeeded and matched: apply update
            succeeded
            (KVModel. (assoc state k new-val))

            ;; CAS failed and didn't match: no change
            :else this))

        this))))

(defn kv-model []
  (->KVModel {}))
