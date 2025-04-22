(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [cheshire.core :as json]))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (str "http://" node ":8080")))

  (setup! [this test]
    ;; (info "Waiting 30 seconds to allow HTTP server to bind to port 8080...")
    (Thread/sleep 30000)

    (let [url (str "http://localhost:8080/health")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server health check response " (:body response)))

    (let [url (str "http://localhost:8080/status")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server status response " (:body response)))
    
    (assoc this :conn "http://localhost:8080"))

  (invoke! [this test op]
    (let [url (:conn this)]
      (case (:f op)
        :bid
        (try
          (let [{:keys [id price succeeded]} (:value op)
                full-url (str url "/bid")
                params {:customerId id :price price}]
            ;; (info "Placing bid on auction:" id "with price:" price "at" full-url)
            (let [response (http/post full-url {:form-params params
                                                :accept :json
                                                :as :text})
                  ;; _ (info "RAW JSON:" (:body response))     
                  body (try
                        (json/parse-string (:body response) true)
                        (catch Exception e
                          (warn "Failed to parse bid response JSON: " (.getMessage e))
                          {:parse-error (.getMessage e)}))
                  bid-succeed (:bidSucceed body)]
              ;; (info "Sent bid request with" params)
              ;; (info "Response body parsed as:" body)
              (assoc op :type :ok
                    :value {:id id :price price :succeeded bid-succeed})))
          (catch Exception e
            (let [ex (ex-data e)
                  raw-body (:body ex)]
              (warn "Failed to place bid: " (.getMessage e))
              (when raw-body (warn "Raw error body: " raw-body))
              (assoc op :type :fail :error (or raw-body (.getMessage e))))))

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
            ;; (info "Received status:" body)
            (assoc op :type :ok :value {:id winningCustomerId :price winningPrice}))
          (catch Exception e
            (let [ex (ex-data e)
                  raw-body (:body ex)]
              (warn "Failed to get status:" (.getMessage e))
              (when raw-body (warn "Raw error body:" raw-body))
              (assoc op :type :fail :error (or raw-body (.getMessage e)))))))))


  (teardown! [this test])
    ;; (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  

  (close! [this test]))
    ;; (info "Closing HTTP server")
    ;;   (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  
