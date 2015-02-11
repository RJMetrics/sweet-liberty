(ns com.rjmetrics.sweet-liberty.expansion
  (:require [clojure.data.json :as json]
            [com.rjmetrics.sweet-liberty.util :as util]
            [clojure.set :as set]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]))

(defn- call-service-broker-with-logging
  [service-broker options]
  (let [mdc (into {} (org.apache.log4j.MDC/getContext))]
    (log/debug (json/write-str (assoc mdc
                                      :expansion-request-options options)))
    (let [sb-result (apply service-broker (apply concat
                                                 (seq options)))]
      (log/debug (json/write-str (assoc mdc
                                        :expansion-response sb-result
                                        :expansion-request-options options)))
      sb-result)))

(defn get-resource-result
  "Extract the body of call-resource and parse it into a map"
  [service-broker resource-name action route-params headers filter-map]
  (let [request-params (merge {:headers headers}
                              (when-not (nil? filter-map)
                                {:body filter-map}))
        sb-result (call-service-broker-with-logging service-broker
                                                    {:resource-name resource-name
                                                     :action action
                                                     :url-params route-params
                                                     :request-options request-params})]
    (if (or (> (:status sb-result) 299) (nil? (:body sb-result)))
      (throw (ex-info "Could not expand to resource"
                      {:response sb-result
                       :resource-name resource-name
                       :url-params route-params
                       :request-options request-params}))
      (try
        (json/read-str (:body sb-result) :key-fn ->kebab-case-keyword)
        (catch Exception e
          (:body sb-result))))))

(defn build-expansion-filters
  "Extract the values for the expansion filter keyed by the resource we're expanding
  to's attribute name. Ex:
  (build-expansion-filters [{:id 5} {:id 8}] {:id :table-id}) => {:table-id [5 8]}"
  [results [result-key filter-key]]
  (let [filter-values (->> results ;; rip out values, flatten to one level and remove duplicates
                           (mapcat #(-> %
                                        (get result-key)
                                        (util/->vec)))
                           distinct)]
    {filter-key filter-values}))

(defn get-expansions
  "Pass in the list of resource names to be expanded on, along with query params
  that will be passed to each expansion. The current results are required to
  correctly filter the expansions by only requesting that subset instead of
  all of them"
  [service-broker expansion-resources route-params expansion-info all-headers results]
  ;; expansions will be a collection when we have multiple items to expand on,
  ;; e.g. ?_expand=columns&_expand=tables => ["columns" "tables"], otherwise just 1 string like "columns"
  (when-not (and (not (keyword? expansion-resources)) (empty? expansion-resources))
    (->> expansion-resources
        util/->vec
        (map (fn [expansion-key]
               [expansion-key (get-resource-result service-broker
                                                   expansion-key
                                                   (-> expansion-info expansion-key :action)
                                                   (set/rename-keys route-params
                                                                    (:route-param-mapping (expansion-key expansion-info)))
                                                   (select-keys (keywordize-keys all-headers)
                                                                (get (expansion-key expansion-info) :headers []))
                                                   (build-expansion-filters results (:join (expansion-key expansion-info))))]))
        (#(apply hash-map (apply concat %))))))

(defn merge-expanded-resources
  "Takes the collection of resources, the resources to be merged in
  and the configuration for the expansions to be performed. Returns
  a collection of resources with the expanded resources nested
  inside appropriately."
  [remote-resources expansion-config result-item]
  (apply merge
         (conj (map (fn [[ex-name {[local-id-field remote-id-field] :join}]]
                      {ex-name (filter #((-> (local-id-field result-item)
                                             util/->vec
                                             set)
                                         (remote-id-field %))
                                       (ex-name remote-resources))})
                    expansion-config)
               result-item)))

(defn expand-all-resources
  "Get all of the expansions in a result and merge them together. If no service broker
  is given, it will hand back the results without any transformation."
  [service-broker route-params expand-query-param table-struct all-headers results]
    (if-not service-broker
      results
      (let [expand-resources (->> expand-query-param util/->vec (map keyword))
            expansion-config (:expansions table-struct)]
        (if (empty? expand-resources)
          results
          (map (partial merge-expanded-resources
                        (get-expansions service-broker
                                        expand-resources
                                        route-params
                                        expansion-config
                                        all-headers
                                        results)
                        (select-keys expansion-config expand-resources))
               results)))))
