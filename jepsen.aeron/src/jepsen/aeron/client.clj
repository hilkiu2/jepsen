(ns jepsen.aeron.client 
    (:require [clojure.tools.logging :refer :all]
              [clj-http.client :as http]
              [jepsen.client :as client]
              [cheshire.core :as json]))

(defn put [_ _]
  {:type :invoke
   :f    :put
   :value {:key   (rand-int 5)
           :value (rand-int 200)}})

(defn get [_ _]
  {:type :invoke
   :f    :get
   :value {:key (rand-int 5)}})

(defn delete [_ _]
  {:type :invoke
   :f    :delete
   :value {:key (rand-int 5)}})

(defn cas [_ _]
  (let [k        (rand-int 5)
        expected (rand-int 200)
        new-val  (rand-int 200)]
    {:type :invoke
     :f    :cas
     :value {:key k :expected expected :new new-val}}))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (str "http://" node ":8081")))

  (setup! [this test])

  (invoke! [this test op]
    (let [url (:conn this)]
      (case (:f op)
        :put
        (try
          (let [{:keys [key value]} (:value op)
                full-url (str url "/kv/put")
                params {:key (long key) :value (long value)}
                response (http/post full-url {:form-params params :accept :json :as :text :throw-exceptions false})
                raw-body (:body response)]
            (let [body (json/parse-string raw-body true)] 
              (assoc op :type :ok :value {:key (long key) :value (long value) :succeeded (:success body)})))
          (catch Exception e
            (warn "PUT failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

        :get
        (try
          (let [{:keys [key]} (:value op)
                full-url (str url "/kv/get")
                params {:key (long key)}
                response (http/post full-url {:form-params params :accept :json :as :text :throw-exceptions false})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value {:key (long key) :value (long (:value body))}))
          (catch Exception e
            (warn "GET failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

      :delete
        (try
          (let [{:keys [key]} (:value op)
                full-url (str url "/kv/delete")
                params {:key (long key)}
                response (http/post full-url {:form-params params :accept :json :as :text :throw-exceptions false})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value {:key (long key) :succeeded (:success body)}))
          (catch Exception e
            (warn "DELETE failed:" (.getMessage e))
            (assoc op :type :fail :error (.getMessage e))))

      :cas
        (try
          (let [{:keys [key expected new]} (:value op)
                full-url (str url "/kv/cas")
                params {:key (long key) :expected (long expected) :new (long new)}
                response (http/post full-url {:form-params params :accept :json :as :text :throw-exceptions false})
                body (json/parse-string (:body response) true)]
            (assoc op :type :ok :value {:key (long key) :expected (long expected) :new (long new) :succeeded (:success body)}))
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
  
