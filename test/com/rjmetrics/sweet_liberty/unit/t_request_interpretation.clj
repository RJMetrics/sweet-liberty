(ns com.rjmetrics.sweet-liberty.unit.t-request-interpretation
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.request-interpretation :refer :all]))

(facts "about extract-merged-body-params"
       (fact "just form-params"
             (extract-merged-body-params {:form-params {:a 1}}
                                         {})
             => {:a 1})
       (fact "just body-params as a map"
             (extract-merged-body-params {:body-params {:a 1}}
                                         {})
             => {:a 1})
       (fact "just body-params as a vector"
             (extract-merged-body-params {:body-params [{:a 1} {:a 2}]}
                                         {})
             => [{:a 1} {:a 2}])
       (fact "just url-params"
             (extract-merged-body-params {}
                                         {:a 1})
             => nil)
       (fact "form-params and body-params as a map"
             (extract-merged-body-params {:form-params {:a 1 :b 3}
                                          :body-params {:a 2 :c 4}}
                                         {})
             => {:a 1 :b 3})
       (fact "form-params and body-params as a vector"
             (extract-merged-body-params {:form-params {:a 1 :b 3}
                                          :body-params [{:a 2 :c 4}{:a 5 :c 6}]}
                                         {})
             => {:a 1 :b 3})
       (fact "form-params and url-params"
             (extract-merged-body-params {:form-params {:a 1 :b 3}}
                                         {:a 2 :c 4})
             => {:a 1 :b 3 :c 4})
       (fact "body-params as map and url-params"
             (extract-merged-body-params {:body-params {:a 1 :b 3}}
                                         {:a 2 :c 4})
             => {:a 1 :b 3 :c 4})
       (fact "body-params as vector and url-params"
             (extract-merged-body-params {:body-params [{:a 2 :c 4}{:a 5 :c 6}]}
                                         {:a 2 :c 4})
             => [{:a 2 :c 4}{:a 5 :c 6}]))

(facts "about extract-merged-query-params"
       (fact "just query-params"
             (extract-merged-query-params {:query-params {:a 1}}
                                          {})
             => {:a 1})
       (fact "just route-params"
             (extract-merged-query-params {:route-params {:a 1}}
                                          {})
             => {:a 1})
       (fact "just url-params"
             (extract-merged-query-params {}
                                          {:a 1})
             => {:a 1})
       (fact "query-params and route-params"
             (extract-merged-query-params {:query-params {:a 1 :b 3}
                                           :route-params {:a 2 :c 4}}
                                          {})
             => {:a 2 :b 3 :c 4})
       (fact "query-params and url-params"
             (extract-merged-query-params {:query-params {:a 1 :b 3}}
                                          {:a 2 :c 4})
             => {:a 2 :b 3 :c 4})
       (fact "route-params and url-params"
             (extract-merged-query-params {:route-params {:a 1 :b 3}}
                                          {:a 2 :c 4})
             => {:a 2 :b 3 :c 4}))