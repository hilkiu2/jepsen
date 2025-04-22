(ns jepsen.aeron.checker
  (:require [jepsen.checker :as checker]
            [clojure.pprint :refer [pprint]]))

(defn auction-checker []
  (reify checker/Checker
    (check [test model history opts]
      (let [winning-price (atom 0)
            errors (atom [])]
        (println "🔍 Starting auction checker")
        (doseq [op history]
          (when (= :ok (:type op))
            (println "📦 Processing op:" op)
            (case (:f op)
              :status
              (let [{:keys [id price]} (:value op)]
                (println "📈 status check - price:" price ", winning:" @winning-price)
                (when (< price @winning-price)
                  (println "❗ Non-monotonic winning price detected!")
                  (swap! errors conj {:type :non-monotonic
                                      :prev @winning-price
                                      :new price
                                      :op op}))
                (when (> price @winning-price)
                  (println "✅ Updating winning price to:" price)
                  (reset! winning-price price)))

              :bid
              (let [{:keys [id price succeeded]} (:value op)]
                (println "🤑 bid check - price:" price ", succeeded:" succeeded ", winning:" @winning-price)
                (if succeeded
                  (when (<= price @winning-price)
                    (println "❌ Accepted too-low bid!")
                    (swap! errors conj {:type :bad-accept
                                        :expected-min (inc @winning-price)
                                        :actual price
                                        :op op}))
                  (when (> price @winning-price)
                    (println "❌ Rejected high-enough bid!")
                    (swap! errors conj {:type :bad-reject
                                        :expected-max @winning-price
                                        :actual price
                                        :op op})))))))

        (let [result {:valid? (empty? @errors)
                      :errors @errors
                      :final-winning-price @winning-price}]
          (println "✅ Final checker result:")
          (pprint result)
          result)))))

;; (ns jepsen.aeron.checker
;;   (:require [jepsen.checker :as checker]))

;; (defn auction-checker []
;;   (reify checker/Checker
;;     (check [test model history opts]
;;       (let [winning-price (atom 0)
;;             errors (atom [])]
;;         (doseq [op history]
;;           (when (= :ok (:type op))
;;             (case (:f op)
;;               :status
;;               (let [{:keys [id price]} (:value op)]
;;                 (when (< price @winning-price)
;;                   (swap! errors conj {:type :non-monotonic
;;                                       :prev @winning-price
;;                                       :new price
;;                                       :op op}))
;;                 (when (> price @winning-price)
;;                   (reset! winning-price price)))

;;               :bid
;;               (let [{:keys [id price succeeded]} (:value op)]
;;                 (if succeeded
;;                   (when (<= price @winning-price)
;;                     (swap! errors conj {:type :bad-accept
;;                                         :expected-min (inc @winning-price)
;;                                         :actual price
;;                                         :op op}))
;;                   (when (> price @winning-price)
;;                     (swap! errors conj {:type :bad-reject
;;                                         :expected-max @winning-price
;;                                         :actual price
;;                                         :op op}))))))

;;         {:valid? (empty? @errors)
;;          :errors @errors
;;          :final-winning-price @winning-price})))))
