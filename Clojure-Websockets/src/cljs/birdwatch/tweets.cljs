(ns birdwatch.tweets
  (:require-macros [cljs.core.async.macros :refer [go-loop go alt!]])
  (:require [birdwatch.channels :as c]
            [birdwatch.util :as util]
            [birdwatch.timeseries :as ts]
            [birdwatch.wordcount :as wc]
            [birdwatch.state :as state]
            [cljs.core.async :as async :refer [<! >! chan put! alts! timeout]]))

(enable-console-print!)

#_(defn add-to-tweets-map [app tweets-map tweet]
  "adds tweet to tweets-map"
  (swap! app
         assoc-in [tweets-map (keyword (:id_str tweet))]
         tweet))

(defn add-to-tweets-map [app tweets-map tweet]
  "adds tweet to tweets-map"
  (swap! app
         assoc-in [tweets-map (keyword (:id_str tweet))]
         (if (= 0 (mod (:id tweet) 10))
           tweet
           {:created_at (:created_at tweet)
            :id_str (:id_str tweet)})))

(defn swap-when-larger [app priority-map rt-id n]
  "swaps item in priority-map when new value is larger than old value"
  (when (> n (rt-id (priority-map @app))) (util/swap-pmap app priority-map rt-id n)))

(defn add-rt-status [app tweet]
  "handles original, retweeted tweet"
  (if (contains? tweet :retweeted_status)
    (let [state @app
          rt (:retweeted_status tweet)
          rt-id (keyword (:id_str rt))
          rt-count (:retweet_count rt)]
      (swap-when-larger app :by-retweets rt-id rt-count)
      (swap-when-larger app :by-favorites rt-id (:favorite_count rt))
      (util/swap-pmap app :by-rt-since-startup rt-id (inc (get (:by-rt-since-startup state) rt-id 0)))
      (when (> rt-count (:retweet_count (rt-id (:retweets state)))) (add-to-tweets-map app :retweets rt)))))

(defn add-tweet [tweet app]
  "increment counter, add tweet to tweets map and to sorted sets by id and by followers"
  (let [state @app]
    (swap! app assoc :count (inc (:count state)))
    (add-to-tweets-map app :tweets-map tweet)
    (util/swap-pmap app :by-followers (keyword (:id_str tweet)) (:followers_count (:user tweet)))
    (util/swap-pmap app :by-id (keyword (:id_str tweet)) (:id_str tweet))
    (add-rt-status app tweet)
    (wc/process-tweet app (:text tweet))))

(go-loop []
 (let [[t chan] (alts! [c/tweets-chan c/prev-tweets-chan] {:priority true})]
   (add-tweet t state/app)
   (recur)))

(go-loop []
         (let [chunk (<! c/prev-chunks-chan)]
           (doseq [t chunk]
             ;(when (= 0 (mod (:id t) 500)) (<! (timeout 0)))
             (put! c/prev-tweets-chan t))
           (<! (timeout 50))
           (recur)))
