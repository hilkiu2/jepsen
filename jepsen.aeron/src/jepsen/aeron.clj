(ns jepsen.aeron
  (:require [jepsen.aeron.client :as client]
            [jepsen.aeron.db :as db]
            [jepsen.control :as c]
            [jepsen.aeron.model :as auction-model]
            [jepsen.aeron.nemesis :as aeron-nemesis]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.cli :as cli]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.tests :as tests]
            [jepsen.net :as net]))

(defn aeron-test [opts]
  (merge tests/noop-test
         opts
         {:name "aeron"
          :nodes ["node1.aeron-jepsen.cs598fts.emulab.net"]
          :pure-generators true
          :db (db/db "v1.47.4")
          :leave-db-running? false
          :concurrency 10
          :client (client/->Client nil)
          ;; :nemesis (nemesis/partition-random-halves) ;; Used to soley test network partitions
          ;; :nemesis (aeron-nemesis/slow-wrapper (nemesis/partition-random-halves) "enp6s7" 0.5) ;; Used to test network partitions under a 0.5 sec delay
          ;; :nemesis (aeron-nemesis/startstop 1)
          :generator (gen/phases
                        (->>  (gen/mix [ (repeat 8 client/bid) (repeat 2 client/item) ])
                              (gen/clients)
                              ;; (gen/nemesis
                              ;;   (->> (cycle [{:type :info, :f :start}
                              ;;               (gen/sleep 5)
                              ;;               {:type :info, :f :stop}
                              ;;               (gen/sleep 5)])
                              ;;       ;; (take 20)
                              ;;       ))
                              (gen/stagger 1/50)
                              (gen/time-limit (:time-limit opts))))
          :checker (checker/compose 
                    { :linear (checker/linearizable {:model (auction-model/auction-model)})
                      :perf      (checker/perf)
                      :timeline  (timeline/html) })
          }))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn aeron-test})
                   (cli/serve-cmd))
            args))
