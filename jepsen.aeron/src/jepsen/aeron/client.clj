(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [cheshire.core :as json]))

(def winning-prices (atom {}))

(defn bid []
  (fn [_ _]
    (let [item-id (+ 1 (rand-int 10))
          current-price (get @winning-prices item-id 0)
          increment (+ 1 (rand-int 10))]
      {:type :invoke
       :f :bid
       :value {:id item-id
               :price (+ current-price increment)}})))

(defn item []
  (fn [_ _]
    {:type :invoke
     :f    :item
     :value {
        :id (+ 1 (rand-int 10))}}))

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
    
    (assoc this :conn "http://localhost:8081"))

  (invoke! [this test op]
    (let [url (:conn this)]
      (case (:f op)
      :bid
      (try
        (let [{:keys [id price]} (:value op)
              full-url (str url "/bid")
              params {:itemId id :price price}]
          (let [response (http/post full-url {:form-params params :accept :json :as :text})
                body (try
                      (json/parse-string (:body response) true)
                      (catch Exception e
                        (warn "Failed to parse bid response JSON: " (.getMessage e))
                        {:parse-error (.getMessage e)}))
                {:keys [itemId price success]} body]
            (do
              (swap! winning-prices assoc itemId price)
              (assoc op :type :ok
                    :value {:id itemId :price price :succeeded success}))))
        (catch clojure.lang.ExceptionInfo e
          (let [data (ex-data e)
                status (:status data)]
            (if (= status 504)
              (assoc op :type :fail :error :timeout)
              (do (warn "Failed to place bid:" (.getMessage e))
                  (assoc op :type :fail :error :unknown)))))
        (catch Exception e
          (do
            (warn "Exception caught placing bid: " (.getMessage e))
            (if (instance? clojure.lang.ExceptionInfo e)
              (let [data (ex-data e)
                    status (:status data)]
                (if (= status 504)
                  (assoc op :type :fail :error :timeout)
                  (assoc op :type :fail :error :unknown)))
              (assoc op :type :fail :error :unknown)))))

        :item
        (try
          (let [{:keys [id]} (:value op)
                full-url (str url "/item")
                params {:itemId id}]
            (let [response (http/get full-url {:query-params params :accept :json :as :text})
                  body (try
                        (json/parse-string (:body response) true)
                        (catch Exception e
                          (warn "Failed to parse item response JSON: " (.getMessage e))
                          {:parse-error (.getMessage e)}))
                  {:keys [itemId price success]} body]
              (do
                (swap! winning-prices assoc itemId price)
                (assoc op :type :ok :value {:id itemId :price price :succeeded success}))))
          (catch clojure.lang.ExceptionInfo e
            (let [data (ex-data e)
                  status (:status data)]
              (if (= status 504)
                (assoc op :type :fail :error :timeout)
                (do (warn "Failed to query item:" (.getMessage e))
                    (assoc op :type :fail :error :unknown)))))
          (catch Exception e
            (do
              (warn "Unknown failure querying item:" (.getMessage e))
              (if (instance? clojure.lang.ExceptionInfo e)
                (let [data (ex-data e)
                      status (:status data)]
                  (if (= status 504)
                    (assoc op :type :fail :error :timeout)
                    (assoc op :type :fail :error :unknown)))
                (assoc op :type :fail :error :unknown))))))))

  (teardown! [this test])  

  (close! [this test]))