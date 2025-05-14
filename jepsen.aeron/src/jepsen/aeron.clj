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
  (let [hostname "node0.hilkiu2-256046.cs598fts.emulab.net"]
    (merge tests/noop-test
         opts
         {:name "aeron"
          :nodes [hostname]
          :ssh {:username "hilkiu2"
                :strict-host-key-checking false
                :private-key-path "/users/hilkiu2/.ssh/id_rsa_jepsen"}
          :pure-generators true
          :db (db/db "v1.47.4" hostname)
          :leave-db-running? false
          :concurrency 10 ;; default setting of 10 clients per cluster
          :client (client/->Client nil)
          ;; :nemesis (aeron-nemesis/partition-random-halves) ;; Used to soley test network partitions
          ;; :nemesis (aeron-nemesis/network-delay :all 0.5) ;; Used to test 0.5 sec network delay for all nodes
          ;; :nemesis (aeron-nemesis/network-delay :random 0.1) ;; Used to test 0.1 sec network delay for a single random node
          ;; :nemesis (aeron-nemesis/random-packet-loss :all 15) ;; Used to test 15% packet loss for all nodes
          ;; :nemesis (aeron-nemesis/random-packet-loss :random 50) ;; Used to test 15% packet loss for a single random node
          :nemesis (aeron-nemesis/kill-random-node hostname)
          :generator (gen/phases
                        (->> (independent/concurrent-generator
                              1
                              (range 0 10) ;; max is (range 0 10)
                              (fn [item-id]
                                (->> (gen/mix [(repeat 8 (client/bid item-id))
                                                (repeat 2 (client/item item-id))])
                                      (gen/stagger 1/100))))
                              (gen/nemesis
                                (concat [(gen/sleep 20)] ; warm-up period
                                        (cycle [{:type :info, :f :start}
                                                (gen/sleep 20)
                                                {:type :info, :f :stop}
                                                (gen/sleep 20)])))
                            (gen/time-limit (:time-limit opts))))                             
          :checker (checker/compose
                    { :perf  (checker/perf)
                      :indep (independent/checker
                                (checker/compose
                                  { :linear   (checker/linearizable { :model (auction-model/auction-model)
                                                                      :algorithm :linear})
                                    :timeline (timeline/html)}))})
          })))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn aeron-test})
                   (cli/serve-cmd))
            args))
