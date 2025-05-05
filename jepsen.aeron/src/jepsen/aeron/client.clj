(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [jepsen.independent :as independent]
              [cheshire.core :as json]))            

(def winning-prices (atom {}))

(def pending-responses (atom {})) ;; corr-id -> op

(defn bid [item-id]
  (fn [_ _]
    (let [
          ;; item-id (rand-int 10)
          current-price (get @winning-prices item-id 0)
          ;; increment (+ 1 (rand-int 5))]
          increment (- 2 (rand-int 5))
          value (if (> increment 0)
                     (+ current-price increment)
                     current-price)]
      {:type :invoke
       :f :bid
       :value value})))

(defn item [item-id]
  (fn [_ _]
    {:type :invoke
     :f    :item
     }))

(defn response-handler [test]
  (fn [req]
    (let [{:keys [corr-id item-id price succeeded]}
          (json/read-str (slurp (:body req)) :key-fn keyword)
          op (get @pending-responses corr-id)]
      (when op
        (swap! pending-responses dissoc corr-id)
        (client/invoke-complete!
          test
          (assoc op
            :type :ok
            :value (independent/tuple item-id {:id item-id :price price :succeeded succeeded}))))
      {:status 200 :body "ok"})))

(defn start-response-server! [test]
  (run-jetty (response-handler test) {:port 8082 :join? false :max-threads 4096}))

(defrecord Client [conn-invoke conn-response]
  client/Client
  (open! [this test node]
    (assoc this :conn-response (str "http://" node ":8082"))
    (assoc this :conn-invoke (str "http://" node ":8081") :winning-price 0))

  (setup! [this test]
    ;; (info "Waiting 30 seconds to allow HTTP server to bind to port 8081...")
    (Thread/sleep 30000)

    (start-response-server! test)

    (let [url (str "http://localhost:8081/health")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server health check response " (:body response)))
    
    (assoc this :conn-invoke "http://localhost:8081"))

  (invoke! [this test op]
    (let [url (:conn-invoke this)]
      (case (:f op)
      :bid
      (try
        (let [[id price] (:value op)
              full-url (str url "/bid")
              corrId (rand-int Integer/MAX_VALUE)
              params {:itemId id :price price :corrId corrId}]
          (let [response (http/post full-url {:form-params params :accept :json :as :text})
                  body (try
                        (json/parse-string (:body response) true)
                        (catch Exception e
                          (warn "Failed to parse bid response JSON: " (.getMessage e))
                          {:parse-error (.getMessage e)}))
                  {:keys [status]} body] 
            (if (= status "ACK")
              (do
                (swap! pending-responses assoc corr-id op) ;; only add if we saw ACK
                (assoc op :type :invoke)) ;; Jepsen sees this as an in-flight op
              (do
                (warn "No ACK received; skipping op tracking")
                (assoc op :type :fail :error :no-ack)))))
        (catch Exception e
          (warn "Failed to send bid: " (.getMessage e))
          (assoc op :type :fail :error :client-error)))

        :item
        (try
          (let [[id] (:value op)
                corr-id (rand-int Integer/MAX_VALUE)
                full-url (str url "/item")
                params {:itemId id}]
            (let [response (http/get full-url {:query-params params :accept :json :as :text})
                  body (try
                        (json/parse-string (:body response) true)
                        (catch Exception e
                          (warn "Failed to parse item response JSON: " (.getMessage e))
                          {:parse-error (.getMessage e)}))
                  {:keys [status]} body]
                (if (= status "ACK")
                  (do
                    (swap! pending-responses assoc corr-id op) ;; only add if we saw ACK
                    (assoc op :type :invoke)) ;; Jepsen sees this as an in-flight op
                  (do
                    (warn "No ACK received; skipping op tracking")
                    (assoc op :type :fail :error :no-ack)))))
          (catch Exception e
            (warn "Failed to send item: " (.getMessage e))
            (assoc op :type :fail :error :client-error))))))


  (teardown! [this test])  

  (close! [this test]))