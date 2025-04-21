;; (ns jepsen.aeron
;;   (:require [jepsen.aeron.db :as db]
;;             [jepsen.aeron.client :as client]
;;             [jepsen.aeron.model :as model]
;;             [clojure.tools.logging :refer :all]
;;             [clojure.string :as str]
;;             [jepsen [checker :as checker]
;;                     [cli :as cli]
;;                     [control :as c]
;;                     [db :as db]
;;                     [tests :as tests]
;;                     [generator :as gen]
;;                     [client :as client]]
;;             [jepsen.control.util :as cu]
;;             [jepsen.checker.timeline :as timeline]
;;             [jepsen.os.debian :as debian]
;;             [clj-http.client :as http]))

(ns jepsen.aeron
  (:require [jepsen.aeron.client :as client]
            [jepsen.aeron.db :as db]
            [jepsen.aeron.model :as model]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.cli :as cli]
            [jepsen.generator :as gen]
            [jepsen.tests :as tests]))

(defn aeron-test [opts]
  (merge tests/noop-test
         opts
         {:name "aeron"
          :nodes ["node0.aeron-jepsen.cs598fts.emulab.net"]
          :pure-generators true
          :db (db/db "v1.47.4")
          :client (client/->Client nil)
          :generator (->> (gen/mix [
                            (fn [_ _] (or (client/create nil nil) nil))
                            (fn [_ _] (or (client/bid nil nil) nil))
                            (fn [_ _] (or (client/close nil nil) nil))])
                (gen/stagger 1)
                (gen/time-limit 15))

          ;; :checker (checker/compose
          ;;            {:linear (checker/linearizable
          ;;                       {:model (->model/AuctionModel #{})
          ;;                        :algorithm :linear})
          ;;             :perf (checker/perf)
          ;;             :timeline (timeline/html)})
                      }))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn aeron-test})
                   (cli/serve-cmd))
            args))
