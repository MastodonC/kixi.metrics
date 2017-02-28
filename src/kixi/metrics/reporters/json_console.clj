(ns kixi.metrics.reporters.json-console
  (:require [cheshire
             [core :as json]
             [factory :as factory]
             [generate :as gen]]
            [metrics
             [core :as core :refer [default-registry]]
             [counters :as c]
             [gauges :as g]
             [histograms :as h]
             [meters :as m]
             [timers :as t]])
  (:import [com.codahale.metrics MetricFilter ScheduledReporter]
           com.fasterxml.jackson.core.JsonFactory
           [java.io BufferedWriter Writer]
           java.util.concurrent.TimeUnit))

(defn gauge->map
  [[gauge-name gauge]]
  {:type :gauge
   :name gauge-name
   :value (g/value gauge)})

(defn counter->map
  [[gauge-name gauge]]
  {:type :counter
   :name gauge-name
   :value (c/value gauge)})

(def sfirst (comp second first))

(def timer-fields
  {"min" t/smallest
   "max" t/largest
   "mean" t/mean
   "std-dev" t/std-dev
   "count" t/number-recorded
   "p50" #(sfirst (t/percentiles % [0.5]))
   "p75" #(sfirst (t/percentiles % [0.75]))
   "p95" #(sfirst (t/percentiles % [0.95]))
   "p98" #(sfirst (t/percentiles % [0.98]))
   "p99" #(sfirst (t/percentiles % [0.99]))
   "p999" #(sfirst (t/percentiles % [0.999]))
   "m1_rate" t/rate-one
   "m5_rate" t/rate-five
   "m15_rate" t/rate-fifteen
   "mean_rate" t/rate-mean})

(defn timer->map
  [[timer-name timer]]
  (map
   (fn [[n f]]
     {:type :timer
      :name (str timer-name "." n)
      :value (f timer)})
   timer-fields))

(def histo-fields
  {"count" h/number-recorded
   "max" h/largest
   "mean" h/mean
   "min" h/smallest
   "p50" #(sfirst (h/percentiles % [0.5]))
   "p75" #(sfirst (h/percentiles % [0.75]))
   "p95" #(sfirst (h/percentiles % [0.95]))
   "p98" #(sfirst (h/percentiles % [0.98]))
   "p99" #(sfirst (h/percentiles % [0.99]))
   "p999" #(sfirst (h/percentiles % [0.999]))})

(defn histo->map
  [[histo-name histo]]
  (map
   (fn [[n f]]
     {:type :histogram
      :name (str histo-name "." n)
      :value (f histo)})
   histo-fields))

(def meter-fields
  {"m1_rate" m/rate-one
   "m5_rate" m/rate-five
   "m15_rate" m/rate-fifteen
   "mean_rate" m/rate-mean})

(defn meter->map
  [[meter-name meter]]
  (map
   (fn [[n f]]
     {:type :meter
      :name (str meter-name "." n)
      :value (f meter)})
   meter-fields))

(def mapper->metric-getter
  {gauge->map core/gauges
   counter->map core/counters
   histo->map core/histograms
   meter->map core/meters
   timer->map core/timers})

(defn registry->maps
  [registry]
  (map 
   #(assoc % :log-type :metric)
   (flatten
    (map
     (fn [[mapper getter]]
       (map mapper (getter registry)))
     mapper->metric-getter))))

(defn get-lock
  [^Writer writer]
  (let [lock-field (.getDeclaredField Writer "lock")]    
    (.setAccessible lock-field true)
    (.get lock-field writer)))

(defn reporter
  ([opts]
   (reporter default-registry opts))
  ([registry {:keys [rate-unit duration-unit filter] :as opts
              :or {filter MetricFilter/ALL
                   rate-unit TimeUnit/SECONDS
                   duration-unit TimeUnit/MILLISECONDS}}]
   (let [name "Json-console-reporter"
         lock (get-lock *out*)]
     (proxy [ScheduledReporter] [registry name filter rate-unit duration-unit]
       (report
         ([]
          (doseq [metric (registry->maps registry)]            
            (locking lock
              (json/generate-stream
               metric
               *out*)
              (prn)))))))))

(defn start
  ([^ScheduledReporter reporter poll]
   (start reporter poll TimeUnit/SECONDS))
  ([^ScheduledReporter reporter poll poll-unit]
   (.start reporter
           poll poll-unit)))

(defn stop
  [^ScheduledReporter reporter]
  (.stop reporter))
