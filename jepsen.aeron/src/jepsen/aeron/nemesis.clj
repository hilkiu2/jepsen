(ns jepsen.aeron.nemesis
  (:require [jepsen.control :as c]
            [dom-top.core :refer [real-pmap]]
            [jepsen.net.proto :as p :refer [Net PartitionAll]]
            [jepsen.nemesis :as nemesis]
            [jepsen.control.net :as control.net]
            [clojure.set :as set]
            [jepsen.generator :as gen]
            [jepsen.aeron.db :as aeron-db]
            [clojure.tools.logging :refer :all]
            [jepsen.db :as db]
            [jepsen.util :as util]
            [jepsen.net :as net]))

(def node->net
  {0 {:ns-name "ns_node0" :dev "veth_a0" :ip "10.42.0.10"}
   1 {:ns-name "ns_node1" :dev "veth_a1" :ip "10.42.0.11"}
   2 {:ns-name "ns_node2" :dev "veth_a2" :ip "10.42.0.12"}})

(defn reset-net!
  [test {:keys [ns-name dev]}]
  (c/on-nodes test
    (fn [_ _]
      (try
        (c/su (c/exec :ip :netns :exec ns-name :tc :qdisc :del :dev dev :root))
        (catch Exception e
          (when-not (re-find #"Cannot delete qdisc" (.getMessage e))
            (throw e)))))))

;; PAPCKET LOSS SOLEY IN CLUSTER
;; Inspired by Jepsen's slow func
(defn loss-net!
  [test {:keys [ns-name dev]} {:keys [percent correlation]
                               :or   {percent 10 correlation 25}}]
  (c/on-nodes test
    (fn [_ _]
      (c/su (c/exec :ip :netns :exec ns-name
                    :tc :qdisc :add :dev dev :root :netem :loss
                    (str percent "%") (str correlation "%"))))))

(defn random-packet-loss
  "If `mode` is :all, packet loss for all nodes. If `:random`, packet loss for one random node."
  [mode percent]
  (let [all-nodes (vals node->net)
        active-nodes (atom [])]
    (reify nemesis/Nemesis
      (setup! [this test]
        (doseq [n all-nodes] (reset-net! test n))
        this)

      (invoke! [_ test op]
        (case (:f op)
          :start (let [nodes (if (= mode :all)
                                 all-nodes
                                 [(rand-nth all-nodes)])]
                   (reset! active-nodes nodes)
                   (doseq [n nodes]
                     (loss-net! test n {:percent percent}))
                    op)

          :stop (do
                  (doseq [n @active-nodes]
                    (reset-net! test n))
                  (reset! active-nodes [])
                  op)
          op))

      (teardown! [_ test]
        (doseq [n all-nodes] (reset-net! test n))))))

;; DELAY NETWORK SOLEY IN CLUSTER
;; Adapted Jepsen's net slow functions
(defn slow-net!
  [test {:keys [ns-name dev]} {:keys [mean variance distribution]
                     :or   {mean 50 variance 10 distribution :normal}}]
  (c/on-nodes test
    (fn [_ _]
      (c/su (c/exec :ip :netns :exec ns-name
                    :tc :qdisc :add :dev dev :root :netem :delay
                    (str mean "ms") (str variance "ms") :distribution distribution)))))

(defn network-delay
  "If `mode` is :all, slow all nodes. If `:random`, slow one random node."
  [mode dt]
  (let [all-nodes (vals node->net)
        active-nodes (atom [])]
    (reify nemesis/Nemesis
      (setup! [this test]
        (doseq [n all-nodes] (reset-net! test n))
        this)

      (invoke! [_ test op]
        (case (:f op)
          :start (let [nodes (if (= mode :all)
                                 all-nodes
                                 [(rand-nth all-nodes)])]
                   (reset! active-nodes nodes)
                   (doseq [n nodes]
                     (slow-net! test n {:mean (* dt 1000)}))
                    op)

          :stop (do
                  (doseq [n @active-nodes]
                    (reset-net! test n))
                  (reset! active-nodes [])
                  op)
          op))

      (teardown! [_ test]
        (doseq [n all-nodes] (reset-net! test n))))))

;; KILLING RANDOM NODE 
(defn pid-of-node [node-id ns-name]
  ;; (info "Fetching PID of node" node-id)
  (let [cmd (str "ps -eo pid,comm,args | grep '^ *[0-9]\\+ *java ' | grep 'BasicAuctionClusteredServiceNode' | grep 'nodeId=" node-id "' | awk '{print $1}'")]
    (-> (c/su (c/exec :ip :netns :exec ns-name
                      :bash :-c cmd)))))

;; (defn latest-leader-node-id [hostname ns-name]
;;   (c/on hostname
;;     (let [log-path "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-0.log"
;;           cmd (str "tac " log-path
;;                   " | stdbuf -oL grep -m1 'leaderId'"
;;                   " | sed -n 's/.*leaderId=\\([0-9]\\+\\).*/\\1/p'")
;;           result (c/su (c/exec :ip :netns :exec ns-name
;;                                :bash :-c cmd))]
;;       (-> result))))

(defn start-node! [node-id hostname]
  (let [ns-name (:ns-name (get node->net node-id))
        log-path "/users/hilkiu2/aeron/cluster.log"
        err-path "/users/hilkiu2/aeron/cluster.err"
        run-cmd (str "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH;"
                     "./basic-auction-cluster-ns " node-id
                     " >> " log-path " 2>> " err-path)]
    (c/on hostname
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        (c/su (c/exec :ip :netns :exec ns-name
                      :bash :-c run-cmd))
        )))) ;; 500 ms to catch up with logs after respawning

(defn kill-node! [node-id hostname]
  (let [ns-name (:ns-name (get node->net node-id))
        pid (pid-of-node node-id ns-name)]
    (if (empty? pid)
          (info "ERROR: No PID found for node" node-id)
          (do
            ;; (info "Killing node" node-id "with pid" pid)
            (c/on hostname
              (c/su (c/exec :ip :netns :exec ns-name :kill :-9 pid))
              (c/su (c/exec :ip :netns :exec ns-name
                            :bash :-c
                            (str "rm -rf /dev/shm/aeron-root-" node-id "-driver "
                                "&& rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id))))))))
                                ;; (c/exec :sleep "3") ;; 1.5 sec to elect leader once timeout detected & 2 sec to detect timeout

;; Taken and adapted from Jepsen's nemesis.clj to use a specific hostname each time and the start/stop will be chosen differently than from the :nodes
(defn node-start-stopper
  [targeter start! stop!]
  (let [nodes (atom nil)]
    (reify nemesis/Nemesis
      (setup! [this test] this)

      (invoke! [this test op]
        (locking nodes
          (assoc op :type :info
                 :value
                 (case (:f op)
                   :start (let [ns (:nodes test)
                                hostname (first (:nodes test))
                                selected (try (targeter test ns)
                                              (catch clojure.lang.ArityException _
                                                (targeter ns)))
                                selected (util/coll selected)]
                            (if selected
                              (if (compare-and-set! nodes nil selected)
                                (c/on hostname
                                  (->> selected
                                       (map (fn [node-id]
                                              [node-id (start! test node-id)]))
                                       (into {})))
                                (str "nemesis already disrupting "
                                     (pr-str @nodes)))
                              :no-target))

                   :stop (if-let [selected @nodes]
                           (let [hostname (first (:nodes test))
                                    res (c/on hostname
                                       (->> selected
                                            (map (fn [node-id]
                                                   [node-id (stop! test node-id)]))
                                            (into {})))]
                             (reset! nodes nil)
                             res)
                           :not-started)))))

      (teardown! [_ _]))))

(defn kill-random-node [hostname]
  (do 
    (node-start-stopper
      (fn [_ _] (rand-int 3))
      (fn start [test node-id] (kill-node! node-id hostname) {:node node-id :action :killed})
      (fn stop [test node-id] (start-node! node-id hostname) {:node node-id :action :restarted}))))

(defn flush-tc-filters!
  [ns-name dev]
  (try
    ;; Deletes filters, keeps qdisc structure
    (c/su (c/exec :bash "-c" (str "ip netns exec " ns-name " tc qdisc del dev " dev " root || true")))
    (c/su (c/exec :bash "-c" (str "ip netns exec " ns-name " tc qdisc del dev " dev " ingress || true")))
    (c/su (c/exec :bash "-c" (str "ip netns exec " ns-name " tc qdisc del dev " dev " clsact || true")))
    (c/su (c/exec :bash "-c" (str "ip netns exec " ns-name " tc qdisc add dev " dev " clsact")))))

;; Adapted Jepsen's network partitioning tests to use local node with nodes in namespaces, from jepsen.nemesis and jepsen.net
(defn setup-qdisc! [ns-name dev]
  ;; Try deleting first to avoid duplicate errors
  (try
    (c/su (c/exec :ip :netns :exec ns-name :tc :qdisc :del :dev dev :root))
    (catch Exception _))
  ;; Now set up the qdiscs
  (c/su (c/exec :ip :netns :exec ns-name
               :tc :qdisc :add :dev dev :root :handle "1:" :prio :bands 4
               :priomap 1 2 2 2 1 2 0 0 1 1 1 1 1 1 1 1))
  (c/su (c/exec :ip :netns :exec ns-name
               :tc :qdisc :add :dev dev :parent "1:4" :handle "40:" :netem))            
  (flush-tc-filters! ns-name dev))

(defn drop!
  [test from to]
  (let [from-ns (:ns-name (get node->net from))
        from-dev (:dev (get node->net from))
        from-ip (:ip (get node->net from))
        to-ip (:ip (get node->net to))]

    (info "Dropping packets from" from "to" to "-> via ns" from-ns ", dev" from-dev ", to-ip" to-ip)
    (c/on (first (:nodes test))
      (c/su (c/exec :ip :netns :exec from-ns
                    :tc :filter :add :dev from-dev
                    :ingress :protocol :ip :prio 10 :flower :src_ip (control.net/ip to-ip) :dst_ip (control.net/ip from-ip) :action :drop))
                    
      (c/su (c/exec :ip :netns :exec from-ns
                    :tc :filter :add :dev from-dev
                    :egress :protocol :ip :prio 10 :flower :src_ip (control.net/ip from-ip) :dst_ip (control.net/ip to-ip) :action :drop)))))

(defn heal!
  [test]
  (c/on (first (:nodes test)) 
    (doseq [{:keys [ns-name dev]} (vals node->net)]
        (flush-tc-filters! ns-name dev))))

;; (defn drop! [net test src dest]
;;       (on-nodes test [dest]
;;                 (fn [test node]
;;                   (su (exec :iptables :-A :INPUT :-s (control.net/ip src) :-j
;;                             :DROP :-w)))))

;; (defn heal! [net test]
;;   (with-test-nodes test
;;     (su
;;       (exec :iptables :-F :-w)
;;       (exec :iptables :-X :-w))))

(defn drop-all!
  "Takes a test and a grudge: a map of nodes to collections of nodes they
  should drop messages from, and makes those changes to the test's network."
  [test grudge]
  (let [net (:net test)]
    (doseq [[dst srcs] grudge
      src srcs]
      (drop! test src dst))))

(defn partitioner
  "Responds to a :start operation by cutting network links as defined by
  (grudge nodes), and responds to :stop by healing the network. The grudge to
  apply is either taken from the :value of a :start op, or if that is nil, by
  calling (grudge (:nodes test))"
  ([] (partitioner nil))
  ([grudge node-ids]
   (reify nemesis/Nemesis
     (setup! [this test]
       ;; setup-qdisc resets network and also sets it up with bands for isolating netwroks
       (c/on (first (:nodes test)) 
        (doseq [{:keys [ns-name dev]} (vals node->net)]
          (setup-qdisc! ns-name dev)))
       this)

     (invoke! [this test op]
       (case (:f op)
         :start (let [g (or (:value op)
                                 (if grudge
                                   (grudge node-ids)
                                   (throw (IllegalArgumentException.
                                            (str "Expected op " (pr-str op)
                                                 " to have a grudge for a :value, but none given.")))))]
                  (drop-all! test g)
                  (assoc op :value [:isolated g]))
         :stop  (do (heal! test)
                    (assoc op :value :network-healed))))

     (teardown! [this test]
       (doseq [[_ node-info] node->net]
        (reset-net! test node-info))))))

(defn partition-halves
  "Responds to a :start operation by cutting the network into two halves--first
  nodes together and in the smaller half--and a :stop operation by repairing
  the network."
  []
  (partitioner (comp nemesis/complete-grudge nemesis/bisect) (keys node->net)))

(defn partition-random-halves
  "Cuts the network into randomly chosen halves."
  []
  (partitioner (comp nemesis/complete-grudge nemesis/bisect shuffle) (keys node->net)))

(defn partition-random-node
  "Isolates a single node from the rest of the network."
  []
  (partitioner (comp nemesis/complete-grudge nemesis/split-one) (keys node->net)))