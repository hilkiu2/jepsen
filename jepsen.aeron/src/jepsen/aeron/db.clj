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

(defn db [version hostname]
  (reify 
    db/DB
    (setup! [_ test node]
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        ;; (info "...Setting up namespaces")
        (c/su (c/exec :bash :-c "./setup-namespaces >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))

        (info "...Running the CLUSTER")
        (c/su (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.log && 
                                  echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.err && 
                                  echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && 
                                  echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && 
                                  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 && 
                                  export PATH=$JAVA_HOME/bin:$PATH && 
                                  ./basic-auction-cluster-ns >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))
        ;;                   ./basic-auction-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))

                      
        (info "...Running the HTTP CLIENT")
        (c/exec :chmod "+x" "./setup-http.sh")
        (c/exec :bash "./setup-http.sh")
        ))

    (teardown! [_ test node]
      (info "Tearing down Aeron on" node)

      (ignore-errors
        (c/su (c/exec :bash :-c "pkill -f AuctionHttpServer || true")))
      (ignore-errors
        (c/su (c/exec :bash :-c "pkill -f basic-auction-cluster || true")))
      (ignore-errors
        (c/su (c/exec :bash :-c "pkill -f BasicAuctionClusteredServiceNode || true")))
        
      (c/exec :sleep "10")

      ;; Clean shared memory (only after processes are stopped)
      (c/su (c/exec :bash :-c "rm -rf /dev/shm/aeron-*"))
      (c/exec :sleep "5")

      ;; Clean node directories/logs
      (c/su (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node*"))
      (c/su (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs"))
      (c/exec :sleep "5")

      (c/su (c/exec :rm :-f "/users/hilkiu2/aeron/nodes/*"))

      ;; Remove cluster namespace and veth interface
      (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
        (ignore-errors
          (c/su (c/exec :bash :-c "./remove-namespaces >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err"))))

      (c/exec :sleep "15"))
    
    ;; db/Kill
    ;; (start! [_ test node]
    ;;   (start-node! node hostname)
    ;;   ;; (c/exec :sleep "10")
    ;;   )

    ;; (kill! [_ test node]
    ;;   (kill-node! node hostname))

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