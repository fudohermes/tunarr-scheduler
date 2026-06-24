(ns tunarr.scheduler.http.api.channels
  "HTTP handlers for channel operations."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.channels.sync :as channel-sync]
            [tunarr.scheduler.scheduling.pseudovision :as pv-schedule]))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn sync-channels-handler
  "Sync all channels to Pseudovision."
  [{:keys [pseudovision channels]}]
  (fn [_]
    (try
      (let [result (channel-sync/sync-all-channels! pseudovision channels)]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error syncing channels to Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn update-schedule-handler
  "Update channel schedule in Pseudovision."
  [{:keys [pseudovision]}]
  (fn [req]
    (try
      (let [channel-id   (get-in req [:parameters :path :channel-id])
            channel-spec (get-in req [:parameters :body])
            horizon      (get channel-spec :horizon 14)
            result       (pv-schedule/update-channel-schedule!
                          pseudovision
                          channel-id
                          channel-spec
                          {:horizon horizon})]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error creating channel schedule")
        {:status 500 :body {:error (.getMessage e)}}))))
