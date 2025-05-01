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

(defn leader-id []
  (let [output (-> (c/exec :bash :-c
                           (str "java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED "
                                "-cp '/users/hilkiu2/aeron/libs/*' "
                                "io.aeron.cluster.ClusterTool /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node0/cluster list-members"))
                   :out)]
    (when-let [match (re-find #"leaderMemberId=(\d+)" output)]
      (Integer/parseInt (second match)))))

(defn pid-of-node [node-id]
   (let [cmd (str "ps aux | grep '[B]asicAuctionClusteredServiceNode' | grep 'nodeId=" node-id "' | awk '{print $2}'")
        result (c/exec :bash :-c cmd)]
    (-> result :out str/trim))
  )

(defn latest-leader-node-id []
  (let [log-path "/users/hilkiu2/aeron/cluster.log"
        cmd (str "tac " log-path
                 " | grep -m1 'CANDIDATE -> LEADER'"
                 " | sed -n 's/.*memberId=\\([0-9]\\+\\).*/\\1/p'")
        result (c/exec :bash :-c cmd)]
    (-> result :out str/trim)))

(defn stop-leader! []
  (when-let [leader (latest-leader-node-id)]
    (let [pid (pid-of-node leader)]
      (info "Killing leader node" leader "with PID " pid)
      (c/exec :kill pid)
      leader)))

(defn start-node! [node-id]
  (let [log-path "/users/hilkiu2/aeron/cluster.log"
        err-path "/users/hilkiu2/aeron/cluster.err"
        node "node0.hilkiu2-255959.cs598fts-pg0.utah.cloudlab.us"
        pid (pid-of-node node-id)
        run-cmd (str "echo '\"[$(date)]\" Starting node " node-id "' >> " log-path "; "
                     "./basic-auction-cluster " node-id
                     " >> " log-path " 2>> " err-path " &")]

    (info "Restarting node " node-id " with resolved PID " pid)
    (c/on node
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        (c/exec :bash :-c run-cmd)))))

(defn kill-node! [node-id nodehostname]
  (let [pid (pid-of-node node-id)]
    (info "Killing node " node-id " with PID " pid)
    (c/on nodehostname
      (c/exec :kill :-9 pid)
      (c/exec :sleep "2"))))

(defn db [version]
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
            (c/exec :bash :-c "echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c ./basic-auction-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err &"))

          (c/exec :sleep "60")

          ;; HTTP
          (info "...Running the HTTP SERVER")
          
          (c/exec :chmod "+x" "~/setup-http.sh")
          (c/exec :bash "~/setup-http.sh")
        )
    )
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

      (c/exec :sleep "15")
    
      ;; (let [check-results
      ;;     {:remaining-nodes    (c/exec :bash :-c "ls -1 /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node* 2>/dev/null || echo NONE")
      ;;     :remaining-shm      (c/exec :bash :-c "ls -1 /dev/shm/aeron* 2>/dev/null || echo NONE")
      ;;     :remaining-logs     (c/exec :bash :-c "ls -1 /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/* 2>/dev/null || echo NONE")}]
      ;; (doseq [[label result] check-results]
      ;;   (if (re-find #"NONE" result)
      ;;     (info label "Clean")
      ;;     (warn label "Still present! " result))))
    )

    ;; db/Kill
    ;; (start! [test node]
    ;;   (start-node! node)
    ;;   (c/exec :sleep "2"))

    ;; (kill! [test node]
    ;;   (kill-node! node))

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