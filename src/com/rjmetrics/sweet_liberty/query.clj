(ns com.rjmetrics.sweet-liberty.query
  (:require [honeysql.core :as sql]
            [honeysql.helpers :refer :all]
            [com.rjmetrics.sweet-liberty.util :as util]
            [clojure.walk :refer [keywordize-keys]]
            [camel-snake-kebab.core :refer [->snake_case_keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]))

(defn get-filter-map
  "Returns the filters from the query params Map as a Map of keyword fields to values.
  (:attributes table-struct) acts as a whitelist for which items to take.
  (get-filter-map {:columnA value1 :_fields [\"name\"]'} returns {:columnA value1}"
  [query-params table-struct]
  (select-keys (transform-keys ->snake_case_keyword query-params)
               (:attributes table-struct)))

(defn create-default-h-sql-map
  "Returns default Honey-Sql query Map with the :select and :from keys set to the proper attributes
  and table-name from the Sweet-Liberty table-name and table-struct config values.
  (create-default-h-sql-map :table-name) returns {:select [:*] :from [:table-name]}"
  [table-struct]
  {:select (:attributes table-struct)
   :from [(:table-name table-struct)]})

(defn set-field-list
  "Returns a Honey-Sql Map with the select field correctly populated.
  (set-field-list h-sql-map [:field1 :field2 :field3] returns {:select [:field1 :field2 :field3]...}"
  [h-sql-map field-list]
  (if (nil? field-list)
    h-sql-map
    (assoc h-sql-map :select field-list)))

(defn create-h-sql-where-vector
  "Returns a Honey-Sql where Vector given a key, value pair of a filter-map.
  (create-h-sql-where-vector [\"column-name\" 1]) returns [:= :another-column 1]
  (create-h-sql-where-vector [\"column-name\" [2 3]]) returns [:in :another-column [2 3]]"
  [[col-name value]]
  (if (vector? value)
    (into [:in (keyword col-name)] [(map str value)])
    (if (nil? value)
      (into [:= (keyword col-name)] [value])
      (into [:= (keyword col-name)] [(str value)]))))

(defn build-where-vector
  "Returns a Honey-Sql where Vector given a filter-map.
  (build-where-vector {:column-name [value1, value2] :another-column value})
  returns [:and[:in :column-name value1 value2][:= :another-column value]]"
  [filter-map]
  (let [conds (map create-h-sql-where-vector filter-map)]
    (case (count conds)
      0 nil
      1 (first conds)
      (into [:and] conds))))

(defn set-where-list
  "Returns a Honey-Sql Map with the where key correctly populated from a filter-map.
  (set-where-list h-sql-map {:column-name value :another-column a-value} returns
  {...:where [:and [:= :column-name value][:= :another-column a-value]]}"
  [h-sql-map filter-list]
  (if (empty? filter-list)
    h-sql-map
    (assoc h-sql-map :where (build-where-vector filter-list))))

(defn- get-raw-select-map
  "This is used by normal SELECTs as well as paged selects"
  [table-struct query-params]
  (-> (create-default-h-sql-map table-struct)
      (set-field-list (util/parse-items-to-keywords (get query-params :_fields)))
      (set-where-list (get-filter-map query-params table-struct))))

(defn build-delete-statement
  ([table-struct params column-name]
   (build-delete-statement nil table-struct params column-name))
  ([dialect table-struct params column-name]
   (if (and (contains? params column-name)
            (some (partial = column-name) (:attributes table-struct)))
     (-> (:table-name table-struct)
         delete-from
         (set-where-list {column-name (column-name params)})
         (sql/format :quoting dialect))
     (throw (ex-info "The column name is invalid. It is either an invalid column, or has no corresponding value."
                     {:is-sweet-lib-exception? true
                      :code 4
                      :more-info ""})))))

(defn- column-is-indexed?
  [table-struct column-name]
  (some (partial = (keyword column-name))
        (util/parse-items-to-keywords (conj (:indices table-struct)
                                            (:primary-key table-struct)))))

(defn- get-paging-parameters
  [query-params]
  (let [{:keys [_page _pagesize _pagekey _pageorder]
         :or {_pageorder "asc"}}
        (keywordize-keys query-params)]
    {:page (if (string? _page) (read-string _page) _page)
     :page-items (if (string? _pagesize) (read-string _pagesize) _pagesize)
     :page-key (keyword _pagekey)
     :page-order (keyword _pageorder)}))

(defn build-paging-query
  "Build a query with the specific paging properties"
  ([table-struct query-params]
    (build-paging-query nil table-struct query-params))
  ([dialect table-struct query-params]
   (let [{:keys [page page-items page-key page-order]} (get-paging-parameters query-params)
         raw-honey-sql (get-raw-select-map table-struct query-params)]
     (if (and page page-items page-key)
       (if (or (column-is-indexed? table-struct page-key)
               (:ignore-index-constraint table-struct))
         (-> raw-honey-sql
             ;; Order By will always be overwritten here
             ;; Only ":desc" will be acceptable as an alternative page order
             (order-by [page-key page-order])
             (offset (* page page-items))
             (limit page-items)
             (sql/format :quoting dialect))
         (throw (ex-info (str "Column \"" page-key  "\" is not defined as an index or primary key. "
                              "If you want to ignore this constraint, add :ignore-index-constraint "
                              "to your table configuration.")
                         {:is-sweet-lib-exception? true
                          :code 3
                          :more-info ""})))
       (sql/format raw-honey-sql :quoting dialect)))))

(defn build-update-statement
  ([table-struct params column-name data]
   (build-update-statement nil table-struct params column-name data))
  ([dialect table-struct params column-name data]
   (if (and (contains? params column-name)
            (some (partial = column-name) (:attributes table-struct)))
     (if (seq data)
       (-> (:table-name table-struct)
           update
           (sset data)
           (set-where-list {column-name (column-name params)})
           (sql/format :quoting dialect))
       (throw (ex-info "The data is empty or incorrectly formatted. No update will be executed."
                       {:is-sweet-lib-exception? true
                        :code 6
                        :more-info ""})))
     (throw (ex-info "The column name is invalid. It is either an invalid column, or has no corresponding value."
                     {:is-sweet-lib-exception? true
                      :code 5
                      :more-info ""})))))
