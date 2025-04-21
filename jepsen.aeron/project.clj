(defproject jepsen.aeron "0.1.0-SNAPSHOT"
  :description "A Jepsen test for Aeron"
  :url "https://github.com/aeron-io"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main jepsen.aeron
  :dependencies [[org.clojure/clojure "1.10.0"]
                [jepsen "0.2.1-SNAPSHOT"]
                [clj-http "3.12.3"]])
