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
      (info "** Setting up Aeron" version "**")
        (c/cd "/users/hilkiu2/aeron"
          ;; Clean up previous cluster dirs and shared memory
          (c/exec :rm :-rf "aeron-samples/scripts/cluster/node*")
          ;; (c/exec :rm :-rf "/dev/shm/aeron-*")
          (c/exec :rm :-f "aeron-samples/scripts/cluster/logs/cluster-*.log")

          (info "...Assembling aeron-agent and aeron-all")
          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.log && echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; ./gradlew :aeron-agent:assemble :aeron-all:assemble -x test -x checkstyleMain -x checkstyleTest >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err")

          (info "...Running the CLUSTER")
          (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
            (c/exec :bash :-c "echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.log && echo '\nRunning cluster... ' >> /users/hilkiu2/aeron/cluster.err && export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c ./basic-auction-cluster >> /users/hilkiu2/aeron/cluster.log 2>> /users/hilkiu2/aeron/cluster.err &")
            (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/cluster.pids && ps aux | grep 'basic-auction-cluster' | grep -v grep | awk '{print $2}' >> /users/hilkiu2/aeron/cluster.pids"))

          (c/exec :sleep "60")

          ;; CLIENT BUILD
          ;; (info "\nStep 2: Launching Aeron CLIENT process")
          ;; (info "Assemble aeron-cluster and aeron-samples")
          ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; ./gradlew :aeron-client:assemble :aeron-cluster:assemble :aeron-samples:assemble -x test -x checkstyleMain -x checkstyleTest > /users/hilkiu2/aeron/cluster.log 2> /users/hilkiu2/aeron/cluster.err")

          ;; CLIENT RUN
          ;; (info "Running the CLIENT & 10 bids")
          ;; (c/cd "/users/hilkiu2/aeron/aeron-samples/scripts/cluster"
            ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; java --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED -cp '/users/hilkiu2/aeron/libs/*:/users/hilkiu2/aeron/aeron-samples/build/libs/aeron-samples-1.47.4.jar' -Daeron.cluster.tutorial.customerId=10 -Daeron.cluster.tutorial.numOfBids=10 -Daeron.cluster.tutorial.bidIntervalMs=1000 io.aeron.samples.cluster.tutorial.BasicAuctionClusterClient > /users/hilkiu2/aeron/client.log 2> /users/hilkiu2/aeron/client.err &")
            ;; (c/exec :bash :-c "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64; export PATH=$JAVA_HOME/bin:$PATH; bash -c /users/hilkiu2/aeron/aeron-samples/scripts/cluster/basic-auction-client 10 10 1000 > /users/hilkiu2/aeron/client.log 2> /users/hilkiu2/aeron/client.err &")
            ;; (c/exec :bash :-c "ps aux | grep 'basic-auction-client' | grep -v grep | awk '{print $2}' > /users/hilkiu2/aeron/client.pids"))

          ;; (c/exec :sleep "90")

          ;; HTTP
          (info "...Running the HTTP SERVER")
          
          (c/exec :chmod "+x" "~/setup-http.sh")
          (c/exec :bash "~/setup-http.sh")
          (c/exec :bash :-c "echo \"[$(date)]\" > /users/hilkiu2/aeron/httpServer.pids && pgrep -f 'AuctionHttpServer' >> /users/hilkiu2/aeron/httpServer.pids")

          ;; (c/exec :sleep "90") ;; wait for logs to catch up
        )
    )
    (teardown! [_ test node]
      (info "Tearing down Aeron on" node)
      (ignore-errors
        (c/su (c/exec :pkill :-f "BasicAuctionClusteredServiceNode")))
      (ignore-errors
        (c/su (c/exec :pkill :-f "BasicAuctionClusterClient")))
      (ignore-errors
        (c/su (c/exec :pkill :-f "bash ./basic-auction-cluster")))
      (ignore-errors
        (c/su (c/exec :pkill :-f "AuctionHttpServer")))
    )

    db/LogFiles
    (log-files [_ test node]
    ["/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-0.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-1.log"
     "/users/hilkiu2/aeron/aeron-samples/scripts/cluster/logs/cluster-2.log"
     "/users/hilkiu2/aeron/cluster.log"
     "/users/hilkiu2/aeron/cluster.pids"
     "/users/hilkiu2/aeron/cluster.err"
    ;;  "/users/hilkiu2/aeron/client.log"
    ;;  "/users/hilkiu2/aeron/client.pids"
    ;;  "/users/hilkiu2/aeron/client.err"
     "/users/hilkiu2/aeron/httpServer.log"
     "/users/hilkiu2/aeron/httpServer.pids"
     "/users/hilkiu2/aeron/httpServer.err"])
  )
)