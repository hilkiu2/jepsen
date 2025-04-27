(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [cheshire.core :as json]))

(def winning-price (atom 0))

(defn bid []
  (fn [_ _]
    (let [current-winning-price @winning-price]
      {:type :invoke
       :f :bid
       :value {:id (+ 1 (rand-int 50))
               :price (+ current-winning-price (rand-int 10))}})))

(defn status []
  (fn [_ _]
    {:type :invoke
     :f    :status
     :value nil}))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (str "http://" node ":8081") :winning-price 0))

  (setup! [this test]
    ;; (info "Waiting 30 seconds to allow HTTP server to bind to port 8081...")
    (Thread/sleep 30000)

    (let [url (str "http://localhost:8081/health")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server health check response " (:body response)))

    (let [url (str "http://localhost:8081/status")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server status response " (:body response)))
    
    (assoc this :conn "http://localhost:8081"))

  (invoke! [this test op]
    (let [url (:conn this)]
      (case (:f op)
      :bid
      (try
        (let [{:keys [id price]} (:value op)
              full-url (str url "/bid")
              params {:customerId id :price price}]
          (let [response (http/post full-url {:form-params params :accept :json :as :text})
                body (try
                      (json/parse-string (:body response) true)
                      (catch Exception e
                        (warn "Failed to parse bid response JSON: " (.getMessage e))
                        {:parse-error (.getMessage e)}))
                bid-succeed (:bidSucceed body)]
            (assoc op :type :ok
                  :value {:id id :price price :succeeded bid-succeed})))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                status (:status data)]
            (if (= status 504)
              (assoc op :type :fail :error :timeout)
              (do (warn "Failed to place bid:" (.getMessage e))
                  (assoc op :type :fail :error :unknown)))))
        (catch Exception e
          (warn "Unknown failure placing bid:" (.getMessage e))
          (assoc op :type :fail :error :unknown)))

        :status
        (try
          (let [full-url (str url "/status")
                response (http/get full-url {:accept :json :as :text})
                body (try
                      (json/parse-string (:body response) true)
                      (catch Exception e
                        (warn "Failed to parse status JSON: " (.getMessage e))
                        {:parse-error (.getMessage e)}))
                {:keys [winningCustomerId winningPrice]} body]
            (reset! winning-price winningPrice)
            (assoc op :type :ok :value {:id winningCustomerId :price winningPrice}))
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)
                  status (:status data)]
              (if (= status 504)
                (assoc op :type :fail :error :timeout)
                (do (warn "Failed to place bid:" (.getMessage e))
                    (assoc op :type :fail :error :unknown)))))
          (catch Exception e
            (warn "Unknown failure placing bid:" (.getMessage e))
            (assoc op :type :fail :error :unknown))))))

  (teardown! [this test])  

  (close! [this test]))