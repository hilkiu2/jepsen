(ns jepsen.aeron
  (:require [jepsen.aeron.client :as client]
            [jepsen.aeron.db :as db]
            [jepsen.aeron.checker :as auction-checker]
            [jepsen.aeron.model :as auction-model]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.cli :as cli]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.tests :as tests]))

(defn aeron-test [opts]
  (merge tests/noop-test
         opts
         {:name "aeron"
          :nodes ["node1.aeron-jepsen.cs598fts.emulab.net"]
          :pure-generators true
          :db (db/db "v1.47.4")
          :leave-db-running? false
          :client (client/->Client nil)
          :generator (gen/phases
                      (->> (gen/mix [
                              (fn [_ _]
                                {:type :invoke
                                :f    :bid
                                :value {:id    (+ 1 (rand-int 10))       ; id ∈ [1, 10]
                                        :price (+ 100 (rand-int 200))}})

                              (fn [_ _]
                                {:type :invoke
                                :f    :status
                                :value nil})])
                          (gen/clients)
                          (gen/stagger 0.3)
                          (gen/time-limit 15)))
          :checker (checker/compose
                     {
                      ;; :auction   (auction-checker/auction-checker)
                      :linear (checker/linearizable {:model (auction-model/auction-model)})
                      :perf      (checker/perf)
                      :timeline  (timeline/html)
                      })
          }))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn aeron-test})
                   (cli/serve-cmd))
            args))
