(defproject com.rjmetrics/sweet-liberty "2.0.2"
  :description "A tool to build Liberatingly Sweet REST Resources"
  :url "https://github.com/RJMetrics/sweet-liberty"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repositories [["snapshots" {:url "s3://rjmetrics-private-m2-repository/snapshots"
                               :username :env
                               :passphrase :env}]
                 ["releases" {:url "s3://rjmetrics-private-m2-repository/releases"
                              :username :env
                              :passphrase :env}]
                 ["conjars" {:url "http://conjars.org/repo"}]]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [liberator "0.12.0"]
                 [honeysql "0.4.3"]
                 [mysql/mysql-connector-java "8.0.20"]
                 [org.clojars.runa/conjure "2.2.0"]
                 [org.clojure/java.jdbc "0.3.3"]
                 [org.clojure/data.json "0.2.4"]
                 [org.clojure/tools.logging "0.2.6"]
                 [org.slf4j/slf4j-log4j12 "1.6.1"]
                 [camel-snake-kebab "0.3.2" :exclusions [org.clojure/clojure]]
                 [log4j/log4j "1.2.15" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jdmk/jmxtools
                                                    com.sun.jmx/jmxri]]]
  :profiles {:dev {:dependencies [[midje "1.6.2"]
                                  [compojure "1.1.6"]
                                  [org.hsqldb/hsqldb "2.2.4"]
                                  [org.clojure/clojure-contrib "1.2.0"]
                                  [org.clojure/java.jdbc "0.3.3"]
                                  [org.clojars.runa/conjure "2.2.0"]
                                  [ring-mock "0.1.5"]
                                  [ring/ring-core "1.2.1"]
                                  [ring-middleware-format "0.3.2"]]
                   :plugins [[lein-midje "3.1.3"]
                             [s3-wagon-private "1.1.2"]
                             [codox "0.6.7"]
                             [lein-kibit "0.0.8"]
                             [jonase/eastwood "0.1.1"]
                             [lein-release "1.0.5"]]}})
