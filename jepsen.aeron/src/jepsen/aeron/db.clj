(ns jepsen.aeron.db
  (:require [jepsen.control :as c]
            [jepsen.db :as db]
            [clojure.tools.logging :refer :all]))

(defmacro ignore-errors
  [& body]
  `(try
     ~@body
     (catch Throwable t#
       (warn "Command failed:" '~body "\nReason:" (.getMessage t#)))))

(def setup-http-script
  (str "#!/bin/bash\n"
       "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64\n"
       "export PATH=$JAVA_HOME/bin:$PATH\n"
       "cd /users/hilkiu2/aeron\n"
       "echo \"[`date`]\" > /users/hilkiu2/aeron/httpServer.log\n"
       "echo \"[`date`]\" > /users/hilkiu2/aeron/httpServer.err\n"
       "nohup java \\\n"
       "  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \\\n"
       "  --add-opens=java.base/java.nio=ALL-UNNAMED \\\n"
       "  -cp '/users/hilkiu2/aeron/libs/*' \\\n"
       "  io.aeron.samples.cluster.tutorial.AuctionHttpServer \\\n"
       "  >> /users/hilkiu2/aeron/httpServer.log 2>> /users/hilkiu2/aeron/httpServer.err & disown\n"))

(defn db [version]
  (reify db/DB
    (setup! [_ test node]
      ;; (info "** Setting up Aeron" version "**")
        (c/cd "/users/hilkiu2/aeron"          
          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.log && echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.err")
          ;; (info "...Assembling aeron-agent and aeron-all")
          ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; ./gradlew :aeron-agent:assemble :aeron-all:assemble -x test -x checkstyleMain -x checkstyleTest >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err")

          (info "...Running the CLUSTER")
          (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
            (c/exec :bash :-c "echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c ./basic-auction-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err &")
            (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.pids && ps aux | grep 'basic-auction-cluster' | grep -v grep | awk '{print $2}' >> /users/hilkiu2/aeron/cluster.pids"))

          (c/exec :sleep "60")

          ;; HTTP
          (info "...Running the HTTP SERVER")
          
          (c/exec :chmod "+x" "~/setup-http.sh")
          (c/exec :bash "~/setup-http.sh")
          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/httpServer.pids && pgrep -f 'AuctionHttpServer' >> /users/hilkiu2/aeron/httpServer.pids")
        )
    )
    (teardown! [_ test node]
      (info "Tearing down Aeron on" node)

      (ignore-errors
        (c/exec :bash :-c "pkill -f Auction || true"))
      (c/exec :sleep "15")

      (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/node*")
      (c/exec :sleep "15")

      (c/exec :bash :-c "rm -rf /dev/shm/aeron-*")
      (c/exec :sleep "15")

      (c/exec :bash :-c "rm -rf /users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs")
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

    db/LogFiles
    (log-files [_ test node]
    ["/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-0.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-1.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-2.log"
     "/users/hilkiu2/aeron/cluster.log"
     "/users/hilkiu2/aeron/cluster.pids"
     "/users/hilkiu2/aeron/cluster.err"
     "/users/hilkiu2/aeron/httpServer.log"
     "/users/hilkiu2/aeron/httpServer.pids"
     "/users/hilkiu2/aeron/httpServer.err"])
  )
)