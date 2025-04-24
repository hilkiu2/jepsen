(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [cheshire.core :as json]))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (str "http://" node ":8081")))

  (setup! [this test]
    ;; (info "Waiting 30 seconds to allow HTTP server to bind to port 8081...")
    (Thread/sleep 30000)

    (let [url (str "http://localhost:8081/health")
          response (http/get url {:throw-exceptions false})]
      (info "HTTP server health check response " (:body response)))

    ;; (let [url (str "http://localhost:8081/status")
    ;;       response (http/get url {:throw-exceptions false})]
    ;;   (info "HTTP server status response " (:body response)))
    
    (assoc this :conn "http://localhost:8081"))

  (invoke! [this test op]
    (let [url (:conn this)]
      (case (:f op)
        :put
        (try
          (let [{:keys [key value]} (:value op)
                full-url (str url "/kv/put")
                params {:key key :value value}]
            (let [response (http/post full-url {:form-params params :accept :json :as :text})
                  body (json/parse-string (:body response) true)]
              (assoc op :type :ok :value body)))
          (catch Exception e
            (warn "PUT failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

        :get
        (try
          (let [{:keys [key]} (:value op)
                full-url (str url "/kv/get")
                params {:key key}
                response (http/post full-url {:form-params params :accept :json :as :text})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value {:key key :value (:value body)}))
          (catch Exception e
            (warn "GET failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

      :delete
        (try
          (let [{:keys [key]} (:value op)
                full-url (str url "/kv/delete")
                params {:key key}
                response (http/post full-url {:form-params params :accept :json :as :text})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value body))
          (catch Exception e
            (warn "DELETE failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

      :cas
        (try
          (let [{:keys [key expected new]} (:value op)
                full-url (str url "/kv/cas")
                params {:key key :expected expected :new new}
                response (http/post full-url {:form-params params :accept :json :as :text})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value {:succeeded (:succeeded body)}))
          (catch Exception e
            (warn "CAS failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

      ;; fallback
      (assoc op :type :fail :error "Unknown operation"))))

  (teardown! [this test])
    ;; (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  

  (close! [this test]))
    ;; (info "Closing HTTP server")
    ;;   (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  
