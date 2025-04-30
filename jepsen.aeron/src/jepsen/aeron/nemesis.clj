(ns jepsen.aeron.nemesis
  (:require [jepsen.control :as c]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator :as gen]
            [jepsen.aeron.db :as aeron-db]
            [jepsen.net :as net]))

(defn fast-net!
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
      (fast-net! test iface)
      (nemesis/setup! nem test)
      this)

    (invoke! [this test op]
      (case (:f op)
        :start (do (slow-net! test iface {:mean (* dt 1000)})
                   (nemesis/invoke! nem test op))

        :stop  (try
                 (nemesis/invoke! nem test op)
                 (finally (fast-net! test iface)))

        (nemesis/invoke! nem test op)))

    (teardown! [this test]
      (fast-net! test iface)
      (nemesis/teardown! nem test))))

;; Taken from cockroachDB's jepsen tests: https://github.com/jepsen-io/jepsen/blob/461235a04f2e9d28c8deb3c89d394dbc622f46cb/cockroachdb/src/jepsen/cockroach/nemesis.clj#L152
(def nemesis-delay 5) ; seconds
(def nemesis-duration 5) ; seconds

;; (defn nemesis-single-gen
;;   []
;;   {:during (take 5
;;                  (cycle [(gen/sleep nemesis-delay)
;;                          {:type :info, :f :start}
;;                          (gen/sleep nemesis-duration)
;;                          {:type :info, :f :stop}]))
;;    :final (gen/once {:type :info, :f :stop})})

;; (defn startstop
;;   [n]
;;   (merge (nemesis-single-gen)
;;          {:name (str "startstop" (if (> n 1) n ""))
;;           :client (jNemesis/hammer-time
;;                     (comp (partial take n) shuffle) "aeron")
;;           :clocks false}))

;; (defn startstop
;;   [n]
;;   (jNemesis/standard-nemesis
;;     (merge (nemesis-single-gen)
;;            {:name   (str "startstop" (if (> n 1) n ""))
;;             :client (jNemesis/hammer-time
;;                       (comp (partial take n) shuffle) "aeron")
;;             :clocks false})))

(defn start! [test node]
    (aeron-db/start-node! node)
    (c/exec :sleep "2"))

(defn kill! [test node]
    (aeron-db/kill-node! node))

;; (defn startkill
;;   [n]
;;   (jNemesis/standard-nemesis
;;     (merge (nemesis-single-gen)
;;            {:name   (str "startkill" (if (> n 1) n ""))
;;             :client (jNemesis/node-start-stopper
;;                       (comp (partial take n) shuffle)
;;                       kill!
;;                       start!)
;;             :clocks false})))

;; (defn startkill
;;   [n]
;;   (merge (nemesis-single-gen)
;;          {:name (str "startkill" (if (> n 1) n ""))
;;           :client (jNemesis/node-start-stopper (comp (partial take n) shuffle)
;;                                               kill!
;;                                               start!)
;;           :clocks false}))

;; (defn startstop
;;   [n]
;;   (let [gen {:during (take 20 (cycle [(gen/sleep 5)
;;                                       {:type :info, :f :start}
;;                                       (gen/sleep 5)
;;                                       {:type :info, :f :stop}]))
;;              :final (gen/once {:type :info, :f :stop})}
;;         target-nodes (comp (partial take n) shuffle)]
;;     (let [
;;           nemesis
;;           (reify jNemesis/Nemesis
;;             (setup! [this _] this) 
;;             (teardown! [_ _] nil)
;;             (invoke! [_ test op]
;;               (case (:f op)
;;                 :start
;;                 (let [node (first (target-nodes (:nodes test)))]
;;                   (aeron-db/kill-node! node)
;;                   (assoc op :value node))

;;                 :stop
;;                 (let [node (first (target-nodes (:nodes test)))]
;;                   (aeron-db/start-node! node)
;;                   (assoc op :value node))

;;                 op)))]
;;       {:nemesis nemesis
;;        :generator gen})))

;; (defn startstop
;;   [n]
;;   (let [target-nodes (comp (partial take n) shuffle)]
;;     (reify jepsen.nemesis/Nemesis
;;       (setup! [this _] this)
;;       (teardown! [_ _] nil)
;;       (invoke! [_ test op]
;;         (case (:f op)
;;           :start
;;           (let [node (rand-int 3)]
;;           ;; (let [node (first (target-nodes (:nodes test)))]
;;             (aeron-db/kill-node! node)
;;             ;; Inject chosen node into op so stop can reuse it
;;             (assoc op :value node))

;;           :stop
;;           (let [node (:value op)] ;; use the same node from :start
;;             (aeron-db/start-node! node)
;;             (assoc op :value node))

;;           op)))))
