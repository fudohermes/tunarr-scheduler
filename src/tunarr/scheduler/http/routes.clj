(ns tunarr.scheduler.http.routes
  "HTTP routes with OpenAPI documentation and Malli validation."
  (:require [reitit.ring                            :as ring]
            [reitit.openapi                         :as openapi]
            [reitit.swagger-ui                      :as swagger-ui]
            [reitit.coercion.malli                  :as malli-coercion]
            [reitit.ring.coercion                   :as rrc]
            [reitit.ring.middleware.parameters      :as parameters]
            [reitit.ring.middleware.muuntaja        :as muuntaja-mw]
            [taoensso.timbre :as log]
            [tunarr.scheduler.http.middleware       :as mw]
            [tunarr.scheduler.http.schemas          :as s]
            [tunarr.scheduler.jobs.runner           :as jobs]
            [tunarr.scheduler.media.sync            :as media-sync]
            [tunarr.scheduler.media.pseudovision-sync :as pv-sync]
            [tunarr.scheduler.media.pseudovision-migration :as pv-migration]
            [tunarr.scheduler.media.pseudovision-media-sync :as pv-media-sync]
            [tunarr.scheduler.scheduling.pseudovision :as pv-schedule]
            [tunarr.scheduler.channels.sync         :as channel-sync]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [tunarr.scheduler.curation.core         :as curate]
            [tunarr.scheduler.tunabrain             :as tunabrain]
            [tunarr.scheduler.media.catalog         :as catalog]))

;; ---------------------------------------------------------------------------
;; Handler functions
;; ---------------------------------------------------------------------------

(defn health-handler [_]
  {:status 200 :body {:status "ok"}})

(defn version-handler [_]
  {:status 200
   :body {:git-commit    (System/getenv "GIT_COMMIT")
          :git-timestamp (System/getenv "GIT_TIMESTAMP")
          :version-tag   (System/getenv "VERSION_TAG")}})

(defn list-libraries-handler [{:keys [pseudovision]}]
  (fn [_]
    (try
      (if-not pseudovision
        {:status 200 :body {:libraries []}}
        (let [pv-config (pv-client/get-config pseudovision)
              libraries (pv-client/list-all-libraries pv-config)]
          {:status 200 :body {:libraries libraries}}))
      (catch Exception e
        (log/error e "Error listing libraries")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn sync-libraries-handler [{:keys [catalog pseudovision]}]
  (fn [_]
    (try
      (if-not pseudovision
        {:status 400 :body {:error "Pseudovision is not configured"}}
        (let [pv-config   (pv-client/get-config pseudovision)
              libraries   (pv-client/list-all-libraries pv-config)
              library-map (into {} (map (fn [lib] [(keyword (:kind lib)) (:id lib)]) libraries))]
          (catalog/update-libraries! catalog library-map)
          (log/info "Synced libraries from Pseudovision" {:count (count library-map)})
          {:status 200 :body {:libraries libraries}}))
      (catch Exception e
        (log/error e "Error syncing libraries from Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn- submit-job!
  "Generic job submission handler."
  [job-runner job-type library error-msg job-fn]
  (if-not library
    {:status 400 :body {:error error-msg}}
    (let [job (jobs/submit! job-runner
                            {:type job-type
                             :metadata {:library library}}
                            (fn [report-progress]
                              (job-fn {:library         library
                                       :report-progress report-progress})))]
      {:status 202 :body {:job job}})))

(defn rescan-handler [{:keys [job-runner collection catalog]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/rescan
                     library
                     "library not specified for rescan"
                     (fn [opts] (media-sync/rescan-library! collection catalog opts))))
      (catch Exception e
        (log/error e "Error submitting rescan job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn retag-handler [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))]
        (submit-job! job-runner
                     :media/retag
                     library
                     "library not specified for retag"
                     (fn [opts] (curate/retag-library!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library
                                 {:force force}))))
      (catch Exception e
        (log/error e "Error submitting retag job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn tagline-handler [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/taglines
                     library
                     "library not specified for taglines"
                     (fn [opts] (curate/generate-library-taglines!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library))))
      (catch Exception e
        (log/error e "Error submitting tagline job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn recategorize-handler [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))]
        (submit-job! job-runner
                     :media/recategorize
                     library
                     "library not specified for recategorize"
                     (fn [opts] (curate/recategorize-library!
                                 (curate/->TunabrainCuratorBackend
                                  tunabrain catalog throttler curation-config)
                                 library
                                 {:force force}))))
      (catch Exception e
        (log/error e "Error submitting recategorize job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn retag-episodes-handler [{:keys [job-runner catalog tunabrain throttler curation-config]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])
            force   (= "true" (get-in req [:parameters :query :force]))]
        (submit-job! job-runner
                     :media/retag-episodes
                     library
                     "library not specified for episode retagging"
                     (fn [_opts] (curate/retag-library-episodes!
                                  (curate/->TunabrainCuratorBackend
                                   tunabrain catalog throttler curation-config)
                                  library
                                  {:force force}))))
      (catch Exception e
        (log/error e "Error submitting episode retag job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn pseudovision-sync-handler [{:keys [job-runner catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (submit-job! job-runner
                     :media/pseudovision-sync
                     library
                     "library not specified for pseudovision sync"
                     (fn [opts] (pv-sync/sync-library-tags! catalog
                                                             pseudovision
                                                             library))))
      (catch Exception e
        (log/error e "Error submitting Pseudovision sync job")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn migrate-to-pseudovision-handler [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [params              (get-in req [:parameters :body] {})
            dry-run?            (get params :dry-run false)
            include-categories? (get params :include-categories true)
            batch-size          (get params :batch-size 10)
            delay-ms            (get params :delay-ms 100)
            pv-config           (pv-client/get-config pseudovision)]
        
        (log/info "Starting Pseudovision migration" 
                  {:dry-run dry-run? 
                   :batch-size batch-size
                   :include-categories include-categories?})
        
        (let [result (pv-migration/migrate-all! 
                       catalog 
                       pv-config
                       {:dry-run dry-run?
                        :include-categories include-categories?
                        :batch-size batch-size
                        :delay-ms delay-ms})]
          
          {:status 200 
           :body (assoc result :message 
                        (if dry-run?
                          "Dry run complete - no changes made"
                          "Migration complete"))}))
      (catch Exception e
        (log/error e "Error during Pseudovision migration")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn sync-from-pseudovision-handler [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (when-not library
          (throw (ex-info "library parameter required" {:status 400})))
        
        (let [pv-config  (pv-client/get-config pseudovision)
              library-kw (keyword library)]
          
          (log/info "Syncing from Pseudovision" {:library library})
          
          (let [result (pv-media-sync/sync-library-from-pseudovision! 
                         catalog 
                         pv-config 
                         library-kw 
                         {})]
            {:status 200 :body (assoc result :message "Pseudovision sync complete")})))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          {:status (or (:status data) 500)
           :body {:error (.getMessage e)}}))
      (catch Exception e
        (log/error e "Error syncing from Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn migrate-catalog-ids-handler [{:keys [catalog pseudovision]}]
  (fn [req]
    (try
      (let [library (get-in req [:parameters :path :library])]
        (when-not library
          (throw (ex-info "library parameter required" {:status 400})))
        
        (let [pv-config  (pv-client/get-config pseudovision)
              library-kw (keyword library)]
          
          (log/info "Migrating catalog IDs to Pseudovision" {:library library})
          
          (let [result (pv-media-sync/migrate-catalog-to-pseudovision! 
                         catalog 
                         pv-config 
                         library-kw)]
            {:status 200 :body (assoc result :message "Catalog ID migration complete")})))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          {:status (or (:status data) 500)
           :body {:error (.getMessage e)}}))
      (catch Exception e
        (log/error e "Error migrating catalog IDs")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn audit-tags-handler [{:keys [tunabrain catalog]}]
  (fn [_]
    (try
      (let [tags (catalog/get-tags catalog)
            _ (log/info (format "Auditing %d tags" (count tags)))
            {:keys [recommended-for-removal]} (tunabrain/request-tag-audit! tunabrain tags)
            removal-count (count recommended-for-removal)
            removed-count (atom 0)]
        (log/info (format "Tunabrain recommended %d tags for removal" removal-count))
        (if (pos? removal-count)
          (doseq [{:keys [tag reason]} recommended-for-removal]
            (log/info (format "Removing tag '%s': %s" tag reason))
            (catalog/delete-tag! catalog (keyword tag))
            (swap! removed-count inc))
          (log/info "No tags recommended for removal"))
        (log/info (format "Tag audit complete: %d audited, %d removed"
                          (count tags) @removed-count))
        {:status 200
         :body {:tags-audited (count tags)
                :tags-removed @removed-count
                :removed recommended-for-removal}})
      (catch Exception e
        (log/error e "Error during tag audit")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn sync-channels-handler [{:keys [pseudovision channels]}]
  (fn [_]
    (try
      (let [result (channel-sync/sync-all-channels! pseudovision channels)]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error syncing channels to Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn update-schedule-handler [{:keys [pseudovision]}]
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

(defn list-jobs-handler [{:keys [job-runner]}]
  (fn [_]
    {:status 200
     :body {:jobs (jobs/list-jobs job-runner)}}))

(defn get-job-handler [{:keys [job-runner]}]
  (fn [req]
    (let [job-id (get-in req [:parameters :path :job-id])]
      (if-let [job (jobs/job-info job-runner job-id)]
        {:status 200 :body {:job job}}
        {:status 404 :body {:error "Job not found"}}))))

;; ---------------------------------------------------------------------------
;; Routes with OpenAPI metadata
;; ---------------------------------------------------------------------------

(defn routes [ctx]
  [""
   ;; ── OpenAPI spec ────────────────────────────────────────────────────────
   ["/openapi.json"
    {:get {:no-doc  true
           :openapi {:info {:title       "Tunarr Scheduler API"
                            :version     "0.1.0"
                            :description "Tunarr Scheduler REST API for media management and scheduling"}}
           :handler (openapi/create-openapi-handler)}}]

   ;; ── Health ──────────────────────────────────────────────────────────────
   ["/healthz"
    {:get {:tags      ["health"]
           :summary   "Health check endpoint"
           :responses {200 {:body s/Health}}
           :handler   health-handler}}]

   ;; ── Version ─────────────────────────────────────────────────────────────
   ["/api/version"
    {:get {:tags      ["meta"]
           :summary   "Build and version information"
           :responses {200 {:body s/Version}}
           :handler   version-handler}}]

   ;; ── Media ───────────────────────────────────────────────────────────────
   ["/api/media/libraries"
    {:tags ["media"]
     :get  {:summary   "List all media libraries from Pseudovision"
            :responses {200 {:body s/LibraryListResponse}
                        500 {:body s/APIError}}
            :handler   (list-libraries-handler ctx)}}]

   ["/api/media/sync-libraries"
    {:tags ["media"]
     :post {:summary   "Sync libraries from Pseudovision to catalog"
            :responses {200 {:body s/LibraryListResponse}
                        400 {:body s/APIError}
                        500 {:body s/APIError}}
            :handler   (sync-libraries-handler ctx)}}]

   ["/api/media/:library/rescan"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Trigger async library rescan job"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (rescan-handler ctx)}}]

   ["/api/media/:library/retag"
    {:tags       ["media"]
     :parameters {:path  [:map [:library s/LibraryName]]
                  :query s/ForceQuery}
     :post       {:summary   "Trigger async LLM retagging job"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (retag-handler ctx)}}]

   ["/api/media/:library/add-taglines"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Generate taglines for library media with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (tagline-handler ctx)}}]

   ["/api/media/:library/recategorize"
    {:tags       ["media"]
     :parameters {:path  [:map [:library s/LibraryName]]
                  :query s/ForceQuery}
     :post       {:summary   "Recategorize library media with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (recategorize-handler ctx)}}]

   ["/api/media/:library/retag-episodes"
    {:tags       ["media"]
     :parameters {:path  [:map [:library s/LibraryName]]
                  :query s/ForceQuery}
     :post       {:summary   "Retag episode special flags with LLM"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (retag-episodes-handler ctx)}}]

   ["/api/media/:library/sync-pseudovision-tags"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Sync library tags to Pseudovision (async job)"
                  :responses {202 {:body s/JobSubmitResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (pseudovision-sync-handler ctx)}}]

   ["/api/media/migrate-to-pseudovision"
    {:tags ["media"]
     :post {:summary    "One-time migration from local catalog to Pseudovision"
            :parameters {:body s/MigrateToPseudovisionRequest}
            :responses  {200 {:body s/MigrationResponse}
                         500 {:body s/APIError}}
            :handler    (migrate-to-pseudovision-handler ctx)}}]

   ["/api/media/:library/sync-from-pseudovision"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Sync media items from Pseudovision to catalog"
                  :responses {200 {:body s/SyncFromPseudovisionResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (sync-from-pseudovision-handler ctx)}}]

   ["/api/media/:library/migrate-catalog-ids"
    {:tags       ["media"]
     :parameters {:path [:map [:library s/LibraryName]]}
     :post       {:summary   "Migrate catalog IDs to use Pseudovision format"
                  :responses {200 {:body s/MigrateCatalogIdsResponse}
                              400 {:body s/APIError}
                              500 {:body s/APIError}}
                  :handler   (migrate-catalog-ids-handler ctx)}}]

   ["/api/media/tags/audit"
    {:tags ["media"]
     :post {:summary   "Audit all tags with LLM and remove unsuitable ones"
            :responses {200 {:body s/TagAuditResponse}
                        500 {:body s/APIError}}
            :handler   (audit-tags-handler ctx)}}]

   ;; ── Channels ────────────────────────────────────────────────────────────
   ["/api/channels/sync-pseudovision"
    {:tags ["channels"]
     :post {:summary   "Sync all channels to Pseudovision"
            :responses {200 {:body s/ChannelSyncResponse}
                        500 {:body s/APIError}}
            :handler   (sync-channels-handler ctx)}}]

   ["/api/channels/:channel-id/schedule"
    {:tags       ["channels"]
     :parameters {:path [:map [:channel-id s/ChannelId]]
                  :body s/ChannelScheduleRequest}
     :post       {:summary   "Update channel schedule in Pseudovision"
                  :responses {200 {:body s/ChannelScheduleResponse}
                              500 {:body s/APIError}}
                  :handler   (update-schedule-handler ctx)}}]

   ;; ── Jobs ────────────────────────────────────────────────────────────────
   ["/api/jobs"
    {:tags ["jobs"]
     :get  {:summary   "List all async jobs"
            :responses {200 {:body s/JobListResponse}}
            :handler   (list-jobs-handler ctx)}}]

   ["/api/jobs/:job-id"
    {:tags       ["jobs"]
     :parameters {:path [:map [:job-id s/JobId]]}
     :get        {:summary   "Get job status and details"
                  :responses {200 {:body s/JobInfoResponse}
                              404 {:body s/APIError}}
                  :handler   (get-job-handler ctx)}}]])

;; ---------------------------------------------------------------------------
;; Handler creation with middleware
;; ---------------------------------------------------------------------------

(defn handler
  "Create the ring handler with OpenAPI support.
   
   Route data supplies the canonical Reitit middleware chain - parameters,
   Muuntaja (JSON request decoding), the application exception handler, and
   malli coercion for :parameters / :responses. Routes without schemas still
   traverse the chain as a pass-through.
   
   Outer wraps - error handling, request logging, JSON response encoding -
   cover the entire dispatch tree so that unmatched routes (404/405) and
   the Swagger UI handler also go through them."
  [ctx]
  (let [dispatch (ring/ring-handler
                  (ring/router
                   (routes ctx)
                   {:data {:muuntaja   mw/muuntaja
                           :coercion   malli-coercion/coercion
                           :middleware [parameters/parameters-middleware
                                        muuntaja-mw/format-negotiate-middleware
                                        muuntaja-mw/format-request-middleware
                                        mw/exception-middleware
                                        rrc/coerce-request-middleware
                                        rrc/coerce-response-middleware]}})
                  (ring/routes
                   (swagger-ui/create-swagger-ui-handler
                    {:path "/swagger-ui"
                     :url  "/openapi.json"})
                   (ring/create-default-handler
                    {:not-found          (fn [_] {:status 404 :body {:error "Not found"}})
                     :method-not-allowed (fn [_] {:status 405 :body {:error "Method not allowed"}})})))]
    (-> dispatch
        mw/wrap-json-response
        mw/wrap-request-logging
        mw/wrap-error-handler)))
