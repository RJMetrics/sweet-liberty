(ns com.rjmetrics.sweet-liberty.core
  (:require [com.rjmetrics.sweet-liberty.handlers :refer [make-read-entities-fn
                                                         make-create-entity-fn
                                                         make-update-entity-fn
                                                         make-delete-entity-fn
                                                         make-read-created-entity-fn
                                                         make-entity-exists?-fn
                                                         make-entities-exist?-fn
                                                         make-allowed?-handler-fn]]
            [com.rjmetrics.sweet-liberty.util :as util]
            [liberator.core :refer [resource]]
            [liberator.representation :refer [ring-response]]
            [clojure.set :refer [subset?]]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [ring.util.response :refer :all]))

(def required-table-properties (hash-set :table-name :attributes :primary-key))

;; defined here so they are available to 3rd parties
(def post-id-kw util/post-id-kw)
(def entities-kw util/entities-kw)
(def existing-entities-transformed-kw util/existing-entities-transformed-kw)


(defn validate-table-config
  "Check the given table map to make sure it has the
  required keys defined by required-table-properties"
  [table]
  (when-not (subset? required-table-properties (set (keys table)))
    (throw (Exception. (str "Invalid table definition given " table)))))

(defn require-allowed?-is-set
  "Verifies that the :allowed? property of the liberator config has been set."
  [config]
  (if (-> config :liberator-config :allowed? nil?)
      (throw (Exception. "The :allowed? property of liberator config must be set explicitly. You might want to include add-authorization to your route."))))

(defn- get-error-map
  [exception-info return-exceptions?]
  (let [error-map (-> exception-info
                      ex-data
                      (dissoc :is-sweet-lib-exception?))]
    (merge error-map
           (if return-exceptions?
             {:exception-message (.getMessage exception-info)
              :stack-trace (map str (.getStackTrace exception-info))}
             { :exception-message "There was an error processing your request."}))))

(defn- get-error-json
  "Returns a properly formatted json string from an ExceptionInfo object."
  [exception-info return-exceptions?]
  (-> exception-info
      (get-error-map return-exceptions?)
      json/write-str))

(defn- log-exception
  [exception-info]
  (let [mdc (into {} (org.apache.log4j.MDC/getContext))
        formatted-exception (get-error-map exception-info true)]
    (log/error (json/write-str (merge mdc formatted-exception)))))

(defn add-exception-handler
  "Given a Sweet-Liberty config object, populate the liberator-config
  object's handle-exception property with a function that will catch
  sweet-liberty internal exceptions and pass any others to the
  handler provided."
  [sweet-lib-config handle-exception-fn]
  (assoc-in sweet-lib-config [:liberator-config :handle-exception]
            (fn [ctx]
              (let [exception-info (:exception ctx)]
                (if (-> exception-info ex-data :is-sweet-lib-exception?)
                  (do
                    (log-exception exception-info)
                    (ring-response {:body (get-error-json exception-info
                                                          (get-in sweet-lib-config [:options :return-exceptions?]))
                                    :status 400
                                    :headers {"Content-Type" "application/json; charset=UTF-8"}}))
                  (handle-exception-fn ctx))))))

(defn default-exception-handler-fn
  [sweet-lib-config]
  (fn [ctx]
    (log-exception (:exception ctx))
    (-> ctx
        :exception
        (get-error-json (get-in sweet-lib-config [:options :return-exceptions?])))))

(defn- add-exception-handler-if-missing
  "Add an exception handler to a Sweet-Liberty config object if it doesn't
  already have one."
  [sweet-lib-config]
  (if (nil? (-> sweet-lib-config :liberator-config :handle-exception))
    (add-exception-handler sweet-lib-config
                           (default-exception-handler-fn sweet-lib-config))
    sweet-lib-config))

(defn make-resource
  "Accepts a Sweet-Liberty config object and creates a Liberator
  resource from it."
  [sweet-lib-config]
  (validate-table-config (get-in sweet-lib-config [:options :table]))
  (require-allowed?-is-set sweet-lib-config)
  (-> sweet-lib-config
      add-exception-handler-if-missing
      :liberator-config
      resource))

(defn add-exists
  "It is always necessary to check for the existance of resources in the
  data store before performing any further operation. Given a
  Sweet-Liberty config object, this function will populate
  liberator-config `exists?` with an appropriate handler. If a route
  deals with individual resources (anything besides an index endpoint),
  the primary key field name should be provided as a second parameter."
  ([{{:keys [table db-spec name-transforms query-transform output-transform]} :options :as config}]
   (assoc-in config
             [:liberator-config :exists?]
             (make-entities-exist?-fn table
                                      db-spec
                                      (get-in config [:options :url-params])
                                      name-transforms
                                      query-transform
                                       output-transform)))
  ([{{:keys [table db-spec name-transforms query-transform output-transform]} :options :as config} column-name]
   (assoc-in config
             [:liberator-config :exists?]
             (make-entity-exists?-fn table
                                     db-spec
                                     (get-in config [:options :url-params])
                                     column-name
                                     name-transforms
                                     query-transform
                                     output-transform))))

(defn add-ok-handler
  "Given a Sweet-Liberty config object, populate liberator :handle-ok
  with a handler that will return resources when responding with HTTP
  status 200."
  [{{:keys [table
            db-spec
            name-transforms
            output-transform
            controller
            service-broker]} :options
    :as sweet-lib-config}
   & {:keys [collection?]}]
  {:pre [(not (nil? collection?))]}
  (assoc-in sweet-lib-config
            [:liberator-config :handle-ok]
            (make-read-entities-fn db-spec
                                   table
                                   name-transforms
                                   output-transform
                                   controller
                                   collection?
                                   service-broker)))

(defn add-get
  "Given a Sweet-Liberty config object, set GET as an allowed method."
  [sweet-lib-config]
  (update-in sweet-lib-config [:liberator-config :allowed-methods] conj :get))

(defn add-post
  "Given a Sweet-Liberty config object, populate liberator :post! with a
  function to execute resource creation. Additionally, add POST as an
  allowed method."
  [{{:keys [table db-spec name-transforms
            input-transform output-transform]} :options
    :as config}]
  (-> config
      (assoc-in [:liberator-config :post!] (make-create-entity-fn table
                                                                  db-spec
                                                                  name-transforms
                                                                  input-transform
                                                                  output-transform
                                                                  (-> config :options :conditions :create)))
      (update-in [:liberator-config :allowed-methods] conj :post)))

(defn add-created-handler
  "Given a Sweet-Liberty config object, populate the
  liberator :handle-created with a handler that will return the created
  resource when responding with HTTP status 201."
  [{{:keys [table db-spec name-transforms output-transform controller service-broker]} :options
    :as sweet-lib-config}]
  (assoc-in sweet-lib-config
            [:liberator-config :handle-created]
            (make-read-created-entity-fn db-spec
                                         table
                                         name-transforms
                                         output-transform
                                         controller
                                         service-broker)))

(defn add-post&handler
  "Enable POST functionality for the route.  Given a Sweet-Liberty
  config object, this function populates liberator :post!
  and :handle-created by calling add-post and add-created-handler."
  [sweet-lib-config]
  (-> sweet-lib-config
      (add-post)
      (add-created-handler)))

(defn add-put
  "Allow PUT to act as UPDATE by updating based on column name.
  If a single endpoint is used (i.e. /item/:id), then the value
  of :id will be the where clause.
  e.g. PUT /item/5 => UPDATE (..) VALUES (..) WHERE id=5

  Where-clause-column-name is USUALLY the primary key or index. If it is not,
  sweet-liberty will return an error. You can ignore this
  constraint by specifying `:ignore-index-constraint true` in your
  config options.

  If there are no route parameters specified, it will act as a bulk
  update. The body of a bulk update should be a vector of maps,
  where each map has a key of column-name and a value. This will act
  as the WHERE clause for each item."
  [{:keys [options] :as config}
   where-clause-column]
  (let [{:keys [table
                db-spec
                name-transforms
                query-transform
                input-transform
                output-transform
                service-broker]} (util/set-sweet-lib-config-defaults options)]
    (-> config
        (assoc-in [:liberator-config :put!] (make-update-entity-fn table
                                                                   db-spec
                                                                   where-clause-column
                                                                   name-transforms
                                                                   query-transform
                                                                   input-transform
                                                                   output-transform
                                                                   (get-in config [:options :url-params])
                                                                   (-> config :options :conditions :update)))
        (update-in [:liberator-config :allowed-methods] conj :put)
        (assoc-in [:liberator-config :new?] (fn [ctx]
                                              (if (= :put (get-in ctx [:request :request-method]))
                                                false
                                                true)))
        (assoc-in [:liberator-config :can-put-to-missing?] false)
        (assoc-in [:liberator-config :respond-with-entity?] true))))

(defn add-delete
  "Allows one or many entities to be deleted. It will automatically pull values
  from the query params, route params, and body. Any that are in the form of
  column-name=val will be used in the WHERE clause for delete.
  e.g. Route /test/:id, DELETE /test/5?id=6, body: id=7
  Translates to DELETE FROM test WHERE id IN (5, 6, 7)."
  [{{:keys [table db-spec name-transforms]} :options :as config} column-name]
  (-> config
      (assoc-in [:liberator-config :delete!]
                (make-delete-entity-fn table
                                       db-spec
                                       column-name
                                       name-transforms
                                       (-> config :options :conditions :delete)))
      (update-in [:liberator-config :allowed-methods] conj :delete)))

(defn add-authorization
  "Apply the provided authorization function to liberator :allowed?"
  [sweet-lib-config permissions]
  (assoc-in sweet-lib-config
            [:liberator-config :allowed?]
            (make-allowed?-handler-fn permissions)))

(defn transform-post-to-get
  "Takes a ring request of method POST and transforms it into a GET,
  replacing the POST body as query parameters. This is typically used to
  create an endpoint that interprets a POST as a GET, which circumvents
  query string length limitations. Yes, this is a hack."
  [req]
  (if (= (:request-method req) :post)
    (if (or (contains? req :form-params) (contains? req :body-params))
      (-> req
          (assoc :request-method :get)
          (assoc :query-params (conj (:query-params req) (:form-params req) (:body-params req))))
      (throw (Exception. ":form-params must be parsed from the request. Check the wrap-params middleware for ring.")))
    req))
