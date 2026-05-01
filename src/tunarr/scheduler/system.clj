(ns tunarr.scheduler.system
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [tunarr.scheduler.http.server :as http]
            [tunarr.scheduler.jobs.runner :as job-runner]
            [tunarr.scheduler.media :as media]
            [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.sql-catalog]
            [tunarr.scheduler.media.collection :as collection]
            [tunarr.scheduler.media.pseudovision-collection]
            [tunarr.scheduler.curation.tags :as tag-curator]
            [tunarr.scheduler.curation.core :as curation]
            [tunarr.scheduler.jobs.throttler :as job-throttler]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.backends.protocol :as backend-protocol]
            [tunarr.scheduler.backends.pseudovision.client :as pseudovision]))

(defmethod ig/init-key :tunarr/logger [_ {:keys [level]}]
  (log/set-level! level)
  (log/info "Logger initialised" {:level level})
  {:level level})

(defmethod ig/halt-key! :tunarr/logger [_ _]
  (log/info "Logger shut down"))

(defmethod ig/init-key :tunarr/tunabrain [_ config]
  (log/info "initializing tunabrain client")
  (tunabrain/create! config))

(defmethod ig/halt-key! :tunarr/tunabrain [_ client]
  (log/info "closing tunabrain client")
  (.close ^java.io.Closeable client))

;; TODO: Implement TTS client when text-to-speech functionality is needed
(defmethod ig/init-key :tunarr/tts [_ config]
  (log/info "tts client initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/tts [_ client]
  (log/info "tts client shutdown disabled (not yet implemented)")
  nil)

(defmethod ig/init-key :tunarr/job-runner [_ config]
  (log/info "initializing job runner")
  (job-runner/create config))

(defmethod ig/halt-key! :tunarr/job-runner [_ runner]
  (log/info "shutting down job runner")
  (job-runner/shutdown! runner))

(defmethod ig/init-key :tunarr/collection [_ config]
  (log/info "initializing media collection")
  (collection/initialize-collection! config))

(defmethod ig/halt-key! :tunarr/collection [_ collection]
  (log/info "closing media collection")
  (collection/close! collection))

;; TODO: Implement Tunarr source when needed for pulling data from Tunarr API
(defmethod ig/init-key :tunarr/tunarr-source [_ config]
  (log/info "tunarr source initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/tunarr-source [_ _]
  (log/info "tunarr source shutdown disabled (not yet implemented)")
  nil)

(defmethod ig/init-key :tunarr/catalog [_ config]
  (log/info "initializing catalog")
  (catalog/initialize-catalog! config))

(defmethod ig/halt-key! :tunarr/catalog [_ state]
  (log/info "closing catalog")
  (catalog/close-catalog! state))

(defmethod ig/init-key :tunarr/tunabrain-throttler [_ {:keys [rate queue-size]}]
  (log/info "initializing tunabrain throttler")
  (let [throttler (job-throttler/create :rate rate :queue-size queue-size)]
    (job-throttler/start! throttler)
    throttler))

(defmethod ig/halt-key! :tunarr/tunabrain-throttler [_ throttler]
  (log/info "closing tunabrain throttler")
  (job-throttler/stop! throttler))

(defmethod ig/init-key :tunarr/curation
  [_ {:keys [tunabrain catalog throttler libraries config]}]
  (log/info "starting curator")
  (log/warn "constant curation not enabled for now")
  #_(let [curator (curation/create! {:tunabrain tunabrain
                                     :catalog   catalog
                                     :throttler throttler
                                     :config    config})]
      (curation/start! curator libraries)
      curator))

(defmethod ig/halt-key! :tunarr/curation
  [_ curator]
  (curation/stop! curator))

(defn- validate-channels! [channels]
  (doseq [[channel-key channel-cfg] channels]
    (let [missing (cond-> []
                    (nil? (::media/channel-id channel-cfg))          (conj ":id")
                    (nil? (::media/channel-fullname channel-cfg))    (conj ":name")
                    (nil? (::media/channel-description channel-cfg)) (conj ":description"))]
      (when (seq missing)
        (throw (ex-info (format "Channel %s is missing required config fields: %s. Set these under :channels > %s in your config."
                                (name channel-key)
                                (str/join ", " missing)
                                (name channel-key))
                        {:channel channel-key :missing missing}))))))

(defn- resolve-library-ids
  "Look up Pseudovision IDs for each library name, matching by :name field."
  [collection-config library-names]
  (let [pv-libraries (pseudovision/list-all-libraries collection-config)]
    (reduce (fn [acc lib-name]
              (if-let [match (some #(when (= (name lib-name) (:name %)) %) pv-libraries)]
                (assoc acc lib-name (:id match))
                (throw (ex-info (format "Library '%s' not found in Pseudovision. Available libraries: %s"
                                        (name lib-name)
                                        (str/join ", " (map :name pv-libraries)))
                                {:library lib-name
                                 :available (mapv :name pv-libraries)}))))
            {}
            library-names)))

(defmethod ig/init-key :tunarr/config-sync [_ {:keys [channels library-names collection-config catalog]}]
  (when (not channels)
    (throw (ex-info "missing required key: channels" {})))
  (validate-channels! channels)
  (log/info (format "syncing channels with config: %s"
                    (str/join "," (map name (keys channels)))))
  (catalog/update-channels! catalog channels)
  (log/info (format "resolving library IDs from Pseudovision for: %s"
                    (str/join "," (map name library-names))))
  (let [libraries (resolve-library-ids collection-config library-names)]
    (log/info (format "syncing libraries: %s" libraries))
    (catalog/update-libraries! catalog libraries))
  channels)

(defmethod ig/init-key :tunarr/normalize-tags
  [_ {:keys [catalog tag-config]}]
  (tag-curator/normalize! catalog tag-config))

(defmethod ig/halt-key! :tunarr/normalize-tags
  [_]
  nil)

(defmethod ig/halt-key! :tunarr/config-sync [_ _]
  (log/info "shutting down channel sync")
  nil)

(defmethod ig/init-key :tunarr/pseudovision [_ config]
  (if (and config (:base-url config) (not= "" (:base-url config)))
    (do
      (log/info "Initializing Pseudovision backend" {:base-url (:base-url config)})
      (let [client (pseudovision/create config)
            validation (backend-protocol/validate-config client config)]
        (if (:valid? validation)
          (do
            (log/info "Pseudovision backend validated successfully"
                      {:version (:version validation)})
            client)
          (do
            (log/error "Pseudovision backend validation failed"
                       {:errors (:errors validation)})
            (throw (ex-info "Pseudovision validation failed" validation))))))
    (do (log/warn "Pseudovision backend not configured - skipping initialization"
                  {:config config})
        nil)))

(defmethod ig/halt-key! :tunarr/pseudovision [_ client]
  (log/info "Shutting down Pseudovision backend")
  nil)

(defmethod ig/init-key :tunarr/backends [_ config]
  (log/info "initializing backends" {:backends (keys config)})
  (let [clients (reduce
                 (fn [acc [backend-key backend-config]]
                   (if (:enabled backend-config)
                     (let [client (case backend-key
                                    ;; Note: ersatztv backend removed - was never fully implemented
                                    ;; :tunarr backend also not implemented yet
                                    (do
                                      (log/warn "Unknown backend type" {:backend backend-key})
                                      nil))]
                       (if client
                         (do
                           (log/info "Created backend client" {:backend backend-key})
                           (assoc acc backend-key client))
                         acc))
                     acc))
                 {}
                 config)]
    (log/info "backends initialized" {:enabled (keys clients)})
    {:config config
     :clients clients}))

(defmethod ig/halt-key! :tunarr/backends [_ backends]
  (log/info "shutting down backends")
  nil)

;; TODO: Implement scheduler engine for automated channel programming
(defmethod ig/init-key :tunarr/scheduler [_ {:keys [time-zone daytime-hours seasonal preferences]
                                             :as config}]
  (log/info "scheduler engine initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/scheduler [_ engine]
  (log/info "scheduler engine shutdown disabled (not yet implemented)")
  nil)

;; TODO: Implement bumpers service for generating inter-program content
(defmethod ig/init-key :tunarr/bumpers [_ {:keys [tunabrain tts]}]
  (log/info "bumpers service initialization disabled (not yet implemented)")
  nil)

(defmethod ig/halt-key! :tunarr/bumpers [_ svc]
  (log/info "bumpers service shutdown disabled (not yet implemented)")
  nil)

(defmethod ig/init-key :tunarr/http-server [_ {:keys [port scheduler media tts bumpers tunarr catalog logger job-runner collection tunabrain throttler backends curation-config pseudovision channels]}]
  (http/start! {:port port
                :job-runner job-runner
                :collection collection
                :catalog catalog
                :tunabrain tunabrain
                :throttler throttler
                :backends backends
                :pseudovision pseudovision
                :channels channels
                :curation-config curation-config}))

(defmethod ig/halt-key! :tunarr/http-server [_ server]
  (http/stop! server))

(defn start
  ([system-config]
   (ig/init system-config))
  ([system-config opts]
   (ig/init system-config opts)))

(defn stop [system]
  (ig/halt! system))
