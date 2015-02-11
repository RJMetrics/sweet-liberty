(ns com.rjmetrics.sweet-liberty.unit.t-handlers
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.handlers :refer :all]
            [com.rjmetrics.sweet-liberty.process :refer :all]))

(def test-result-data [{:id 0 :name "a"} {:id 1 :name "b"} {:id 2 :name "c"}])

(fact-group "about transform-representation"
            (let [resource-representation {:id 1 :name "Rex" :type :dachshund}
                  db-representation {:id 1 :dog-name "Rex" :breed 1}
                  key-mapping {:dachshund 1
                               :pittbull 2
                               :poodle 3}]
              (fact "transforms from resource to db representation"
                    (transform-representation
                      :resource->db
                      resource-representation
                      {}
                      {:name :dog-name
                       :type :breed}
                      (fn [data ctx] (update-in data
                                                [:type]
                                                #(get key-mapping
                                                      %))))
                    => db-representation)
              (fact "transforms from db to resource representation"
                    (transform-representation
                      :db->resource
                      db-representation
                      {}
                      {:dog-name :name
                       :breed :type}
                      (fn [data ctx] (update-in data
                                                [:type]
                                                #(get (clojure.set/map-invert key-mapping)
                                                      %))))
                    => resource-representation)))
