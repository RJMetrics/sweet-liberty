(ns com.rjmetrics.sweet-liberty.integration.t-postprocessing
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.core :refer :all]
            [compojure.core :refer [defroutes GET]]
            [clojure.java.jdbc :as j]
            [conjure.core :as conjure]
            [ring.mock.request :refer :all]
            [clojure.data.json :as json]
            [ring.middleware.params :refer [wrap-params]]
            [com.rjmetrics.sweet-liberty.integration.utils :refer :all]))

(def test-db-spec (get-db-spec "postprocessing"))
(def default-options {:table default-table-structure
                      :return-exceptions? true
                      :db-spec test-db-spec})
(initialize-db test-db-spec)

(defroutes test-app
  (GET "/default-transform" []
       (make-resource
        (-> {:options default-options
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/passthrough-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             (fn [data ctx] data))
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/context-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             (fn [data ctx] ctx))
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/nil-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             (fn [ctx result] nil))
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/empty-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             (fn [ctx result] ""))
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/state-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             (fn [data ctx] (map #(assoc %1 :state 5) data)))
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true))))

  (GET "/value-transform" []
       (make-resource
        (-> {:options (assoc default-options
                             :controller
                             5)
             :liberator-config default-liberator-hooks}
            (add-exists)
            (add-ok-handler :collection? true)))))

(def default-response-headers {"Content-Type" "application/json;charset=UTF-8"
                                "Vary" "Accept"})

(def handler (-> test-app
                 wrap-params))

;; Unfortunately we need to double-parse the json here since it reformats it a bit
(def all-rows-as-json (json/read-str (json/write-str (get-all-rows test-db-spec))))

(facts "about post processing"
       (fact "when no transform is given, it should act as a pass-through function"
             (let [result (handler (request :get "/default-transform"))]
               result => (just {:status 200
                                :headers default-response-headers
                                :body #""})
               (json/read-str (:body result)) => all-rows-as-json))

       (fact "when a passthrough transform is supplied, all rows should be returned"
             (let [result (handler (request :get "/passthrough-transform"))]
               result => (just {:status 200
                                :headers default-response-headers
                                :body #""})
               (json/read-str (:body result)) => all-rows-as-json))

       (fact "when a context transform is applied, a 500 is returned"
             (let [response (handler (request :get "/context-transform"))]
               response => (contains {:headers {"Content-Type" "application/json;charset=UTF-8", "Vary" "Accept"}
                                      :status 500})
               (:body response) => (contains "\"exception-message\":\"Don't know how to write JSON of class clojure.core$constantly$")))

       (fact "when a nil transform is applied, the body should be empty"
             (handler (request :get "/nil-transform")) => {:status 200
                                                            :headers (assoc
                                                                      default-response-headers
                                                                      "Content-Type"
                                                                      "application/json")
                                                            :body ""})

       (fact "when an empty transform is applied, an empty string should be returned with the default headers"
             (handler (request :get "/empty-transform")) => {:status 200
                                                              :headers default-response-headers
                                                              :body ""})

       (fact "when all states are toggled by a map transform, they should all be 5"
             (let [result (handler (request :get "/state-transform"))]
               result => (just {:status 200
                                :headers default-response-headers
                                :body #""})
               (json/read-str (:body result)) => (json/read-str (json/write-str (map #(assoc % :state 5) (get-all-rows test-db-spec))))))

       (fact "when a value is given as a transformation function, it should fail"
             (let [response (handler (request :get "/value-transform"))]
               response => (contains {:headers {"Content-Type" "application/json;charset=UTF-8"
                                                "Vary" "Accept"}
                                      :status 500})
               (:body response) => (contains "\"exception-message\":\"java.lang.Long cannot be cast to clojure.lang.IFn\""))))

(drop-db test-db-spec)
