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

(defn wait-for-leader!
  ([] (wait-for-leader! 120)) ; default timeout: 60s
  ([max-wait]
   (loop [i 0]
     (if-let [leader (leader-id)]
       (do
         (info "Found leader node:" leader)
         leader)
       (do
         (when (>= i max-wait)
           (throw (ex-info "Timed out waiting for Aeron leader" {:wait-time max-wait})))
         (Thread/sleep 1000)
         (recur (inc i)))))))

;; (defn pid-of-node [node-id]
;;   (info "Attempting to get pid of node: " node-id)
;;   (let [output (-> (c/exec :bash :-c
;;                            (str "java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED "
;;                                 "-cp '/users/hilkiu2/aeron/libs/*' "
;;                                 "io.aeron.cluster.ClusterTool /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id "/cluster pid"))
;;                    :out)]
;;     (info "Output: " output)
;;     (Integer/parseInt (str/trim output))))

(defn pid-of-node [node-id]
  (let [node "node1.aeron-jepsen.cs598fts.emulab.net"
        cmd  (str "java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED "
                  "-cp '/users/hilkiu2/aeron/libs/*' "
                  "io.aeron.cluster.ClusterTool /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id "/cluster pid")]
    (info "🔍 Running PID command on" node ": " cmd)
    (try
      (c/on node
        (info "🧾 Checking if node directory exists for node" node-id)
        (c/on node
          (c/exec :ls :-l (str "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id "/cluster")))


        (let [output (-> (c/exec :bash :-c cmd) :out)]
          (info "📥 PID command output:" (pr-str output))
          (if (str/blank? output)
            (do
              (warn "⚠️  PID output was blank for node" node-id)
              (throw (ex-info "PID output was blank" {:node-id node-id :node node})))
            (try
              (Integer/parseInt (str/trim output))
              (catch NumberFormatException e
                (warn "🚫 Failed to parse PID from output:" (pr-str output))
                (throw e))))))
      (catch Exception e
        (warn e "💥 Exception in pid-of-node for node" node-id)
        (.printStackTrace e)
        (throw e)))))


;; (defn pid-of-node [node-id]
;;   (let [node "node1.aeron-jepsen.cs598fts.emulab.net"]
;;     (c/on node
;;       (let [output (-> (c/exec :bash :-c
;;                                (str "java --add-opens java.base/jdk.internal.misc=ALL-UNNAMED "
;;                                     "-cp '/users/hilkiu2/aeron/libs/*' "
;;                                     "io.aeron.cluster.ClusterTool /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node" node-id "/cluster pid"))
;;                        :out)]
;;         (info "Output: " output)
;;         (Integer/parseInt (str/trim output))))))

(defn stop-leader! []
  (when-let [leader (leader-id)]
    (let [pid (pid-of-node leader)]
      (info "Killing leader node" leader "with PID" pid)
      (c/exec :kill pid)
      leader)))

;; (defn start-node! [node-id]
;;   (info "Starting Aeron node" node-id)
;;   (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
;;     (c/exec :bash :-c
;;             (str "echo '\"[$(date)]\" Starting node " node-id "' >> /users/hilkiu2/aeron/cluster.log; JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 PATH=$JAVA_HOME/bin:$PATH ./basic-kv-cluster " node-id " >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))))

(defn start-node! [node-id]
  (let [node "node1.aeron-jepsen.cs598fts.emulab.net"
        log-path "/users/hilkiu2/aeron/cluster.log"
        err-path "/users/hilkiu2/aeron/cluster.err"
        pid-path (str "/users/hilkiu2/aeron/nodes/node" node-id ".pid")
        run-cmd (str "echo '\"[$(date)]\" Starting node " node-id "' >> " log-path "; "
                     "JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 "
                     "PATH=$JAVA_HOME/bin:$PATH "
                     "./basic-kv-cluster " node-id
                     " >> " log-path " 2>> " err-path " &")]

    (info "🚀 Starting Aeron node" node-id)
    (c/on node
      ;; Ensure PID dir exists
      (c/exec :mkdir :-p "/users/hilkiu2/aeron/nodes")

      ;; Run the cluster script
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        (c/exec :bash :-c run-cmd))

      ;; Give it time to spin up
      (c/exec :sleep "5")

      ;; Retrieve and write the PID to file
      (let [pid (pid-of-node node-id)]
        (c/exec :bash :-c (str "echo " pid " > " pid-path))
        (info "✅ Wrote PID" pid "to" pid-path)))))


(defn kill-node! [node-id]
  (let [pid-path (str "/users/hilkiu2/aeron/nodes/node" node-id ".pid")]
    (try
      (let [pid-str (-> (c/exec :bash :-c (str "cat " pid-path)) :out str/trim)]
        (info "🛑 Killing node" node-id "with PID from file:" pid-str)
        (c/exec :kill :-9 pid-str))
      (catch Exception e
        (warn "Failed to kill node" node-id ":" (.getMessage e))))))



;; (defn kill-node! [node-id]
;;   (info "Attempting to kill node: " node-id)
;;   (try
;;     (let [pid (pid-of-node node-id)]
;;       (info "Killing node" node-id "with PID" pid)
;;       (c/exec :kill :-9 pid))
;;     (catch Exception e
;;       (warn "Failed to kill node" node-id ":" (.getMessage e)))))

(defn db [version]
  (reify 
    db/DB
    (setup! [_ test node]
      ;; (info "** Setting up Aeron" version "**")
        (c/cd "/users/hilkiu2/aeron"          
          (ignore-errors (c/exec :bash :-c "pkill -f 'KVHttpServer' || true"))
          (ignore-errors (c/exec :bash :-c "pkill -f 'basic-kv-cluster' || true"))
          (ignore-errors (c/exec :bash :-c "rm -rf /dev/shm/aeron-*"))
          (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node*")
          (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs")

          (c/exec :sleep "15")

          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.log && echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.err")
          ;; (info "...Assembling aeron-agent and aeron-all")
          ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; ./gradlew :aeron-agent:assemble :aeron-all:assemble -x test -x checkstyleMain -x checkstyleTest >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err")

          (info "...Running the CLUSTER")
          (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
            (c/exec :bash :-c "echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c ./basic-kv-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err &"))
            ;; (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.pids && ps aux | grep 'basic-kv-cluster' | grep -v grep | awk '{print $2}' >> /users/hilkiu2/aeron/cluster.pids"))

          (c/exec :sleep "60")

          ;; (c/exec :mkdir :-p "/users/hilkiu2/aeron/nodes")
          ;; (let [pid (pid-of-node 0) ; get PID using your function
          ;;       pid-path (str "/users/hilkiu2/aeron/nodes/node0.pid")]
          ;;   (c/exec :bash :-c (str "echo " pid " > " pid-path))
          ;;   (info "✅ Wrote PID" pid "to" pid-path))
          ;; (let [pid (pid-of-node 1) ; get PID using your function
          ;;       pid-path (str "/users/hilkiu2/aeron/nodes/node1.pid")]
          ;;   (c/exec :bash :-c (str "echo " pid " > " pid-path))
          ;;   (info "✅ Wrote PID" pid "to" pid-path))
          ;; (let [pid (pid-of-node 2) ; get PID using your function
          ;;       pid-path (str "/users/hilkiu2/aeron/nodes/node2.pid")]
          ;;   (c/exec :bash :-c (str "echo " pid " > " pid-path))
          ;;   (info "✅ Wrote PID" pid "to" pid-path))


          ;; (wait-for-leader!)

          ;; HTTP
          (info "...Running the HTTP SERVER")
          
          (c/exec :chmod "+x" "~/setup-http.sh")
          (c/exec :bash "~/setup-kv-http.sh")
          ;; (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/httpServer.pids && pgrep -f 'KVHttpServer' >> /users/hilkiu2/aeron/httpServer.pids")
        )
    )
    (teardown! [_ test node]
      (info "Tearing down Aeron on" node)

      (ignore-errors
        (ignore-errors (c/exec :bash :-c "pkill -f KVHttpServer || true")))
      (ignore-errors
        (c/exec :bash :-c "pkill -f basic-kv-cluster || true"))
      (ignore-errors
              (c/exec :bash :-c "pkill -f BasicKVClusteredServiceNode || true"))
        
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