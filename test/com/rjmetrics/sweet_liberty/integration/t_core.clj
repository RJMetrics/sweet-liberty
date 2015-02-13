(ns com.rjmetrics.sweet-liberty.integration.t-core
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as jdbc]
            [honeysql.core :as h-sql]
            [ring.mock.request :refer :all]
            [compojure.core :refer [defroutes GET POST PUT DELETE ANY]]
            [clojure.data.json :as json]
            [ring.middleware.stacktrace :refer [wrap-stacktrace]]
            [ring.middleware.params :refer [wrap-params]]
            [com.rjmetrics.sweet-liberty.core :refer :all]
            [com.rjmetrics.sweet-liberty.integration.utils :refer :all]
            [com.rjmetrics.sweet-liberty.util :as util]
            [clojure.walk :refer [keywordize-keys]]
            [ring.middleware.format :refer [wrap-restful-format]]
            [clojure.set :refer [rename-keys]]))

(def db-spec (get-db-spec "testdb"))
(def table-structure {:attributes [:id
                                   :name
                                   :state
                                   :note]
                      :table-name :test-table
                      :primary-key :id
                      :indices [:name]})

(def default-options {:table table-structure
                      :return-exceptions? true
                      :db-spec db-spec})

(def default-sweet-lib-config {:options default-options
                               :liberator-config default-liberator-hooks})

(defn populate-rows
  [db-spec table-name]
  (apply jdbc/insert!
         db-spec
         table-name
         [["id" "name" "state" "note"]
          [0 "test0" 0 ""]
          [1 "test1" 1 ""]
          [2 "test2" 1 ""]
          [3 "test3" 0 ""]
          [4 "test3" 0 ""]]))

(initialize-db db-spec populate-rows)

(declare handler)

(defroutes test-app
  (GET "/exists" []
       (make-resource (-> default-sweet-lib-config
                          (add-exists))))
  (POST "/" req
        ((make-resource
          (-> default-sweet-lib-config
              (add-exists)
              (add-ok-handler :collection? true)))
         (transform-post-to-get req)))
  (GET "/" []
       (make-resource
        (-> default-sweet-lib-config
            (add-exists)
            (add-ok-handler :collection? true))))
  (GET "/throw-exception/application-handles" []
       (make-resource
        (-> default-sweet-lib-config
            (assoc-in [:options :controller]
                      (fn [_ _] (throw (Exception. "exception to be caught by application"))))
            (add-exists)
            (add-ok-handler :collection? true)
            (add-exception-handler #(identity {:application-handled true
                                               :message (-> % :exception .getMessage)})))))
  (GET "/throw-exception/default-handler" []
       (make-resource
        (-> default-sweet-lib-config
            (assoc-in [:options :controller]
                      (fn [_ _] (throw (Exception. "exception to be caught by application"))))
            (add-exists)
            (add-ok-handler :collection? true))))
  (GET "/throw-exception/default-handler/return-exceptions-false" []
       (make-resource
        (-> default-sweet-lib-config
            (assoc-in [:options :return-exceptions?] false)
            (assoc-in [:options :controller]
                      (fn [_ _] (throw (Exception. "exception to be hidden by sweet-lib"))))
            (add-exists)
            (add-ok-handler :collection? true))))
  (GET "/query-transforms" []
       (make-resource
        (-> {:options (assoc default-options
                             :query-transform (fn[data ctx]
                                                (rename-keys data {:etats :state})))
             :liberator-config default-liberator-hooks}
            add-exists
            (add-ok-handler :collection? true))))
  (GET "/name-transforms-query" []
       (make-resource
        (-> {:options (assoc default-options :name-transforms {:state :etats})
             :liberator-config default-liberator-hooks}
            add-exists
            (add-ok-handler :collection? true))))
  (GET "/:id" [id]
       (make-resource
        (-> {:options (assoc default-options
                             :url-params {:id id})
             :liberator-config default-liberator-hooks}
            (add-exists :id)
            (add-ok-handler :collection? false))))
  (GET "/name-transform/:id" [id]
       (make-resource
        (-> {:options (assoc default-options
                             :url-params {:di id}
                             :name-transforms {:id :di})
             :liberator-config default-liberator-hooks}
            (add-exists :di)
            (add-ok-handler :collection? false))))
  (GET "/diff-params/:some-id" [some-id]
       (make-resource
        (-> {:options (assoc default-options
                        :url-params {:id some-id})
             :liberator-config default-liberator-hooks}
            (add-exists :id)
            (add-ok-handler :collection? false))))
  (POST "/insert" []
        (make-resource
         (-> default-sweet-lib-config
             (add-post&handler))))
  (POST "/insert-input-transform" []
        (make-resource
         (-> default-sweet-lib-config
             (assoc-in [:options :input-transform]
                       (fn [data ctx]
                         (assoc data :state 1)))
             (add-post&handler))))

  (POST "/insert-name-transforms" []
       (make-resource
        (-> {:options (assoc default-options :name-transforms {:name :the-name})
             :liberator-config default-liberator-hooks}
            add-post
            add-created-handler)))

  (POST "/insert-with-conditions" []
        (make-resource
         (-> (assoc-in default-sweet-lib-config
                       [:options :conditions :create :before]
                       (fn [data ctx]
                         (handler (body (request :post "/insert")
                                        {:state 2 :name "before-cond" :note (pr-str data)}))
                         true))
             (assoc-in [:options :conditions :create :after]
                       (fn [data result-data ctx]
                         (handler (body (request :post "/insert")
                                        {:state 3 :name "after-cond" :note (pr-str data result-data)}))
                         true))
             (add-post&handler))))
  (POST "/insert-with-bad-conditions" []
        (make-resource
         (-> (assoc-in default-sweet-lib-config
                       [:options :conditions :create :before]
                       (fn [data ctx]
                         (:pre data)))
             (assoc-in [:options :conditions :create :after]
                       (fn [data result-data ctx]
                         (:post data)))
             (add-post&handler))))
  (DELETE "/delete/:id" [id]
          (make-resource
           (-> default-sweet-lib-config
               (add-delete :id)
               (add-exists :id)
               (add-ok-handler :collection? false))))
  (DELETE "/multidelete" []
          (make-resource
           (-> default-sweet-lib-config
               (add-delete :id))))
  (DELETE "/delete/prefailure/:id" [id]
          (make-resource
           (-> (assoc-in default-sweet-lib-config
                         [:options :conditions :delete :before]
                         (fn [data ctx]
                           false))
               (add-delete :id)
               (add-exists :id)
               (add-ok-handler :collection? false))))
  (DELETE "/delete-name-transform/:sid" [sid]
          (make-resource
           (-> {:options (assoc default-options
                           :name-transforms {:id :sid})
                :liberator-config default-liberator-hooks}
               (add-delete :sid)
               (add-exists :sid)
               (add-ok-handler :collection? false))))
  (GET "/exists/:id" [id]
       (make-resource (-> default-sweet-lib-config
                          (add-exists :id))))
  (PUT "/update/:id" [id]
       (make-resource (-> default-sweet-lib-config
                          (add-exists :id)
                          (add-put :id)
                          (add-ok-handler :collection? false))))
  (PUT "/update-different-id-name/:sid" [sid]
       (make-resource (-> {:options (assoc default-options
                                      :name-transforms {:id :sid})
                           :liberator-config default-liberator-hooks}
                          (add-exists :sid)
                          (add-put :sid)
                          (add-ok-handler :collection? false))))
  (PUT "/update-transform/:id" [id]
       (make-resource (-> (assoc-in default-sweet-lib-config
                                    [:options :input-transform]
                                    (fn [data ctx]
                                      (rename-keys data {:the-name :name})))
                          (add-exists :id)
                          (add-put :id)
                          (add-ok-handler :collection? false))))
  (PUT "/update-name-transforms/:id" []
       (make-resource
        (-> {:options (assoc default-options :name-transforms {:name :the-name})
             :liberator-config default-liberator-hooks}
            (add-exists :id)
            (add-put :id)
            (add-ok-handler :collection? false))))
  (PUT "/update/diff/:funky-id" [funky-id]
       (make-resource (-> (assoc-in default-sweet-lib-config
                                    [:options :url-params]
                                    {:id funky-id})
                          (add-exists :id)
                          (add-put :id)
                          (add-ok-handler :collection? false))))
  (PUT "/updates" request
       (make-resource (-> default-sweet-lib-config
                          (add-exists :id)
                          (add-put :id)
                          (add-ok-handler :collection? true))))
  (PUT "/update-append-original-name/:id" [id]
       (make-resource (-> default-sweet-lib-config
                          (assoc-in [:options :controller]
                                    (fn [data ctx]
                                      (assoc data
                                             :original-name
                                             (-> ctx
                                                 util/existing-entities-transformed-kw
                                                 first
                                                 :name))))
                          (add-exists :id)
                          (add-put :id)
                          (add-ok-handler :collection? false)))))

(def handler (-> test-app
                 (wrap-params)
                 (wrap-restful-format)))

(def default-headers {"Content-Type" "application/json;charset=UTF-8"
                      "Vary" "Accept"})

(fact "/diff-params should correctly map a different id"
      (let [result (handler (request :get "/diff-params/1"))]
        (:status result) => 200
        (json/read-str (:body result) :key-fn keyword) => {:state 1
                                                           :name "test1"
                                                           :note ""
                                                           :id 1}
        (:headers result) => default-headers))

(fact-group :get-requests "about fields and filters via GET"
       (fact "no fields and no filters should return all items where state = 1 (default)"
             (let [result (handler (request :get "/"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (count (json/read-str (:body result))) => 5))

       (fact "just the name field should return only name for all items"
             (let [result (handler (request :get "/?_fields=name"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (json/read-str (:body result)) => [{"name" "test0"}
                                                  {"name" "test1"}
                                                  {"name" "test2"}
                                                  {"name" "test3"}
                                                  {"name" "test3"}]))

       (fact "just the name and id fields should return only name for all items"
             (let [result (handler (request :get "/?_fields=name&_fields=id"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (json/read-str (:body result)) => [{"id" 0 "name" "test0"}
                                                  {"id" 1 "name" "test1"}
                                                  {"id" 2 "name" "test2"}
                                                  {"id" 3 "name" "test3"}
                                                  {"id" 4 "name" "test3"}]))

       (fact "filtering to items with state = 1 should return just 2 items"
             (let [result (handler (request :get "/?state=1"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (count (json/read-str (:body result))) => 2))

       (fact "query-transform works correctly: etats = 1 should return just 2 items"
             (let [result (handler (request :get "/query-transforms?etats=1"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (count (json/read-str (:body result))) => 2))

       (fact "name-transforms works correctly: etats = 1 should return 2 items withe etats as a property of the maps"
             (let [result (handler (request :get "/name-transforms-query?etats=1"))
                   parsed-body (json/read-str (:body result) :key-fn keyword)]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (count parsed-body) => 2
               parsed-body => (contains [(contains {:etats anything})])))

       (fact "getting a resource by id"
             (let [result (handler (request :get "/2"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (json/read-str (:body result)) => {"id" 2 "name" "test2" "note" "" "state" 1}))

       (fact :name-transforms "getting a resource by id name-transforms work"
             (let [result (handler (request :get "/name-transform/2"))]
               result => (just {:headers default-headers
                                :body #""
                                :status 200})
               (json/read-str (:body result)) => {"di" 2 "name" "test2" "note" "" "state" 1}))

       (fact "giving query params for columns that don't exist should return all rows"
             (count (json/read-str (:body (handler (request :get "/?asd=xyz"))))) => 5))

(facts "about fields and filters via POST"
       (fact "query endpoint returns just names"
             (let [result (handler (-> (request :post "/")
                                       (body {:_fields [:name]})))]
               result => (just {:headers default-headers
                                :status 200
                                :body #""})
               (json/read-str (:body result)) => [{"name" "test0"}
                                                  {"name" "test1"}
                                                  {"name" "test2"}
                                                  {"name" "test3"}
                                                  {"name" "test3"}]))

       (fact "query endpoint returns just names and ids"
             (let [result (handler (-> (request :post "/")
                                       (body {:_fields [:name :id]})))]
               result => (just {:headers default-headers
                                :status 200
                                :body #""})
               (json/read-str (:body result)) => [{"id" 0, "name" "test0"}
                                                  {"id" 1, "name" "test1"}
                                                  {"id" 2, "name" "test2"}
                                                  {"id" 3, "name" "test3"}
                                                  {"id" 4, "name" "test3"}]))

       (fact "query endpoint returns just items that have state = 1"
             (let [result (handler (-> (request :post "/")
                                       (body {:state 1})))]
               result => (just {:headers default-headers
                                :status 200
                                :body #""})
               (count (json/read-str (:body result))) => 2))

       (fact "query endpoint returns just items that have state = 1 and id = 2"
             (let [result (handler (-> (request :post "/")
                                       (body {:state 1 :id 2})))]
               result => (just {:headers default-headers
                                :status 200
                                :body #""})
               (count (json/read-str (:body result))) => 1))

       (fact "query endpoint returns just items that have state = 1 and id = 2 in json"
             (let [result (-> (request :post "/")
                              (assoc :body (util/to-json-input-stream {:state 1 :id 2}))
                              (content-type "application/json")
                              handler)]
               (:headers result) => default-headers
               (:status result) => 200
               (count (json/read-str (:body result))) => 1))

       (fact "query endpoint gets only name field and filters to only state = 1"
             (let [result (handler (-> (request :post "/")
                                       (body {:state 1 :_fields [:name]})))]
               result => (just {:headers default-headers
                                :status 200
                                :body #""})
               (json/read-str (:body result)) => [{"name" "test1"}
                                                  {"name" "test2"}]))

       (fact "a request that does not use the middleware will throw an exception"
             (test-app (-> (request :post "/")
                           (body {:state 1}))) => (throws #":form-params must be parsed")))

(facts "about single item post! and handle-created via post"
       (fact "insert and return entity with id properly set"
             (let [result (handler (-> (request :post "/insert")
                                       (body {:name "nate" :state 1})))]
               (json/read-str (:body result) :key-fn keyword)
               => {:id 5, :name "nate", :note nil :state 1}))
       (fact "insert and return entity with id properly set for json"
             (let [result (-> (request :post "/insert")
                              (content-type "application/json")
                              (assoc :body (util/to-json-input-stream {:name "nate2" :state 0}))
                              handler)]
               (json/read-str (:body result) :key-fn keyword)
               => {:id 6 :name "nate2" :note nil :state 0})))

(facts "about paging"
       (fact "a page request with only some page keys does not page"
             (-> (request :get "/?_page=0") handler :body (json/read-str :key-fn keyword) count) => 7
             (-> (request :get "/?_pagesize=20") handler :body (json/read-str :key-fn keyword) count) => 7
             (-> (request :get "/?_pagekey=id") handler :body (json/read-str :key-fn keyword) count) => 7)
       (fact "a page request with a key that is not a primary key or index fails"
             (let [response (handler (request :get "/?_page=0&_pagesize=20&_pagekey=state"))]
               response => (contains {:headers {"Content-Type" "application/json; charset=UTF-8"
                                                "Vary" "Accept"}
                                      :status 400})
               (json/read-str (:body response) :key-fn keyword)
               =>
               (contains {:exception-message "Column \":state\" is not defined as an index or primary key. If you want to ignore this constraint, add :ignore-index-constraint to your table configuration."
                          :code 3
                          :more-info ""
                          :stack-trace anything})))
       (fact "a page request with out of range returns empty array."
             (handler (request :get "/?_page=5&_pagesize=20&_pagekey=id"))
             => {:body "[]"
                 :headers default-headers
                 :status 200})
       (fact "a page request on ids works"
             (let [response (handler (request :get "/?_page=0&_pagesize=2&_pagekey=id"))]
               response
               => (just {:body anything
                   :headers default-headers
                   :status 200})
             (json/read-str (:body response) :key-fn keyword)
             => [{:note "" :state 0 :name "test0" :id 0}
                 {:note "" :state 1 :name "test1" :id 1}]))
       (fact "a page request at the end of ids works"
             (let [response (handler (request :get "/?_page=2&_pagesize=2&_pagekey=id"))]
               response
               => (just {:body anything
                             :headers default-headers
                             :status 200})
               (json/read-str (:body response) :key-fn keyword)
               => [{:note "" :state 0 :name "test3" :id 4}
                   {:note nil :state 1 :name "nate" :id 5}]))
       (fact "a page request on strings works"
             (let [response (handler (request :get "/?_page=0&_pagesize=2&_pagekey=name"))]
               response
               => (just {:body anything
                         :headers default-headers
                         :status 200})
               (json/read-str (:body response) :key-fn keyword)
               => [{:note nil :state 1 :name "nate" :id 5}
                   {:note nil :state 0 :name "nate2" :id 6}]))
       (fact "a page request with filters works"
             (let [response (handler (request :get "/?_page=0&_pagesize=2&_pagekey=name&name=test0&name=test1&name=test2&name=test3"))]
               response
               => (just {:body anything
                         :headers default-headers
                         :status 200})
               (json/read-str (:body response) :key-fn keyword)
               => [{:note "" :state 0 :name "test0" :id 0}
                   {:note "" :state 1 :name "test1" :id 1}]))
       (fact "paging on non-included fields works"
             (handler (request :get "/?_page=0&_pagesize=2&_pagekey=id&_fields=name"))
             => {:body (json/write-str [{:name "test0"}
                                        {:name "test1"}])
                 :headers default-headers
                 :status 200}))

(fact-group "about /insert-input-transform")  ;; contains no facts. Typo?
  (fact "input is always transformed"
        (let [body-data {:state 0 :name "nope"}
              result (-> (request :post "/insert-input-transform")
                         (body body-data)
                         (handler))]
          (json/read-str (:body result) :key-fn keyword)
          => {:id 7, :name "nope", :note nil :state 1}))

  (fact "input names are transformed correctly" :dev
        (let [body-data {:state 1 :the-name "nope"}
              result (-> (request :post "/insert-name-transforms")
                         (body body-data)
                         (handler))]
          (json/read-str (:body result) :key-fn keyword)
          => {:id 8, :the-name "nope", :note nil :state 1}))

(facts "about /insert-with-conditions"
       (fact "it will insert 3 items, one before, one during, one after"
             (let [result (-> (request :post "/insert-with-conditions")
                              (body {:state 1 :name "sandwiched"})
                              handler)
                   before-item (handler (request :get "/9"))
                   sandwich-item (handler (request :get "/10"))
                   after-item (handler (request :get "/11"))]
               (:status result) => 201
               (json/read-str (:body before-item) :key-fn keyword) => {:state 2 :name "before-cond" :note "{:name \"sandwiched\", :state \"1\"}" :id 9}
               (json/read-str (:body sandwich-item) :key-fn keyword) => {:note nil :state 1 :name "sandwiched" :id 10}
               (json/read-str (:body after-item) :key-fn keyword) => {:state 3
                                                                      :name "after-cond"
                                                                      :note "{:name \"sandwiched\", :state \"1\"} {:com.rjmetrics.sweet-liberty.core/post-id 10}"
                                                                      :id 11}))
       (fact "it will fail when a bad pre-condition is specified"
             (let [result (-> (request :post "/insert-with-bad-conditions")
                              (body {:state false :name true})
                              handler)]
               (json/read-str (:body result) :key-fn keyword)
               => (contains {:exception-message "POST Precondition failed"
                             :more-info ""
                             :stack-trace anything})
               (:status result) => 400
               (:headers result) => {"Content-Type" "application/json; charset=UTF-8"
                                     "Vary" "Accept"})))

(facts "about delete"
       (fact "inserting and then deleting an item should make the database look unaffected"
             (let [original (handler (request :get "/"))
                   insert-result (-> (request :post "/insert")
                                     (body {:name "nate" :state 1})
                                     handler)
                   new-id (-> (:body insert-result)
                              (json/read-str :key-fn keyword)
                              :id)
                   delete-result (handler (request :delete (str "/delete/" new-id)))
                   post-delete (handler (request :get "/"))]
               original => post-delete
               (:status delete-result) => 204
               (slurp (:body delete-result)) => "null"
               (:headers delete-result) => {"Content-Type" "application/json; charset=utf-8"
                                            "Content-Length" "4"}))
       (fact "delete's with name-transforms work"
             (let [original (handler (request :get "/"))
                   insert-result (-> (request :post "/insert")
                                     (body {:name "nate" :state 1})
                                     handler)
                   new-id (-> (:body insert-result)
                              (json/read-str :key-fn keyword)
                              :id)
                   delete-result (handler (request :delete (str "/delete-name-transform/" new-id)))
                   post-delete (handler (request :get "/"))]
               original => post-delete
               (:status delete-result) => 204
               (slurp (:body delete-result)) => "null"
               (:headers delete-result) => {"Content-Type" "application/json; charset=utf-8"
                                            "Content-Length" "4"}))
       (fact "deleting an item that doesn't exist gives a 404"
             (let [pre-delete (handler (request :get "/"))
                   result (handler (request :delete "/delete/255"))
                   post-delete (handler (request :get "/"))]
               (:status result) => 404
               pre-delete => post-delete))
       (fact "bulk delete works"
             (let [pre-delete (handler (request :get "/"))
                   first-id (-> (request :post "/insert")
                                (body {:name "first" :state 1})
                                handler
                                :body
                                (json/read-str :key-fn keyword)
                                :id)
                   second-id (-> (request :post "/insert")
                                 (body {:name "second" :state 1})
                                 handler
                                 :body
                                 (json/read-str :key-fn keyword)
                                 :id)
                   bulk-del-request (body (request :delete "/multidelete")
                                          {:id [first-id second-id]})
                   result (handler bulk-del-request)
                   post-delete (handler (request :get "/"))]
               (:status result) => 204
               pre-delete => post-delete))
       (fact "deleting an item with a failed pre-condition does not delete the item"
             (let [pre-delete (handler (request :get "/"))
                   result (handler (request :delete "/delete/prefailure/1"))
                   post-delete (handler (request :get "/"))]
               pre-delete => post-delete
               (json/read-str (:body result) :key-fn keyword)
               =>
               (contains {:exception-message "DELETE Precondition failed"
                          :more-info ""
                          :stack-trace anything})
               (:headers result) => {"Content-Type" "application/json; charset=UTF-8"
                                     "Vary" "Accept"}
               (:status result) => 400)))

(facts "about add-exists for a single entity"
       (fact "an entity with id = 2 exists"
             (let [result (handler (request :get "/exists/2"))]
               (:body result) => "OK"
               (:status result) => 200))
       (fact "an entity with id = 255 does not exist"
             (let [result (handler (request :get "/exists/255"))]
               (:status result) => 404)))

(facts "about add-exists for collections"
       (fact "gets all the entities for a get request"
             (let [result (handler (request :get "/exists"))]
               (:body result) => "OK"
               (:status result) => 200)))

(facts "about put"
       (fact "updating a single item works"
             (let [result (handler (body (request :put "/update/2")
                                         {:name "new-name!"}))]
               (json/read-str (:body result) :key-fn keyword)
               => {:note "" :state 1 :name "new-name!" :id 2}
               (:status result) => 200))
       (fact "updating a single item with a name-transform works"
             (let [result (handler (body (request :put "/update-different-id-name/2")
                                         {:name "new-name!"}))]
               (json/read-str (:body result) :key-fn keyword)
               => {:note "" :state 1 :name "new-name!" :sid 2}
               (:status result) => 200))
       (fact "updating a single item with a tranform works"
             (let [result (handler (body (request :put "/update-transform/2")
                                        {:the-name "transformed-worked!"}))]
               (json/read-str (:body result) :key-fn keyword)
               => {:note "" :state 1 :name "transformed-worked!" :id 2}))
       (fact "updating a single item with name tranforms works"
             (let [result (handler (body (request :put "/update-name-transforms/2")
                                        {:the-name "transformed-worked!"}))]
               (json/read-str (:body result) :key-fn keyword)
               => {:note "" :state 1 :the-name "transformed-worked!" :id 2}))
       (fact "updating a single item with url-params works"
             (let [result (handler (body (request :put "/update/diff/2")
                                         {:name "new-name-2!"}))]
               (json/read-str (:body result) :key-fn keyword)
               => {:id 2 :name "new-name-2!" :note "" :state 1}
               (:status result) => 200))
       (fact "updating a single item via json works"
             (let [result (-> (request :put "/update/2")
                              (assoc :body (util/to-json-input-stream
                                            {:name "other name!"}))
                              (content-type "application/json")
                              handler)]
               (json/read-str (:body result) :key-fn keyword)
               => {:id 2 :name "other name!" :note "" :state 1}
               (:status result) => 200))
       (fact "updating a single item and show that ::existing-entities-transformed works"
             (let [result (-> (request :put "/update-append-original-name/3")
                              (assoc :body (util/to-json-input-stream
                                            {:name "other name! 2"}))
                              (content-type "application/json")
                              handler)]
               (json/read-str (:body result) :key-fn keyword)
               => {:id 3, :name "other name! 2", :original-name "test3", :note "" :state 0}
               (:status result) => 200))
       (fact "updating multiple items works"
             (let [raw-request (-> (request :put "/updates")
                                   (assoc :body (util/to-json-input-stream
                                                 [{:id 3 :name "new-name-3"}
                                                  {:id 4 :name "new-name-4" :note "" :state 55}]))
                                   (content-type "application/json"))
                   result (handler raw-request)
                   new-items (handler (request :get "/?id=3&id=4"))]
               (json/read-str (:body result) :key-fn keyword)
               => [{:note "" :state 0 :name "new-name-3" :id 3}
                   {:note "" :state 55 :name "new-name-4" :id 4}]
               (json/read-str (:body new-items) :key-fn keyword)
               => [{:note "" :state 0 :name "new-name-3" :id 3}
                   {:note "" :state 55 :name "new-name-4" :id 4}]))
       (fact "updating an item that doesn't exist fails"
             (let [before (handler (request :get "/"))
                   result (handler (request :put "/update/99999999"))
                   after (handler (request :get "/"))]
               (:body result) => "Not implemented."
               (:status result) => 501
               before => after))
       (fact "updating items that don't exist fails"
             (let [before '(handler (request :get "/"))
                   result (handler (-> (request :put "/updates")
                                       (assoc :body (util/to-json-input-stream
                                                     [{:id 999 :name "shouldnthappen"}
                                                      {:id 998 :name "wrong"}]))
                                       (content-type "application/json")))
                   after '(handler (request :get "/"))]
               (:body result) => "Not implemented."
               before => after)))

(facts "about handle-exception"
       (fact "application exception handler recieves exception"
            (let [result (handler (request :get "/throw-exception/application-handles"))]
               (:status result) => 500
               (-> result
                   :body
                   (json/read-str :key-fn keyword)
                   ((juxt :application-handled :message)))
               => [ true "exception to be caught by application"]))
       (fact "default handler recieves exception and returns expected response"
            (let [result (handler (request :get "/throw-exception/default-handler"))]
               (:status result) => 500
               (-> result
                   :body
                   (json/read-str :key-fn keyword)
                   :exception-message)
               => "exception to be caught by application"))
       (fact "default handler recieves exception and returns expected response"
            (let [result (handler (request :get "/throw-exception/default-handler/return-exceptions-false"))]
               (:status result) => 500
               (-> result
                   :body
                   (json/read-str :key-fn keyword)
                   :exception-message)
               => "There was an error processing your request.")))

(drop-db db-spec)
