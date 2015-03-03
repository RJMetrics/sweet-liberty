(ns com.rjmetrics.sweet-liberty.db
  (:require [clojure.java.jdbc :as j]
            [com.rjmetrics.sweet-liberty.query :as q]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [com.rjmetrics.sweet-liberty.util :as util]))



; This is crucial to allow Liberator to write JSON
; responses from sql Timestamp and Date objects
(extend-type java.sql.Timestamp
  json/JSONWriter
  (-write [date out]
          (json/-write (str date) out)))

(extend-type java.sql.Date
  json/JSONWriter
  (-write [date out]
          (json/-write (str date) out)))

(defn- storage-interaction-with-logging!
  [fn db-spec sqlmap]
  (let [mdc (into {} (org.apache.log4j.MDC/getContext))]
    (log/debug (json/write-str (assoc mdc
                                 :raw-query sqlmap)))
    (try
    (let [result (fn db-spec sqlmap)]
      (log/debug (json/write-str (assoc mdc
                                   :raw-query sqlmap
                                   :query-result result)))
      result)
     (catch Exception e (do
                          (log/error "Error runnign SQL: " (.getMessage e))
                          (throw (.getNextException e)))))))

(defn- query-with-logging!
  [db-spec sqlmap]
  "Logs the result of executing a query."
  (storage-interaction-with-logging! j/query db-spec sqlmap))

(defn- execute-with-logging!
  [db-spec sqlmap]
  "Logs the result of executing a query."
  (storage-interaction-with-logging! j/execute! db-spec sqlmap))

(defn- dialect
  [db-spec]
  (when-let [s (db-spec :subprotocol)]
    (keyword s)))

(defn get-records-from-storage
  "Query the storage db for rows in table satisfying specified conditions."
  [db-spec table-struct where-map]
  (storage-interaction-with-logging! j/query
                                     db-spec
                                     (q/build-paging-query (dialect db-spec)
                                                               table-struct
                                                               (merge (:defaults table-struct)
                                                                      where-map))))

(defn insert-entity-into-storage
  "Execute statement to insert an entity into storage."
  [table db-spec data]
  (let [mdc (into {} (org.apache.log4j.MDC/getContext))
        table-name (util/dash-to-underscore-kw (:table-name table))
        row-map (util/convert-to-dialect (dialect db-spec) (select-keys data (:attributes table)))
        _     (log/debug (json/write-str (assoc mdc
                                           :message "Inserting a row"
                                           :table (:table-name table)
                                           :data data
                                           :row row-map)))
        result (j/insert! db-spec
                          table-name
                          row-map)]
    (log/debug (json/write-str (assoc mdc
                                 :message "Successfully inserted a row"
                                 :table (:table-name table)
                                 :row row-map
                                 :query-result result)))
    (if (nil? (ffirst result)) (get data (:primary-key table))  ;if result is nil then we set the primary key ourselves
                      (-> result ;; take ({:changing-name-key post-id}) and get post-id. always.
                             ffirst
                             val))))


(defn update-single-entity-in-storage
  "Execute statement to update an entity from storage."
  [column-name params table db-spec data]
  (execute-with-logging! db-spec
                         (q/build-update-statement (dialect db-spec)
                                                       table
                                                       params
                                                       column-name
                                                       data)))

(defn update-many-entities-in-storage
  "Run over each entity in data, doing a single entity update for each. Then,
  since our data is a vector of maps, make a map where the key is the value of
  the where item, and merge them together with what's already on the context"
  [column-name params ctx table db-spec data]
  (j/with-db-transaction [conn db-spec]
    (dorun (map #(update-single-entity-in-storage column-name
                                                  (merge params
                                                         {column-name (get % column-name)})
                                                  table
                                                  conn
                                                  %)
                data)))
  (let [params (hash-map column-name (vec (map column-name data)))
        result (get-records-from-storage db-spec table params)]
    result))

(defn update-entity-in-storage
  "Execute statement to update one or more entities from storage."
  [column-name params ctx table db-spec data]
  ;; update-entity-in-storage requires the context unlike the others
  ;; because it has to merge in the changes to the result.
  (if-not (map? data)
    (update-many-entities-in-storage column-name params ctx table db-spec data)
    (do
      (update-single-entity-in-storage column-name
                                       params
                                       table
                                       db-spec
                                       data)
      (get-records-from-storage db-spec table params))))

(defn delete-entity-in-storage
  "Execute statement to delete an entity from storage."
  [column-name params table db-spec data]
  (execute-with-logging! db-spec
                         (q/build-delete-statement (dialect db-spec)
                                                   table
                                                   params
                                                   column-name)))
