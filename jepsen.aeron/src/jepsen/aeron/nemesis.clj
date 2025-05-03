(ns jepsen.aeron.nemesis
  (:require [jepsen.control :as c]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.aeron.db :as aeron-db]
            [clojure.tools.logging :refer :all]
            [jepsen.db :as db]
            [jepsen.util :as util]
            [jepsen.net :as net]))

(defn reset-net!
  [test iface]
  (c/on-nodes test
    (fn [_ _]
      (try
        (c/su (c/exec :tc :qdisc :del :dev iface :root))
        (catch Exception e
          (when-not (re-find #"Cannot delete qdisc" (.getMessage e))
            (throw e)))))))

;; Adapted Jepsen's net slow and fast functions
(defn slow-net!
  [test iface {:keys [mean variance distribution]
               :or   {mean 50 variance 10 distribution :normal}}]
  (c/on-nodes test
    (fn [_ _]
      (c/su (c/exec :tc :qdisc :add :dev iface :root :netem :delay
                    (str mean "ms") (str variance "ms") :distribution distribution)))))

(defn slowing
  [nem iface dt]
  (reify nemesis/Nemesis
    (setup! [this test]
      (reset-net! test iface)
      (nemesis/setup! nem test)
      this)

    (invoke! [this test op]
      (case (:f op)
        :start (do (slow-net! test iface {:mean (* dt 1000)})
                   (nemesis/invoke! nem test op))

        :stop  (try
                 (nemesis/invoke! nem test op)
                 (finally (reset-net! test iface)))

        (nemesis/invoke! nem test op)))

    (teardown! [this test]
      (reset-net! test iface)
      (nemesis/teardown! nem test))))

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
      (fn start [test node-id] (db/kill! (:db test) test node-id) {:node node-id :action :killed})
      (fn stop [test node-id] (db/start! (:db test) test node-id) {:node node-id :action :restarted}))))