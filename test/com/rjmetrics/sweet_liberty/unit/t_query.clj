(ns com.rjmetrics.sweet-liberty.unit.t-query
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.query :refer :all]))

(facts "get-filter-map returns the filters from the query params map as a map of keywords"
       (fact "when the filter column name is a keyword"
             (get-filter-map {"_fields" ["x" "y" "zzz"] :column-name 4} {:attributes [:column-name]})
             => {:column-name 4})
       (fact "when the filter column name is a string"
             (get-filter-map {"_fields" ["x" "y" "zzz"] "column-name" 4} {:attributes [:column-name]})
             => {:column-name 4}))

(fact "create-default-h-sql-map returns the default Honey-Sql query Map with the :select and :from keys set properly."
      (create-default-h-sql-map {:attributes [:column-name :another-column-name]
                                 :table-name :table-name})
      => {:select [:column-name :another-column-name] :from [:table-name]})

(facts "set-field-list"
       (let [h-sql-map {:select [:*]
                        :from [:table-name]}]
         (fact "returns a Honey-Sql query Map with the :select value properly set."
               (set-field-list h-sql-map [:field1 :field2]) => {:select [:field1 :field2]
                                                                :from [:table-name]})
         (fact "does not modify the Honey-Sql Map when field-list is nil"
               (set-field-list h-sql-map nil) => {:select [:*] :from [:table-name]})))

(facts "create-h-sql-where-vector"
       (fact "returns a equals h-sql where vector when the value isn't a vector"
             (create-h-sql-where-vector ["column-name" 1]) => [:= :column-name "1"])
       (fact "returns a in h-sql where vector when the value is a vector"
             (create-h-sql-where-vector ["column-name" [1 "a" 3]]) => [:in :column-name ["1" "a" "3"]]))

(facts "build-where-vector returns a Honey-Sql where vector"
       (fact "for multiple filters."
             (build-where-vector {:column-name 1 :another-column-name 2})
             => [:and [:= :another-column-name "2"] [:= :column-name "1"]])
       (fact "for a single filter."
             (build-where-vector {:column-name 1}) => [:= :column-name "1"])
       (fact "for an empty filter."
             (build-where-vector {}) => nil))

(facts "set-where-list returns a Honey-Sql map with the where field properly set"
       (let [h-sql-map {:select [:*]
                        :from [:table-name]}]
         (fact "for multiple filters."
               (set-where-list h-sql-map {:column-name 1 :another-column-name 2})
               => {:select [:*]
                   :from [:table-name]
                   :where [:and
                           [:= :another-column-name "2"]
                           [:= :column-name "1"]]})
         (fact "for a single filter."
               (set-where-list h-sql-map {:column-name 1}) => {:select [:*]
                                                               :from [:table-name]
                                                               :where [:= :column-name "1"]})
         (fact "for no filters."
               (set-where-list h-sql-map {}) => h-sql-map)
         (fact "for in syntax."
               (set-where-list h-sql-map {:column-name ["a", 2, "b"]}) =>{:select [:*]
                                                                          :from [:table-name]
                                                                          :where [:in :column-name ["a", "2", "b"]]})
         (fact "for multiple filters mixing in and equals syntax."
               (set-where-list h-sql-map {:column-name ["a", 2 "b"] :another-column-name 1 :final-column "3"})
               => {:select [:*]
                   :from [:table-name]
                   :where [:and [:= :final-column "3"][:= :another-column-name "1"][:in :column-name ["a" "2" "b"]]]})))

(facts "build-paging-query returns a properly formatted sql string and params to be passed to jdbc when given no paging options"
       (let [table-structure {:attributes [:column-name
                                           :another-column-name]
                              :table-name :table-name}]
         (fact "when there are no query params."
               (build-paging-query table-structure {})
               => ["SELECT column_name, another_column_name FROM table_name"])
         (fact "when :mysql is the dialect."
               (build-paging-query :mysql table-structure {})
               => ["SELECT `column_name`, `another_column_name` FROM `table_name`"])
         (fact "when there is a fields query-param."
               (build-paging-query table-structure {:_fields [:column1 :column2]})
               => ["SELECT column1, column2 FROM table_name"])
         (fact "when there is a filter query-param."
               (build-paging-query table-structure {:column-name 1
                                                         :another-column-name 2})
               => ["SELECT column_name, another_column_name FROM table_name WHERE (another_column_name = ? AND column_name = ?)"
                   "2"
                   "1"])
         (fact "when there are fields and filter query-params."
               (build-paging-query table-structure {"_fields" [:column_name :another_column_name]
                                                         :column-name 1
                                                         :another-column-name 2})
               => ["SELECT column_name, another_column_name FROM table_name WHERE (another_column_name = ? AND column_name = ?)"
                   "2"
                   "1"])))

(facts "build-paging-query returns a propertly formatted sql string when given paging options"
       (let [table-struct {:attributes [:column-name
                                        :another-column-name
                                        :third-column]
                           :primary-key :column-name
                           :indices [:another-column-name]
                           :table-name :my-table}]
         (fact "no paging information leaves the query unaffected"
               (build-paging-query table-struct {})
               => ["SELECT column_name, another_column_name, third_column FROM my_table"])
         (fact "any single paging parameter is not enough to trigger paging"
               (build-paging-query table-struct {"_page" 1})
               => ["SELECT column_name, another_column_name, third_column FROM my_table"]
               (build-paging-query table-struct {"_pagesize" 20})
               => ["SELECT column_name, another_column_name, third_column FROM my_table"]
               (build-paging-query table-struct {"_pagekey" :column-name})
               => ["SELECT column_name, another_column_name, third_column FROM my_table"]
               (build-paging-query table-struct {"_pageorder" :desc})
               => ["SELECT column_name, another_column_name, third_column FROM my_table"])
         (fact "_page _pagesize and _pagekey will trigger paging"
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :column-name})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY column_name "
                        "ASC LIMIT 20 OFFSET 0")])
         (fact "_pageorder is optional and applied correctly"
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :column-name "_pageorder" :desc})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY column_name "
                        "DESC LIMIT 20 OFFSET 0")]
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :column-name "_pageorder" :asc})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY column_name "
                        "ASC LIMIT 20 OFFSET 0")]
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :column-name "_pageorder" "desc"})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY column_name "
                        "DESC LIMIT 20 OFFSET 0")])
         (fact "using a _pagekey that is in :indices works"
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :another-column-name})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY another_column_name "
                        "ASC LIMIT 20 OFFSET 0")])
         (fact "using a _pagekey that is not in :indices nor the :primary-key fails"
               (build-paging-query table-struct {"_page" 0 "_pagesize" 20 "_pagekey" :third-column})
               => (throws Exception (str "Column \":third-column\" is not defined as an index or primary key. "
                          "If you want to ignore this constraint, add :ignore-index-constraint to your "
                          "table configuration.")))
         (fact "using a _pagekey that is not in :indices nor the :primary-key but with :ignore-index-constraint works"
               (build-paging-query (assoc table-struct :ignore-index-constraint true)
                                   {"_page" 0 "_pagesize" 20 "_pagekey" :third-column})
               => [(str "SELECT column_name, another_column_name, third_column "
                        "FROM my_table "
                        "ORDER BY third_column "
                        "ASC LIMIT 20 OFFSET 0")])))

(facts "build-delete-statement returns a properly formatted sql string"
       (let [table-struct {:attributes [:column-name
                                        :another-column-name]
                           :table-name :my-table}]
         (fact "when there are no route params"
               (build-delete-statement table-struct {} :column-name)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the column name does not exist"
               (build-delete-statement table-struct {} :dne)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the route params contain the incorrect information"
               (build-delete-statement table-struct {:a 5} :column-name)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the route params contain the correct information"
               (build-delete-statement table-struct {:column-name "fake-column"} :column-name)
               => ["DELETE FROM my_table WHERE column_name = ?" "fake-column"])
         (fact "when the route params contain the correct information and it's null"
               (build-delete-statement table-struct {:column-name nil} :column-name)
               => ["DELETE FROM my_table WHERE column_name IS NULL"])
         (fact "when a bulk delete is issued"
               (build-delete-statement table-struct {:column-name ["a" "b" "c"]} :column-name)
               => ["DELETE FROM my_table WHERE (column_name in (?, ?, ?))" "a" "b" "c"])
         (fact "when mysql dialect is used"
               (build-delete-statement :mysql
                                       table-struct
                                       {:column-name ["a" "b" "c"]}
                                       :column-name)
               => ["DELETE FROM `my_table` WHERE (`column_name` in (?, ?, ?))" "a" "b" "c"])
         ))

(facts "build-update-statement returns a properly formatted sql string"
       (let [table-struct {:attributes [:column-name
                                        :another-column-name
                                        :id]
                           :table-name :my-table}
             data {:column-name "simple"}]
         (fact "when there are no route params"
               (build-update-statement table-struct {} :column-name data)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the column name does not exist"
               (build-update-statement table-struct {} :dne data)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the route params contain the incorrect information"
               (build-update-statement table-struct {:a 5} :column-name data)
               => (throws Exception "The column name is invalid. It is either an invalid column, or has no corresponding value."))
         (fact "when the data is nil, nothing happens"
               (build-update-statement table-struct {:id 5} :id nil)
               => (throws Exception "The data is empty or incorrectly formatted. No update will be executed."))
         (fact "when the data is empty, nothing happens"
               (build-update-statement table-struct {:id 5} :id {})
               => (throws Exception "The data is empty or incorrectly formatted. No update will be executed."))
         (fact "when the params contain the correct information and it's null"
               (build-update-statement table-struct {:id 5 :column-name nil} :id {:column_name nil})
               => ["UPDATE my_table SET column_name = NULL WHERE id = ?", "5"])
         (fact "build update will give back a correct update"
               (build-update-statement table-struct {:id 5 :column-name "test-col" :another-column-name "a"} :id data)
               => ["UPDATE my_table SET column_name = ? WHERE id = ?" "simple" "5"])
         (fact "with mysql dialect"
               (build-update-statement :mysql
                                       table-struct
                                       {:id 5
                                        :column-name "test-col"
                                        :another-column-name "a"}
                                       :id data)
               => ["UPDATE `my_table` SET `column_name` = ? WHERE `id` = ?" "simple" "5"])
         ))
