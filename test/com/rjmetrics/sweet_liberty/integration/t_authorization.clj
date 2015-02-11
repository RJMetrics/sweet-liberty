(ns com.rjmetrics.sweet-liberty.integration.t-authorization
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.core :refer :all]
            [clojure.java.jdbc :as jdbc]
            [com.rjmetrics.sweet-liberty.integration.utils :as util]
            [conjure.core :as conjure :refer [stubbing]]
            [compojure.core :refer [defroutes routes POST GET ANY]]
            [ring.mock.request :refer :all]
            [ring.middleware.params :refer [wrap-params]]
            [com.rjmetrics.sweet-liberty.integration.utils :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.data.json :as json]))

(def db-spec (get-db-spec "testdb"))
(def table-structure {:attributes [:id
                                   :name
                                   :state]
                      :table-name :test_table
                      :primary-key :id})

(def default-options {:table table-structure
                      :db-spec db-spec})

(defn populate-rows
  [db-spec table-name]
  (apply jdbc/insert!
         db-spec
         table-name
         [["id" "name" "state"]
          [0 "test0" 0]
          [1 "test1" 1]
          [2 "test2" 1]
          [3 "test3" 0]
          [4 "test3" 0]]))

(initialize-db db-spec populate-rows)

(facts "about routes with authorizations"
       (let [test-app-w-permissions (wrap-params
         (routes
          (GET "/no-auth-specified" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config (dissoc default-liberator-hooks :authorized? :allowed?)}
                    (add-ok-handler :collection? false))))
          (GET "/nothing-allowed" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-ok-handler :collection? false)
                    (add-authorization (constantly false)))))
          (ANY "/get-yes-post-not" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-get)
                    (add-ok-handler :collection? false)
                    (add-post&handler)
                    (add-authorization {:get (constantly true)
                                        :post (constantly false)}))))
          (ANY "/any-no-post-yes" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-get)
                    (add-ok-handler :collection? false)
                    (add-post&handler)
                    (add-authorization {:any (constantly false)
                                        :post (constantly true)}))))
          (ANY "/any-no-get-yes" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-get)
                    (add-ok-handler :collection? false)
                    (add-post&handler)
                    (add-authorization {:any (constantly false)
                                        :get (constantly true)}))))
          (ANY "/dummy-yes" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-get)
                    (add-ok-handler :collection? false)
                    (add-post&handler)
                    (add-authorization {:dummy (constantly true)}))))
          (ANY "/query-params-fn" req
               (make-resource
                (-> {:options (assoc default-options
                                :result-set-transform-fn first)
                     :liberator-config default-liberator-hooks}
                    (add-get)
                    (add-ok-handler :collection? false)
                    (add-post&handler)
                    (add-authorization #(= "god" (-> % :request :query-params keywordize-keys :who))))))
          ))]
         (fact "no auth specified"
               (:status (test-app-w-permissions (request :get "/no-auth-specified")))
               => (throws Exception))
         (fact "a GET request fails when nothing is permitted"
               (:status (test-app-w-permissions (request :get "/nothing-allowed")))
               => 403)
         (fact "a GET request SUCCEEDS when GET is permitted, but POST is not"
               (:status (test-app-w-permissions (request :get "/get-yes-post-not")))
               => 200)
         (fact "a POST request FAILS when GET is permitted, but POST is not"
               (:status (test-app-w-permissions (-> (request :post "/get-yes-post-not")
                                                    (body {:name "nate" :state 1}))))
               => 403)
         (fact "a GET request FAILS when ANY is not permitted, but POST is permitted"
               (:status (test-app-w-permissions (request :get "/any-no-post-yes")))
               => 403)
         (fact "a POST request SUCCEEDS when ANY is not permitted, but POST is permitted"
               (:status (test-app-w-permissions (-> (request :post "/any-no-post-yes")
                                                    (body {:name "nate" :state 1}))))
               => 201
               (test-app-w-permissions (-> (request :post "/any-no-post-yes")
                                           (body {:name "nate" :state 1})))
               => {:body (json/write-str {:state 1
                                          :name "nate"
                                          :id 6})
                   :headers {"Content-Type" "application/json;charset=UTF-8"
                             "Vary" "Accept"}
                   :status 201})
         (fact "a GET request SUCCEEDS when ANY is permitted, but POST is not permitted"
               (:status (test-app-w-permissions (request :get "/any-no-get-yes")))
               => 200)
         (fact "a POST request FAILS when ANY is permitted, but POST is not permitted"
               (:status (test-app-w-permissions (-> (request :post "/any-no-get-yes")
                                                    (body {:name "nate" :state 1}))))
               => 403)
         (fact "a GET request FAILS when only DUMMY is permitted"
               (:status (test-app-w-permissions (request :get "/dummy-yes")))
               => 403)
         (fact "a GET request SUCCEEDS when correct query params are supplied"
               (:status (test-app-w-permissions (request :get "/query-params-fn?who=god")))
               => 200)
         (fact "a GET request FAILS when incorrect query params are supplied"
               (:status (test-app-w-permissions (request :get "/query-params-fn?who=mortal")))
               => 403)))

(drop-db db-spec)
