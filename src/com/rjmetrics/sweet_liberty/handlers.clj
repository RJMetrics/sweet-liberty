(ns com.rjmetrics.sweet-liberty.handlers
  (:require [clojure.walk :refer [keywordize-keys]]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [com.rjmetrics.sweet-liberty.util :as util]
            [com.rjmetrics.sweet-liberty.expansion :as expansion]
            [com.rjmetrics.sweet-liberty.db :as db]
            [com.rjmetrics.sweet-liberty.process :as p]
            [com.rjmetrics.sweet-liberty.request-interpretation :as req]
            [clojure.set :refer [rename-keys
                                 map-invert]]))

(defn- log-request-from-context
  [ctx]
  (let [mdc (into {} (org.apache.log4j.MDC/getContext))
        log-structure (-> ctx
                          (select-keys [:request])
                          (merge mdc))]
    (log/debug (json/write-str log-structure :value-fn util/json-value-fn))))

(defn- make-special-query-param-processor-fn
  "Make function to apply expansion and grouping response processing."
  [ctx service-broker table]
  (fn [result-data]
    (let [request (:request ctx)]
      (->> result-data
           (expansion/expand-all-resources service-broker
                                           (:route-params request)
                                           (req/extract-expand-query-param request)
                                           table
                                           (:headers request))))))

(defn- run-with-conditions
  "Runs preconditions, executor and postconditions in appropriate order.
  Throws exception if condition fails."
  [data conditions ctx executor]
  (log-request-from-context ctx)
  (if ((:before conditions (constantly true)) data ctx)
    (let [result (executor)]
      (if ((:after conditions (constantly true)) data result ctx)
        result
        (throw (ex-info (str (req/raw-request-method ctx) " Postcondition failed")
                        {:is-sweet-lib-exception? true
                         :more-info ""}))))
    (throw (ex-info (str (req/raw-request-method ctx) " Precondition failed")
                    {:is-sweet-lib-exception? true
                     :more-info ""}))))

(defn make-allowed?-handler-fn
  "Create a handler for the 'allowed?' liberator hook based on permissions
  supplied. Permissions can either be a function or a map with http methods as
  keys and respective permission functions as values."
  [permissions]
  (fn [ctx]
    ((cond
       (fn? permissions) permissions
       (map? permissions)  (get permissions
                                (-> ctx :request :request-method)
                                (get permissions :any (constantly false)))) ctx)))

(defn make-entities-exist?-fn
  "Check to see if any entities exist for the given database, table and the
  query-params of the request object. Existing entities are attached to the ctx
  with the ::entities keyword"
  [table db-spec url-params name-transforms query-transform output-transform]
  (fn [{{:keys [query-params]} :request :as ctx}]
    (log-request-from-context ctx)
    (let [keyworded-input (keywordize-keys (merge query-params url-params))
          where-map (p/resource->db ctx
                                  keyworded-input
                                  :key-rename-map name-transforms
                                  :values-transform-fn query-transform)
          entities (db/get-records-from-storage db-spec
                                             table
                                             where-map)
          entities-transformed (p/db->resource ctx
                                             entities
                                             :key-rename-map name-transforms
                                             :values-transform-fn output-transform)]
      [true {util/entities-kw entities
             util/existing-entities-transformed-kw entities-transformed}])))

(defn- entity-exists-where-map
  "If we aren't doing a PUT (normal), just get a single entity from the route
  params. Otherwise extract the keys from the body for a bulk put based on
  column-name"
  [column-name url-params {{:keys [body-params form-params route-params body request-method]} :request :as ctx}]
  (let [params (or body-params form-params)]
    (hash-map column-name
              (if (and (= :put request-method) (seq params) (not (map? params)))
                (vec (map column-name (map keywordize-keys params)))
                (or (get url-params column-name) (get route-params column-name))))))

(defn make-entity-exists?-fn
  "Check to see if an entity exists for the given database, table and
  route-params of the request object. If an entity is found it is attached to
  the ctx object with the ::entities keyword."
  [table db-spec url-params column-name name-transforms query-transform output-transform]
  (fn [{{:keys [route-params]} :request :as ctx}]
    (log-request-from-context ctx)
    (let [where (entity-exists-where-map column-name url-params ctx)
          transformed-where (p/resource->db ctx
                                          where
                                          :key-rename-map name-transforms
                                          :values-transform-fn query-transform)
          results (seq (db/get-records-from-storage db-spec
                                                 table
                                                 transformed-where))
          results-transformed (p/db->resource ctx
                                            results
                                            :key-rename-map name-transforms
                                            :values-transform-fn output-transform)]
      (if results
        [true {util/entities-kw results
               util/existing-entities-transformed-kw results-transformed}]
        false))))

(defn make-read-created-entity-fn
  "Create a function that reads the new entity from storage and attaches it to
  the liberator context map at ::entities."
  [db-spec table-struct name-transforms output-transform controller service-broker]
  (fn [ctx]
    (log-request-from-context ctx)
    (let [created-entity (db/get-records-from-storage db-spec
                                                   table-struct
                                                   (hash-map (:primary-key table-struct)
                                                             (get ctx util/post-id-kw)))]
      (-> ctx
          (assoc util/entities-kw created-entity)
          (p/process-data-response name-transforms
                                 output-transform
                                 (or controller util/identity-transform-fn)
                                 false)))))

(defn make-create-entity-fn
  "Create a function that creates a new entity in storage and returns the new
  primary key value on the ctx at ::post-id."
  [table db-spec name-transforms input-transform _ conditions]
  (fn [{{form-params :form-params body-params :body-params} :request :as ctx}]
    (let [data (keywordize-keys (or body-params form-params))
          transformed-data (p/resource->db ctx
                                         data
                                         :key-rename-map name-transforms
                                         :values-transform-fn input-transform)]
      (run-with-conditions data
                           conditions
                           ctx
                           #(db/insert-entity-into-storage table db-spec transformed-data)))))

(defn make-read-entities-fn
  "Create a function that returns a entity collection after running the given
  result-set-transform-fn on it."
  [db-spec table-struct name-transforms output-transform controller collection? service-broker]
  (fn [ctx]
    (p/process-data-response ctx
                           name-transforms
                           output-transform
                           controller
                           collection?
                           :inject-processing (make-special-query-param-processor-fn ctx service-broker table-struct))))



(defn make-update-entity-fn
  [table db-spec column-name name-transforms query-transform
   input-transform output-transform url-params conditions]
  (fn [{:keys [request] :as ctx}]
    ;; Form params only exist if the body is of content type form-urlencoded
    ;; Body params only exist if it's json or otherwise.
    ;; Url formencoded CANNOT be bulk, only single updates.
    ;; Examples - Route | Body
    ;; /update/5 | name=new-name
    ;; /update/5 | {"name": "new-name"}
    ;; /update   | [{"name": "new-name", "id": 5} {"name": "other", "id": 6}]
    (let [data (req/extract-merged-body-params request url-params)
          transformed-params (p/resource->db ctx
                                           (req/extract-merged-query-params request url-params)
                                           :key-rename-map name-transforms
                                           :values-transform-fn query-transform)
          transformed-column-name (get (map-invert name-transforms)
                                       column-name
                                       column-name)]
      (->> (p/resource->db ctx
                         data
                         :key-rename-map name-transforms
                         :values-transform-fn input-transform)
           (partial db/update-entity-in-storage
                    transformed-column-name
                    (keywordize-keys transformed-params)
                    ctx
                    table
                    db-spec)
           (run-with-conditions data
                                conditions
                                ctx)))))

(defn make-delete-entity-fn
  "Make a handler function for deleting one or more entities from storage."
  [table db-spec column-name name-transforms conditions]
  ;; :params is used here since otherwise we'd merge all parameters together anyway
  ;; E.g. DELETE /item/5, /items?id=5&id=6, /items  id=5&id=6, /items {"id":[5, 6]}
  ;; These would parse to route params, query params, form params, and body params.
  (fn [{{params :params} :request :as ctx}]
    (let [;; we only have to transform the names of the params because they should just be the id field... I hope - natevecc
          transformed-params (rename-keys (keywordize-keys params)
                                          (map-invert name-transforms))
          transformed-column-name (get (map-invert name-transforms)
                                       column-name
                                       column-name)
          data (keywordize-keys params)]
      (->> (p/resource->db ctx
                         data
                         :key-rename-map name-transforms)
           (partial db/delete-entity-in-storage
                    transformed-column-name
                    transformed-params
                    table
                    db-spec)
           (run-with-conditions data
                                conditions
                                ctx)))))
