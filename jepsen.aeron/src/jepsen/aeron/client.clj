(ns jepsen.aeron.client
  (:require [clojure.tools.logging :refer :all]
            [clj-http.client :as http]
            [jepsen.client :as client]))

(def auction-ids (atom []))

(defn create [_ _]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! auction-ids conj id)
    {:type :invoke, :f :create, :value id}))

(defn bid [_ _]
  (let [auctions @auction-ids]
    (when (seq auctions)
      {:type :invoke, :f :bid, :value [(rand-nth auctions) (rand-int 100)]})))

(defn close [_ _]
  (let [auctions @auction-ids]
    (when (seq auctions)
      {:type :invoke, :f :close, :value (rand-nth auctions)})))

(defrecord Client [conn]
  client/Client
  (open! [this test node]
    (assoc this :conn (str "http://" node ":8080")))

  (setup! [this test]
    ;; (info "Waiting 30 seconds to allow HTTP server to bind to port 8080...")
    (Thread/sleep 30000)

    (let [url (str "http://localhost:8080/health")
          response (http/get url {:throw-exceptions false})]
      ;; (info "HTTP server health check response " (:body response))
    )

    (assoc this :conn "http://localhost:8080"))

  (invoke! [this test op]
    (let [url (:conn this)] 
      (case (:f op)
        :create
        (try
          (let [item (:value op)
                full-url (str url "/auction")]
            ;; (info "Creating auction with item:" item "at" full-url)
            (let [response (http/post full-url {:form-params {:item item}})]
              ;; (info "Create response:" response)
              (assoc op :type :ok :value item)))
          (catch Exception e
            (let [body (:body (ex-data e))]
              (warn "Failed to create auction:" (.getMessage e))
              (when body (warn "Response body:" body))
              (assoc op :type :fail :error (or body (.getMessage e))))))

        :bid
        (try
          (let [[id amount] (:value op)
                full-url (str url "/auction/bid")]
            ;; (info "Placing bid on auction:" id "with amount:" amount "at" full-url)
            (let [response (http/post full-url {:form-params {:id id :amount amount}})]
              ;; (info "Bid response:" (:body response))
              (assoc op :type :ok :value [id amount])))
         (catch Exception e
          (let [body (:body (ex-data e))]
            (warn "Failed to place bid:" (.getMessage e))
            (when body (warn "Response body:" body))
            (assoc op :type :fail :error (or body (.getMessage e))))))

        :close
        (try
          (let [id (:value op)
                full-url (str url "/auction/close")]
            ;; (info "Closing auction with id:" id "at" full-url)
            (let [response (http/post full-url {:form-params {:id id}})]
              ;; (info "Close response:" (:body response))
              (assoc op :type :ok :value id)))
          (catch Exception e
            (let [body (:body (ex-data e))]
              (warn "Failed to close auction:" (.getMessage e))
              (when body (warn "Response body:" body))
              (assoc op :type :fail :error (or body (.getMessage e)))))))))

  (teardown! [this test]
    ;; (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  )

  (close! [this test]
    ;; (info "Closing HTTP server")
    ;;   (ignore-errors (c/su (c/exec :pkill :-f "AuctionHttpServer")))
  )
)