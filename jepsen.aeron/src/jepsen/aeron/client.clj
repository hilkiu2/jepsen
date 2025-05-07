(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [jepsen.independent :as independent]
              [cheshire.core :as json]))            

(def winning-prices (atom {}))

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
    ;;  :value [item-id]
     }))

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
        (let [[id price] (:value op)
              full-url (str url "/bid")
              params {:itemId id :price price}]
          (let [response (http/post full-url {:form-params params :accept :json :as :text})
                body (try
                      (json/parse-string (:body response) true)
                      (catch Exception e
                        (warn "Failed to parse bid response JSON: " (.getMessage e))
                        {:parse-error (.getMessage e)}))
                {:keys [itemId price success]} body]
            (swap! winning-prices assoc itemId price)
            (assoc op :type :ok :value (independent/tuple itemId {:id itemId :price price :succeeded success}))))
        (catch Exception e
          (warn "Exception caught placing bid: " (.getMessage e))
          (let [data (ex-data e)
                msg-str (.getMessage e)
                parsed-msg (try
                     (:message (json/parse-string msg-str true))
                     (catch Exception _ nil))]
            (cond
              (= (:status data) 504)
              (assoc op :type :fail :error :timeout)

              (some? data)
              (assoc op :type :fail :error :unknown :message parsed-msg)

              :else
              (assoc op :type :fail :error :unknown :message parsed-msg)))))


        :item
        (try
          (let [[id] (:value op)
                full-url (str url "/item")
                params {:itemId id}]
            (let [response (http/get full-url {:query-params params :accept :json :as :text})
                  body (try
                        (json/parse-string (:body response) true)
                        (catch Exception e
                          (warn "Failed to parse item response JSON: " (.getMessage e))
                          {:parse-error (.getMessage e)}))
                  {:keys [itemId price success]} body]
              (swap! winning-prices assoc itemId price)
              (assoc op :type :ok :value (independent/tuple itemId {:id itemId :price price :succeeded success}))))
          (catch Exception e
            (warn "Exception caught querying item: " (.getMessage e))
            (let [data (ex-data e)
                msg-str (.getMessage e)
                parsed-msg (try
                     (:message (json/parse-string msg-str true))
                     (catch Exception _ nil))]
            (cond
              (= (:status data) 504)
              (assoc op :type :fail :error :timeout)

              (some? data)
              (assoc op :type :fail :error :unknown :message parsed-msg)

              :else
              (assoc op :type :fail :error :unknown :message parsed-msg))))))))


  (teardown! [this test])  

  (close! [this test]))