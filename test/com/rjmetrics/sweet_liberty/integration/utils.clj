(ns com.rjmetrics.sweet-liberty.integration.utils
  (:require [com.rjmetrics.sweet-liberty.core :refer :all]
            [compojure.core :refer [defroutes GET]]
            [clojure.java.jdbc :as j]
            [ring.middleware.params :refer [wrap-params]]))

(defn get-db-spec
  [db-name]
  {:subprotocol "hsqldb"
   :subname (str "mem:" db-name)})

(def default-liberator-hooks {:available-media-types ["application/json"]
                              :authorized? true
                              :allowed? true})

(def default-table :test_table)

(def default-table-structure {:attributes [:name :id :state :note :datetime :date :timestamp]
                              :table-name default-table
                              :primary-key :id})

(defn keyword-to-str
  [keyword]
  (subs (str keyword) 1))

(defn create-table
  ([db-spec] (create-table db-spec default-table))
  ([db-spec table-name] (create-table db-spec
                                      table-name
                                      [[:id "INTEGER" "NOT NULL" "IDENTITY"]
                                      [:name "VARCHAR(255)" "NOT NULL"]
                                      [:state "INTEGER" "NOT NULL"]
                                      [:note "VARCHAR(255)"]
                                      [:datetime "DATETIME"]
                                      [:date "DATE"]
                                      [:timestamp "TIMESTAMP"]]))
  ([db-spec table-name fields]
     (j/execute! db-spec [(apply j/create-table-ddl table-name fields)])))

(defn drop-table
  ([db-spec] (drop-table db-spec default-table))
  ([db-spec table-name]
     ;; I specifically opted not to use the ddl lib here since it doesn't support IF EXISTS
     (j/execute! db-spec [(str "DROP TABLE IF EXISTS " (keyword-to-str table-name))])))

(defn populate-table
  ([db-spec] (populate-table db-spec default-table))
  ([db-spec table-name]
     (apply j/insert! db-spec table-name [["id" "name" "state" "note" "datetime" "date" "timestamp"]
                                          [0 "test0" 0 nil nil nil nil]
                                          [1 "test1" 1 nil "2014-10-15 10:25:49" "1986-04-26" "2014-10-15 10:25:49"]])))

(defn get-all-rows
  ([db-spec] (get-all-rows db-spec default-table))
  ([db-spec table-name]
     (j/query db-spec (str "SELECT * FROM " (keyword-to-str table-name)))))

(defn initialize-db
  ([db-spec] (initialize-db db-spec populate-table))
  ([db-spec populate-fn] (initialize-db db-spec populate-fn default-table))
  ([db-spec populate-fn table-name]
     (drop-table db-spec table-name)
     (create-table db-spec table-name)
     (populate-fn db-spec table-name)))

(defn drop-db
  ([db-spec] (drop-db db-spec default-table))
  ([db-spec table-name]
     (drop-table db-spec table-name)))
