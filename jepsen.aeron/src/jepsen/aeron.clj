(ns jepsen.aeron
  (:require [jepsen.aeron.client :as client]
            [jepsen.aeron.db :as db]
            [jepsen.control :as c]
            [jepsen.aeron.model :as auction-model]
            [jepsen.aeron.nemesis :as aeron-nemesis]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.cli :as cli]
            [jepsen.independent :as independent]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.tests :as tests]
            [jepsen.net :as net]))

(defn aeron-test [opts]
  (merge tests/noop-test
         opts
         {:name "aeron"
          :nodes ["node0.hilkiu2-255833.cs598fts-pg0.utah.cloudlab.us"]
          :pure-generators true
          :db (db/db "v1.47.4")
          :leave-db-running? false
          :concurrency 100
          :client (client/->Client nil)
          ;; :nemesis (nemesis/partition-random-halves) ;; Used to soley test network partitions
          ;; :nemesis (aeron-nemesis/slowing (nemesis/partition-random-halves) "eno33" 0.5) ;; Used to test network partitions under a 0.5 sec delay
          ;; :nemesis (aeron-nemesis/startstop 1)
          :generator  (->> (independent/concurrent-generator
                             10 ;; keep 10-20, defines how many concurrent threads per key
                             (range 0 10)
                             (fn [item-id]
                               (->> (gen/mix [(repeat 8 (client/bid item-id)) (repeat 2 (client/item item-id))])
                                    (gen/stagger 1/50)
                                    (gen/limit 100))))
                          ;;  (gen/nemesis
                          ;;    (cycle [(gen/sleep 5)
                          ;;            {:type :info, :f :start}
                          ;;            (gen/sleep 5)
                          ;;            {:type :info, :f :stop}]))
                           (gen/time-limit (:time-limit opts)))                              
          :checker (checker/compose
                    { :perf  (checker/perf)
                      :indep (independent/checker
                                (checker/compose
                                  { :linear   (checker/linearizable { :model (auction-model/auction-model)
                                                                      :algorithm :linear})
                                    :timeline (timeline/html)}))})
          }))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn aeron-test})
                   (cli/serve-cmd))
            args))
