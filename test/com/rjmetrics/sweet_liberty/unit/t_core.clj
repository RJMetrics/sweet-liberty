(ns com.rjmetrics.sweet-liberty.unit.t-core
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.core :refer :all]))

(facts "about make-resource"
       (fact "throws exception when an invalid table structure is given"
             (make-resource {:options {:table {:table-name "test"}}})
             => (throws Exception "Invalid table definition given {:table-name \"test\"}")))

