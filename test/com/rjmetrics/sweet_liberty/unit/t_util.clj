(ns com.rjmetrics.sweet-liberty.unit.t-util
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.util :refer :all]))

(facts "about index-map-by-value"
       (fact "a nil map returns an empty map"
             (index-map-by-value nil :id) => {})
       (fact "a map indexed by nil returns the map"
             (index-map-by-value [{:id 5}] nil) => [{:id 5}])
       (fact "a vector with a single map will return a map where the value has become the key"
             (index-map-by-value [{:id 5}] :id) => {:5 [{:id 5}]})
       (fact "a vector with multiple maps will correctly map it's :id values to the keys in the new map"
             (index-map-by-value [{:id 5} {:id "asd"} {:id :nope} {:id 0 :other "other"}] :id)
             => {:5 [{:id 5}]
                 :asd [{:id "asd"}]
                 :nope [{:id :nope}]
                 :0 [{:id 0 :other "other"}]})
       (fact "a vector with one map that has multiple values for the id will map correctly"
             (index-map-by-value [{:id [5,22]}] :id) => {(keyword (str [5 22])) [{:id [5 22]}]})
       (fact "a vector with two maps grouped on the same value will be overwritten"
             (index-map-by-value [{:id 5 :name "overwrite"} {:id 10 :name "overwrite"}] :name)
             => {:overwrite [{:id 10 :name "overwrite"}]})
       (fact "a vector with two maps grouped on the same value but with grouped=true will give back vectors of groups"
             (index-map-by-value [{:id 5 :name "overwrite"} {:id 10 :name "overwrite"}] :name true)
             => {:overwrite [{:id 5 :name "overwrite"} {:id 10 :name "overwrite"}]}))

(facts "about parse-items-to-keywords"
       (fact "parses a vector of strings to a vector of keywords"
             (parse-items-to-keywords ["x" "y" "zzz"]) => [:x :y :zzz])
       (fact "explicitly returns a vector"
             (vector? (parse-items-to-keywords ["x" "y" "zzz"])) => true)
       (fact "returns a string in a vector when just a single argument is given"
             (parse-items-to-keywords "xyz") => [:xyz])
       (fact "returns nil if nothing is given"
             (parse-items-to-keywords nil) => nil)
       (fact "returns the keyword if a keyword is given"
             (parse-items-to-keywords :hello) => :hello))

(facts "about parse-item-to-keyword"
       (fact "will return a keyword when given a keyword"
             (parse-item-to-keyword :test) => :test)
       (fact "will return a keyword when given a string"
             (parse-item-to-keyword "test") => :test)
       (fact "will return a keyword when given a string that looks like a keyword"
             (parse-item-to-keyword ":test") => :test)
       (fact "will return a keyword when given an integer"
             (parse-item-to-keyword 1) => :1)
       (fact "will return a keyword when given a sequence"
             (parse-item-to-keyword '(:a :b)) => (keyword (str '(:a :b))))
       (fact "will return nil when given nil"
             (parse-item-to-keyword nil) => nil))

(facts "convert-to-dialect returns a properly formatted set"
  (let [attr-map {:one-one "one" :two "two" :three "three"}]
    (fact "when dialect is null do not change the format"
      (convert-to-dialect :notmysql attr-map) => {:one-one "one" :two "two" :three "three"})
    (fact "when dialect is mysql add ticks"
      (convert-to-dialect :mysql attr-map) => {"`one-one`" "one" "`two`" "two" "`three`" "three"})
    (fact "when dialect is ansi add quotes"
      (convert-to-dialect :ansi attr-map) => {"\"one-one\"" "one" "\"two\"" "two" "\"three\"" "three"})
    (fact "when dialect is sqlserver add brackets"
      (convert-to-dialect :sqlserver attr-map) => {"[one-one]" "one" "[two]" "two" "[three]" "three"})
    (fact "when dialect is oracle add quotes"
      (convert-to-dialect :oracle attr-map) => {"\"one-one\"" "one" "\"two\"" "two" "\"three\"" "three"})))
