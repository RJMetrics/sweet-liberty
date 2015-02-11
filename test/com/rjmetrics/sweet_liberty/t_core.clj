;; This is a dummy file that lets midje recurse the other dirs
(ns com.rjmetrics.sweet-liberty.t-core
	(:require [midje.sweet :refer :all]))

(fact "bears eat beets"
     (let [what-bears-eat #{:rodents :trash :beets :children :leafy-greens}]
       (:beets what-bears-eat) => :beets))