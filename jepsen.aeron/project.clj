(defproject jepsen.aeron "0.1.0-SNAPSHOT"
  :description "A Jepsen test for Aeron"
  :url "https://github.com/aeron-io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.aeron
  :jvm-opts ["-Xmx8g" "-Xms512m"]
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [jepsen "0.3.8"
                  :exclusions [org.slf4j/slf4j-log4j12
                               log4j/log4j
                               org.slf4j/log4j-over-slf4j]]
                 [clj-http "3.12.3"]
                 [cheshire "5.11.0"]
                 [knossos "0.3.6"
                  :exclusions [org.slf4j/slf4j-log4j12
                               log4j/log4j
                               org.slf4j/log4j-over-slf4j]]
])
