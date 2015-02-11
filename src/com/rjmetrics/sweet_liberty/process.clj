(ns com.rjmetrics.sweet-liberty.process
  (:require [com.rjmetrics.sweet-liberty.util :as util]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.set :refer [rename-keys
                                 map-invert]]))


(defn transform-representation
  [order data ctx key-rename-map values-transform-fn]
  {:pre [(some #{order} [:resource->db :db->resource])]}
  (let [non-nil-key-rename-map (or key-rename-map {})
        non-nil-value-transform-fn #((or values-transform-fn util/identity-transform-fn) % ctx)
        rename-keys-fn #(rename-keys % non-nil-key-rename-map)
        func-coll [#(util/map-if-not-map rename-keys-fn %)
                   non-nil-value-transform-fn]
        func-coll-ordered (case order
                            :resource->db func-coll
                            :db->resource (reverse func-coll))]
    ((apply comp func-coll-ordered) data)))

(defn resource->db
  "Accepts a collection and transforms it using the value transform function
  then the key transform map to rename the keys. Passes liberator context to
  value transform function."
  [ctx data & {:keys [key-rename-map values-transform-fn] :as named-args}]
  {:pre [(every? #{:key-rename-map :values-transform-fn} (keys named-args))]}
  (transform-representation :resource->db
                            (keywordize-keys data)
                            ctx
                            (map-invert key-rename-map)
                            values-transform-fn))

(defn db->resource
  "Given a collection transform it using the key transform map to rename keys
  then the value transform function. Passes liberator context to value transform
  function."
  [ctx data & {:keys [key-rename-map values-transform-fn] :as named-args}]
  {:pre [(every? #{:key-rename-map :values-transform-fn} (keys named-args))]}
  (transform-representation :db->resource
                            data
                            ctx
                            key-rename-map
                            values-transform-fn))

(defn process-data-response
  "Apply appropriate transforms, controller logic and optional
  additional processing to data to prepare for response."
  ([ctx name-transforms output-transform controller collection?
    & {:keys [inject-processing] :or {inject-processing identity}}]
   (let [controller (or controller util/identity-transform-fn)
         result-data (db->resource ctx
                                   (util/entities-kw ctx)
                                   :key-rename-map name-transforms
                                   :values-transform-fn output-transform)]
     (controller (->> result-data
                      (remove nil?) ;; added remove nil? to handle the result-tranform function returning nil
                      inject-processing
                      ((if collection? identity first)))
                 ctx))))
