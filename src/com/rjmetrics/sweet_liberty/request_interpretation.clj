(ns com.rjmetrics.sweet-liberty.request-interpretation)

(defn extract-merged-body-params
  [{:keys [form-params body-params]} url-params]
  (if (empty? form-params)
    (if (map? body-params)
      (merge url-params body-params)
      body-params)
    (merge url-params form-params)))

(defn extract-merged-query-params
  [{:keys [query-params route-params]} url-params]
  (merge query-params
         route-params
         url-params))

(defn extract-expand-query-param
  [request]
  (get-in request [:query-params "_expand"]))

(defn raw-request-method
  "Given a liberator context map, returns the request method."
  [ctx]
  (-> ctx
      :request
      :request-method
      name
      clojure.string/upper-case))
