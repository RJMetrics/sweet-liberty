# Sweet-Liberty

Sweet-Liberty is a library for building database-backed RESTful services using Clojure. Its name derives from its two principle dependencies, [HoneySQL](https://github.com/jkk/honeysql), a declarative query string renderer, and [Liberator](https://clojure-liberator.github.io/liberator/), a library for building REST-compliant web applications.

### Including Sweet-Liberty
Sweet-Liberty is available on [Clojars](https://clojars.org/) at:

`[com.rjmetrics.developers/sweet-liberty "0.1.3"]`

Require with `(require [com.rjmetrics.sweet-liberty.core :as sl)`

### Key Features

Sweet-Liberty powered endpoints support the following:

- CRUD operations
    - HTTP `GET` **=>** SQL `SELECT`
    - HTTP `POST` **=>** SQL `INSERT`
    - HTTP `PUT` **=>** SQL `UPDATE`
    - HTTP `DELETE` **=>** SQL `DELETE`
- field selection
	- eg `?_fields=id&_fields=name`
	- Use query params to return only a selected subset of fields. 
- filtering
	- eg `?breed=corgi` -- only return corgis
	- Use query params to filter out results that don't match specified conditions
- paging
	- eg `?_page=2&_pagesize=10&_pagekey=created&_pageorder=desc`
	- Use query params to only return one page of results
- expansion
	- eg `?_expand=owner` -- would include data for the dog's owner in the dog object
	- Use query params to include data from referenced resources
	- Can significantly reduce number of requests client needs to make
	- Conceptually similar to doing a SQL `JOIN`, but between resources instead of tables
	- Requires additional configuration -- not as *out-of-the-box* as other features above

### Required Middleware **(important!)**

Sweet-Liberty requires `wrap-params` in [`ring.middleware.params`](https://ring-clojure.github.io/ring/ring.middleware.params.html#var-wrap-params). This is built-in if you use `lib-noir` for your handler.

### Example application

https://github.com/RJMetrics/sweet-liberty-example

## Table of Contents

<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [API Documents](#api-documents)
- [Built-in query parameter operations](#built-in-query-parameter-operations)
  - [Field Selection](#field-selection)
  - [Filtering](#filtering)
  - [Paging](#paging)
  - [Expansion](#expansion)
- [Configuring an Endpoint](#configuring-an-endpoint)
  - [Liberator Configuration](#liberator-configuration)
  - [Sweet-Liberty Configuration](#sweet-liberty-configuration)
    - [DB-spec Example](#db-spec-example)
    - [Conditions](#conditions)
    - [Expansion Configuration](#expansion-configuration)
  - [Core Functions](#core-functions)
    - [Existence](#existence)
    - [Adding HTTP Methods](#adding-http-methods)
    - [Adding Status Handlers](#adding-status-handlers)
    - [Other Stuff](#other-stuff)
  - [Helpers](#helpers)
- [Order of Operations](#order-of-operations)
- [Logging with log4j](#logging-with-log4j)
- [Future plans](#future-plans)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->


## API Documents

API documentation is generated using [codox](https://github.com/weavejester/codox). You can generate it with `lein doc`.

Access `doc/index.html` in a browser to see the generated docs.

## Built-in query parameter operations

An endpoint powered by Sweet-Liberty provides a number of operations by default. These can be accessed via query parameters in the request URL. Operations can by used in conjunction.

The following query string keys are reserved. If a resource has a field with the same name as any of these, filtering will not be available for that field.
```
_fields _page _pagesize _pagekey _pageorder _expand
```

### Field Selection

Use query params such as these `_fields=id&_fields=name&...` to select only a subset of fields.

Example response`GET /dogs`: 
```Clojure
[{:id 1 :name "Fido" :breed "dachshund"}
 {:id 2 :name "Rex" :breed "chihuahua"}]
```

...and now with a field list `GET /dogs?_fields=id&_fields=name`:
```Clojure
[{:id 1 :name "Fido"}
 {:id 2 :name "Rex"}]
```

### Filtering

If a supplied query parameter is not reserved and matches a field name, it will be used as a filter condition. Applying filters to *multiple* fields will return the intersection (SQL `AND`) of the results. Specify multiple filters for a *single* field will return the union (SQL `OR`) of the results.

Example requests and responses are below.

 `GET /dogs?id=5&id=2`
```Clojure
[{:id 2 :name "Lacy" :breed "corgi"}
 {:id 5 :name "Rex" :breed "chihuahua"}]
```

 `GET /dogs?breed=corgi`
```Clojure
[{:id 2 :name "Lacy" :breed "corgi"}
 {:id 6 :name "Brody" :breed "corgi"}]
```

 `GET /dogs?id=5&breed=corgi`
 `[]`

Inequality operators are not supported at this time. :'(

### Paging

The paging query parameters can be used to sort and divide the collection of records into pages and return only the page requested. Paging can only be performed on items that have been configured as a primary key or index, unless `:ignore-index-constraint` is true. See [sweet-liberty configuration: indices](#sweet-liberty-config-indices) for details.

Parameter | Type | Behavior
----------|------|-----------
`_page` | int | Index of page to return. Uses zero-based offset.
`_pagesize` | int | Number of items per page.
`_pagekey` | string | Name of the column to sort by
`_pageorder`| string | *(optional)* Sort results in ascending ("asc") or descending ("desc") order. Ascending is the default.


### Expansion

It is common to have resources that reference, or are referenced by, other resources. A dog has an owner. An owner may have one or more dogs. A dog may have one or more puppies. *(always spay or neuter your pets!)*
In a typical relational database scenario, if you wanted to merge data from more than one of these resources, you would likely use a SQL `JOIN`. But, Sweet-Liberty is designed for a world where that option is not available to you. Different resources might be managed by different teams, using different technologies, in different geographical locations. Sweet-Liberty's expansion operation lets you transcend those boundaries without putting the onus onto the client.

Here is an example request and response using an expansion:

`GET /dogs?id=1&id=2&_expand=owner`
```Clojure
[{:id 1 :name "Lacy" :breed "corgi" :owner {:id 8 :name "Josh"}}
 {:id 2 :name "Rex" :breed "chihuahua" :owner {:id 4 :name "Owen"}}]
```

See [sweet-liberty configuration: expansion](#expansion-configuration).

## Configuring an Endpoint

An endpoint using Sweet-Liberty is created by:

1. constructing a Sweet-Liberty configuration map
2. passing that to `sweet-liberty.core/make-resource`, which yields a Liberator resource function
3. and passing that to a compojure endpoint definition

The Sweet-Liberty configuration map has two top level properties:

- `:options`
	- This contains all Sweet-Liberty specific [configuration](#sweet-liberty-configuration). See below for details.
- `:liberator-config`
	- This contains a map that is used as the base Liberator configuration. Any configuration that you'd like to pass directly into Liberator should go here. Typically, this map will not have much, but the full Liberator API is available to you.


### Liberator Configuration

As stated above, [Liberator](https://clojure-liberator.github.io/liberator/) is used under the hood to provide HTTP and REST compliant behavior through its awesome [decision graph](https://clojure-liberator.github.io/liberator/tutorial/decision-graph.html). While it may not be strictly necessary to have knowledge of Liberator in order to use Sweet-Liberty, a little bit of understanding will take you a long way.

Sweet-Liberty allows you to fully leverage Liberator by leaving the Liberator configuration map exposed during route configuration.

To get started, your Liberator configuration can be as simple as the example below. This specifies that your endpoints should return data as json if the HTTP request specified that as an acceptable format.

Example:
```Clojure
{:available-media-types ["application/json"]}
```

If you do not need to customize Liberator in any way, just pass through an empty map `{}`.

### Sweet-Liberty Configuration

This section describes the configuration values that can be set in the `:options' map.
A useful convention is to separate resource-specific config from common config value and then merge the applicable maps on a per-route basis. As such, the configuration options are presented in two tables. The first contains values that are typically specific to a resource. The second contains values that are typically common across resources. Check out the [example application](https://github.com/RJMetrics/sweet-liberty-example) for how you might organize this type of scheme, but, ultimately, those details are up to you.

> Configuration options typically set on a **per resource** basis

Key | Required? |Type | Description
----|-----------|---------------|--------------
`:table-name` | Yes | Keyword | The name of the table to be queried.
`:attributes` | Yes | Vector of keywords | These are the fields in the database table.
<a name="sweet-liberty-config-indices"></a>`:primary-key` | Yes | Keyword | The column name of the primary key.
`:indices` | No | Vector of keywords | A list of indexes on the table.
`:ignore-index-constraint` |No | Boolean | If `true`, allows otherwise index/primary-key constrained operations to run on any column. Use with **caution** -- sorting by a field without an index can be slow and resource-intensive.
`:defaults` | No |Map | Contains default filters. Expects `{:[FIELD NAME] value}`.
`:conditions` | No | Map of functions | See [condition configuration](#conditions) below.
`:name-transforms` | No | {:db-column-name :resource-attribute-name} | Transform the keys of data going in or out
`:query-transform` | No | (fn [data context] ...) | Transform any query data. Done during exists?
`:input-transform` | No | (fn [data context] ...) | Transform data before it goes to the database
`:output-transform` | No | (fn [data context] ...) | Transforms data on its way out, but before expansions occcur
`:controller` | No | (fn [data context] ...) | Transforms data after expansion, before it's returned in the response.
`:expansions` | No | Map of maps | See [expansion configuration](#expansion-configuration) below.

> Configuration options typically common to all resources and routes

Key | Required? |Type | Description
----|-----------|---------------|--------------
`:db-spec` | Yes | Map | The map should contain all information needed to connect to a database by JDBC. See [db-spec example](#db-spec-example) below.
`:return-exceptions?` | No | Boolean | If this is true, internal sweet-lib exceptions with stack traces will be returned in responses. Otherwise, only a message indicating an internal sweet-lib error will be returned. Additionally, if this is true *and* if you have not set an exception handler using `add-exception-handler`, *all* exceptions will show a stack trace in the response. This should not be set to `true` in production.
`:service-broker` | No | Function | A method to return a result from another service. This is only required if you want routes to support the [expansion](#expansion) operation. A reference implementation of a service broker is available [here](https://github.com/RJMetrics/simple-service-broker) on github.

> Configuration options typically set on a **per route** basis

Key | Required? |Type | Description
----|-----------|---------------|--------------
`:url-params` | No | Map | If you have a route that appears like `GET /item/:item-id [item-id]`, and the actual table id is `:id`, Sweet-Liberty won't be able to put two and two together. Spell it out by specifying `{:url-params {:id item-id}}`, where item-id is the value that has been extracted from the request url at run-time.


#### DB-spec Example

A [clojure.java.jdbc](https://github.com/clojure/java.jdbc) compatible database connection map.

```Clojure
{:subprotocol "mysql"
 :user "username"
 :password "password"
 :subname "//localhost:3307/database-name"}
```

#### Conditions

Conditions can be applied to Create, Update, and Delete operations. The keys will be appropriately used by `add-post`, `add-delete`, and `add-put`. For each method, you can apply a function both before or after the SQL operation is executed. If the function returns false, execution halts and an exception is thrown with `ex-info`. This exception is caught internally by Sweet-Liberty and yields a response with HTTP status 400.

Possible uses of using pre/post conditions would be cascading deletes, forcing creation of secondary entities, or updating multiple keys at once. If any secondary operation fails, you can bail out and notify the requesting user/program that something went wrong so they can retry.

The `:before` function will receive the current data and context. The `:after` function will receive the original data, the result of the operation, and the context.

Example config:
```Clojure
{:create {:before (fn [data context] ...)
          :after (fn [data result context] ...)}
 :update {:after (fn [data result context] ... )}
 :delete {:before (fn [data context] ...)}}
```

The before and after conditions have a signature of `[data context]` and `[data result context]` respectively. In each case, `data` will be the original resource value that was contained in the request. `Result` is only available to *after* conditions. Its contents depends on the http method of the request. For a `PUT` (`UPDATE`), the `result` will be the results record(s) selected from the database after the `UPDATE` statement was executed. For a `POST` (`INSERT`), `result` will equal the primary key id value of the newly created record.


#### Expansion Configuration

The expansion configuration specifies how to *join* one resource to another. The configuration includes:

-  the foreign resource name (which the service broker must understand)
-  a list of local and foreign key fields to bind on, and
- a list of any headers that should be propagated from the original request to the expansion request. This comes in handy for cache control and authentication purposes. 

Example expansion configuration:

```Clojure
{:owner {:join [:id :dog-id] ;; [local key, foreign key]
	 :action :get-single
         :headers [:cache-control]}
```

In the example above, making a request such as `GET /dogs?id=1&id=2&_expand=owner`, might result in a response such as the one below. For each dog object, the respective data for the owner has been retrieved and appended. Internally, Sweet-Liberty calls the service broker with all the relevant information about what resources need to be fetched. The resource name (`:owner`) and the action (`:get-single`) are unique values that the service broker needs to be configured to understand. The service broker carries out the actual communications and returns any responses it receives. Sweet-Liberty then merges those responses to form the full data set response.

```Clojure
[{:id 1 :name "Lacy" :breed "corgi" :owner {:id 8 :name "Josh"}}
 {:id 2 :name "Rex" :breed "chihuahua" :owner {:id 4 :name "Owen"}}]
```

A reference implementation of a service broker is available [here](https://github.com/RJMetrics/simple-service-broker) on github.

### Core Functions

You use the functions in the `core` namespace to compose an endpoint. The namespace contains a set of functions for assembling a configuration map. It also has a function, `make-resource`, that accepts a configuration map and returns a Liberator resource. This resource function orchestrates the processing of the HTTP request when the corresponding endpoint is called. Refer to the [Liberator decision graph](https://clojure-liberator.github.io/liberator/tutorial/decision-graph.html) for more in-depth information on how Liberator does this.

To create this resource, typically you thread your base options through a series of functions. E.g.

```Clojure
(-> {:options ...
	 :liberator-config ...}
    add-exists
    add-get
    add-ok-handler
    (add-authorization some-auth-fn)
    make-resource)
```

Below are descriptions of the functions availble in `core` that apply the various operations that Sweet-Liberty supports. All of these functions take a configuration map as the first parameter.

#### Existence

Checking whether a resource already exists in the database is a critical step for any of the actions a route may perform. Every route will require a call to `add-exists` in order to function.

Function | Liberator Hooks | Description
---------|-----------------|------------
`add-exists`| exists? | Queries database for relevant resources. If field name is passed in as an argument, the query will use that field for the search condition.


#### Adding HTTP Methods

Function | Liberator Hooks | Description
---------|-----------------|------------
`add-get` | get |  Adds `GET` to the list of allowed methods.
`add-post` | post! | Performs a SQL `INSERT` when route receives a `POST` request. Resource values are read from the request body.
`add-put` | put!, new?, can-put-to-missing?, response-with-entity? | Performs a SQL `UPDATE` when route receives a `PUT` request. Resource values are read from the request body.
`add-delete` | delete! | Performs a SQL `DELETE` when route receives a `DELETE` request.


#### Adding Status Handlers

Function | Liberator handler | Description
---------|-------------------|------------
`add-ok-handler` | handle-ok | Executes whenever an HTTP status 200 is returned. Responsible for applying output transforms, expansion, conditions and controllers. Takes a `collection?` argument which indicates whether to return a single entity or a collection of entities.
`add-created-handler` | handle-created | Executes whenever an HTTP status 201 is returned. It retrieves the newly created entity from the database and applies output transforms, conditions and controllers.


#### Other Stuff

Function | Liberator handler | Description
---------|-------------------|------------
`add-post&handler` | post!, handle-created | Helper function that calls both `add-post` and `add-created-handler`.
`add-authorization` | allowed? | Sweet-Liberty requires that authorization (:allowed?) logic is explicitly set. This function takes either a function or a map of functions. In either case, the functions should be predicates (return boolean). In the case of a map, the keys should be HTTP methods (eg `:GET`) where the corresponding value is the appropriate function. If the function returns `true`, the request continues normally. Otherwise, a response of HTTP status 403 is returned.
`add-exception-handler` | handle-exception | Takes a Sweet-Liberty configuration map and a function. In the event of an exception, the function will be called and passed a context map, with the exception being associated to the `:exception` key.

### Helpers

In your routes, you can specify that the POST should be transformed and treated as a GET internally by using `transform-post-to-get` on the request.

Example:

```Clojure
(POST "/resource/:id/query" [id :as request]
  ((-> {:options .. :liberator-config ..}
      add-exists
      add-ok-handler
      make-resource)
   (transform-post-to-get request)))
```

## Order of Operations

Below is the ordered list of operations that may be applied to a request, which ultimately yields the response.

1. Authentication and authorization checks are executed.
   - set by `add-authorization`
2. Existence check -- set by `add-exists`
   - Paging, field selection, filtering and query transforms occur here.
3. Output Transforms are applied to any existing records retrieved.
   - If request is a GET, we are **done**. Response is returned. Otherwise, execution continues.
   - [documentation](#sweet-liberty-configuration)
4. Input Transforms are applied to incoming data.
   - [documentation](#sweet-liberty-configuration)
5. "Before" conditions are executed.
   - If the function returns false, an http status 400 will be returned.
   - [documentation](#conditions)
6. HTTP method specific function executes.
   - set by `add-get`, `add-post`, `add-put` or `add-delete`
   - Appropriate `INSERT`, `UPDATE` or `DELETE` sql statements will be executed against database at this step.
7. Resulting records are read from database.
8. "After" conditions are executed.
   - [documentation](#conditions)
9. Output transforms are applied to any out-going records.
   - [documentation](#sweet-liberty-configuration)
10. Expansions are executed and merged into result set.
    - specified in [configuration](#expansion-configuration)
11. Controller executes
    - [documentation](#sweet-liberty-configuration)
12. HTTP status handler is executed here
    - set by `add-ok-handler` or `add-created-handler`
13. Response is returned.

If an exception occurs that sweet-liberty itself throws, it uses [ex-info](https://clojure.github.io/clojure/clojure.core-api.html#clojure.core/ex-info) to generate the exception. This exception info will be logged, and it will be returned in the response only if the `return-exceptions?` option is true in the sweet-lib config.


## Logging with log4j

Sweet-Liberty has support for logging various events via [log4j](http://logging.apache.org/log4j/2.x/). You can include a log4j.properties file in your project in order to enable logging in Sweet-Liberty. Here's an example of what that might look like:

```
log4j.rootLogger=WARN
log4j.logger.user=INFO
log4j.logger.com.rjmetrics.sweet-liberty=CONSOLE, DAILY

log4j.appender.CONSOLE=org.apache.log4j.ConsoleAppender
log4j.appender.CONSOLE.layout=org.apache.log4j.PatternLayout
log4j.appender.CONSOLE.layout.ConversionPattern=%d{ISO8601} %p %t %c{1}.%M %m%n
log4j.appender.CONSOLE.Threshold=INFO

log4j.appender.DAILY=org.apache.log4j.DailyRollingFileAppender
log4j.appender.DAILY.File=logs/sweet-liberty-example-app
log4j.appender.DAILY.DatePattern='-'yyyy-MM-dd'.log'
log4j.appender.DAILY.layout=org.apache.log4j.PatternLayout
log4j.appender.DAILY.layout.ConversionPattern=%d{ISO8601} %p %t %c %m%n%n
log4j.appender.DAILY.Threshold=DEBUG
```

