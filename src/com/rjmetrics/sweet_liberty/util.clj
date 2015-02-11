(ns com.rjmetrics.sweet-liberty.util
  (:require [clojure.set :refer [subset?
                                 difference]]
            [clojure.data.json :as json]
            [clojure.string :as string]))

(def post-id-kw (keyword "com.rjmetrics.sweet-liberty.core" "post-id"))
(def entities-kw (keyword "com.rjmetrics.sweet-liberty.core" "entities"))
(def existing-entities-transformed-kw (keyword "com.rjmetrics.sweet-liberty.core" "existing-entities-transformed"))

(defn parse-item-to-keyword
  "Parse out an item. Sometimes items start with a colon even though they're a
  string, so (keyword \":item\") produces \"::item\" which is not intended.
  (parse-item-to-keyword \":name\") => :name, (parse-item-to-keyword \"name\") => :name
  (parse-item-to-keyword :name) => :name"
  [item]
  (cond (keyword? item) item
        (integer? item) (keyword (str item))
        (and (not (string? item)) (seq item)) (keyword (str item))
        (= (first item) \:) (keyword (subs item 1))
        :else (keyword item)))

(defn parse-items-to-keywords
  "Parse items in a vector (or a single string) that may or may not be formatted
  like keywords already into correctly-parsed keywords. Returns nil if the
  items are neither a string nor a sequence."
  [items]
  (cond (keyword? items) items
        (string? items) (vector (parse-item-to-keyword items))
        (seq items) (vec (map parse-item-to-keyword items))
        :else nil))

(defn to-json-input-stream
  "Take a vector or map, write it using json/write-str, and put it into a
  ByteArrayInputStream."
  [items]
  (java.io.ByteArrayInputStream. (.getBytes (json/write-str items))))

(defn- overwrite-or-append-item
  [append? attribute-name items item]
  (let [item-key (parse-item-to-keyword (attribute-name item))]
    ;; If we aren't appened (e.g. overwrite) or the current key doesn't
    ;; exist in items, just tack it onto items.
    (if (or (not append?) (nil? (item-key items)))
      (conj items {item-key [item]})
      (update-in items [item-key] conj item))))

(defn index-map-by-value
  "For a given map, pull out a value of a key and re-create the map s.t. the map
   keys are the values"
  ([item-map attribute-name] (index-map-by-value item-map attribute-name false))
  ([item-map attribute-name grouped?]
     (if-not (nil? attribute-name)
       (reduce (partial overwrite-or-append-item grouped? attribute-name) {} item-map)
       item-map)))

(defn dash-to-underscore-kw
  [kw]
  (keyword (string/replace (name kw) "-" "_")))

(def quote-fns
  {:ansi #(str \" % \")
   :mysql #(str \` % \`)
   :sqlserver #(str \[ % \])
   :oracle #(str \" % \")})

(defn convert-to-dialect
  "For a given map and a dialect, wrap the map keys."
  [dialect attr-map]
  (if (contains? quote-fns dialect)
    (zipmap (map #(-> % name ((quote-fns dialect)))
                  (keys attr-map))
            (vals attr-map))
    attr-map))

(defn json-value-fn [_ v]
  (if (or (number? v) (string? v) (coll? v) (nil? v))
    v
    (str v)))

(defn identity-transform-fn
  "Identity function transform that returns whatever data is given to it"
  [data ctx]
  data)

(defn set-sweet-lib-config-defaults
  [sweet-lib-config]
  (merge {:name-transforms  {}
          :input-transform identity-transform-fn
          :output-transform identity-transform-fn
          :query-transform identity-transform-fn}
         sweet-lib-config))

(defn map-if-not-map
  [func value]
  (if (map? value)
      (func value)
      (map func value)))

(defn ->vec
  [x]
  (cond (nil? x) x
        (vector? x) x
        (seq? x) (vec x)
        :else [x]))
