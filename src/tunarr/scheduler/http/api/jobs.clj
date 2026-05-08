(ns tunarr.scheduler.http.api.jobs
  "HTTP handlers for async job management."
  (:require [tunarr.scheduler.jobs.runner :as jobs]))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn list-jobs-handler
  "List all async jobs."
  [{:keys [job-runner]}]
  (fn [_]
    {:status 200
     :body {:jobs (jobs/list-jobs job-runner)}}))

(defn get-job-handler
  "Get job status and details."
  [{:keys [job-runner]}]
  (fn [req]
    (let [job-id (get-in req [:parameters :path :job-id])]
      (if-let [job (jobs/job-info job-runner job-id)]
        {:status 200 :body {:job job}}
        {:status 404 :body {:error "Job not found"}}))))
