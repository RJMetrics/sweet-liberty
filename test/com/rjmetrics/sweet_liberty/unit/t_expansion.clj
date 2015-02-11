(ns com.rjmetrics.sweet-liberty.unit.t-expansion
  (:require [midje.sweet :refer :all]
            [com.rjmetrics.sweet-liberty.expansion :refer :all]))

(fact-group :expansion-filters "about build-expansion-filters"
            (fact "single filter values work"
                  (build-expansion-filters [{:other-id 11}
                                            {:other-id 22}]
                                           [:other-id :oid])
                  => {:oid [11 22]})
            (fact "collections of filter values work"
                  (build-expansion-filters [{:other-id [11 12]}
                                            {:other-id [22 23]}]
                                           [:other-id :oid])
                  => {:oid [11 12 22 23]})
            (fact "only distinct values are allowed through"
                  (build-expansion-filters [{:other-id [11 12]}
                                            {:other-id [11 12]}]
                                           [:other-id :oid])
                  => {:oid [11 12]})
            (fact "mixing values and collections works"
                  (build-expansion-filters [{:other-id 11}
                                            {:other-id [22 23]}]
                                           [:other-id :oid])
                  => {:oid [11 22 23]})
            (fact "deeply nested collections do not get flatten"
                  (build-expansion-filters [{:other-id [11 [12]]}
                                            {:other-id [22 23]}]
                                           [:other-id :oid])
                  => {:oid [11 [12] 22 23]}))

(fact-group "about merge-expanded-resources"
            (fact "merge-expanded-resources works"
                  (merge-expanded-resources {:chars-after
                                             [{:comment "", :char "f", :cid 5}
                                              {:comment "", :char "g", :cid 6}
                                              {:comment "", :char "h", :cid 7}
                                              {:comment "", :char "i", :cid 8}]}
                                            {:chars-after
                                             {:join [:nid :cid], :route-param-mapping {:number-id :start}}}
                                            {:char_list "[2,3]", :comment "", :num 7, :nid 7})
                  => {:char_list "[2,3]"
                      :chars-after [{:char "h"
                                     :cid 7
                                     :comment ""}]
                      :comment ""
                      :nid 7
                      :num 7}))
