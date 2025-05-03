(ns jepsen.aeron.db
  (:require [jepsen.control :as c]
            [jepsen.db :as db]
            [clojure.tools.logging :refer :all]
            [clojure.string :as str]))

(defmacro ignore-errors
  [& body]
  `(try
     ~@body
     (catch Throwable t#
       (warn "Command failed:" '~body "\nReason:" (.getMessage t#)))))

(defn pid-of-node [node-id]
  (let [cmd (str "ps aux | grep '[B]asicAuctionClusteredServiceNode' | grep 'nodeId=" node-id "' | awk '{print $2}'")]
    (-> (c/exec :bash :-c cmd))))

(defn latest-leader-node-id [hostname]
  (c/on hostname
    (let [log-path "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-0.log"
          cmd (str "tac " log-path
                  " | stdbuf -oL grep -m1 'leaderId'"
                  " | sed -n 's/.*leaderId=\\([0-9]\\+\\).*/\\1/p'")
          result (c/exec :bash :-c cmd)]
      (-> result))))

(defn start-node! [node-id hostname]
  (let [log-path "/users/hilkiu2/aeron/cluster.log"
        err-path "/users/hilkiu2/aeron/cluster.err"
        run-cmd (str "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH;"
                     "./basic-auction-cluster " node-id
                     " >> " log-path " 2>> " err-path)]
    (c/on hostname
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        (c/exec :bash :-c run-cmd)
        ;; (c/exec :sleep "1")
        )))) ;; 500 ms to catch up with logs after respawning

(defn kill-node! [node-id hostname]
  (when-let [pid (pid-of-node node-id)]
    (c/on hostname
      (c/exec :kill :-9 pid)
      (c/exec :bash :-c (str "rm -rf /dev/shm/aeron-hilkiu2-" node-id "-driver"))
      (c/exec :bash :-c (str "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id))
      ;; (c/exec :sleep "3") ;; 1.5 sec to elect leader once timeout detected & 2 sec to detect timeout
      )))

(defn db [version hostname]
  (reify 
    db/DB
    (setup! [_ test node]
        (c/cd "/users/hilkiu2/aeron"          
          (ignore-errors (c/exec :bash :-c "pkill -f 'AuctionHttpServer' || true"))
          (ignore-errors (c/exec :bash :-c "pkill -f 'basic-auction-cluster' || true"))
          (ignore-errors (c/exec :bash :-c "rm -rf /dev/shm/aeron-*"))
          (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node*")
          (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs")

          (c/exec :sleep "15")

          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.log && echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.err")
          ;; (info "...Assembling aeron-agent and aeron-all")
          ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; ./gradlew :aeron-agent:assemble :aeron-all:assemble -x test -x checkstyleMain -x checkstyleTest >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err")

          (info "...Running the CLUSTER")
          (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
            (c/exec :bash :-c "echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c ./basic-auction-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))

          ;; (c/exec :sleep "10")

          ;; HTTP
          (info "...Running the HTTP SERVER")
          
          (c/exec :chmod "+x" "~/setup-http.sh")
          (c/exec :bash "~/setup-http.sh")))
          
    (teardown! [_ test node]
      (info "Tearing down Aeron on" node)

      (ignore-errors
        (ignore-errors (c/exec :bash :-c "pkill -f AuctionHttpServer || true")))
      (ignore-errors
        (c/exec :bash :-c "pkill -f basic-auction-cluster || true"))
      (ignore-errors
              (c/exec :bash :-c "pkill -f BasicAuctionClusteredServiceNode || true"))
        
      (c/exec :sleep "10")

      ;; Clean shared memory (only after processes are stopped)
      (c/exec :bash :-c "rm -rf /dev/shm/aeron-*")
      (c/exec :sleep "5")

      ;; Clean node directories/logs
      (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node*")
      (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs")
      (c/exec :sleep "5")

      (c/exec :rm :-f "/users/hilkiu2/aeron/nodes/*")

      (c/exec :sleep "15"))
    
    db/Kill
    (start! [_ test node]
      (start-node! node hostname)
      ;; (c/exec :sleep "10")
      )

    (kill! [_ test node]
      (kill-node! node hostname))

    db/LogFiles
    (log-files [_ test node]
    ["/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-0.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-1.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-2.log"
     "/users/hilkiu2/aeron/cluster.log"
     "/users/hilkiu2/aeron/cluster.err"
     "/users/hilkiu2/aeron/httpServer.log"
     "/users/hilkiu2/aeron/httpServer.pid"
     "/users/hilkiu2/aeron/httpServer.err"])
  )
)