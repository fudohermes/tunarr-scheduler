(ns tunarr.scheduler.backends.pseudovision.client
  "Pseudovision IPTV platform HTTP client.
   
   Provides integration with Pseudovision's native scheduling engine,
   tag management, and streaming capabilities."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [tunarr.scheduler.backends.protocol :as proto]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; HTTP Client Helpers
;; ---------------------------------------------------------------------------

(defn- api-url [config path]
  (str (:base-url config) path))

(defn- request!
  "Make HTTP request to Pseudovision API with error handling."
  [method url opts]
  (try
    (let [response (http/request (merge {:method method
                                         :url url
                                         :accept :json
                                         :as :json
                                         :throw-exceptions false}
                                        opts))]
      (if (<= 200 (:status response) 299)
        (:body response)
        (do
          (log/error "Pseudovision API error" 
                    {:status (:status response)
                     :url url
                     :body (:body response)})
          (throw (ex-info "Pseudovision API error"
                         {:status (:status response)
                          :url url
                          :response (:body response)})))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (log/error e "HTTP request failed" {:url url})
      (throw (ex-info "HTTP request failed" {:url url} e)))))

;; ---------------------------------------------------------------------------
;; Tag Management
;; ---------------------------------------------------------------------------

(defn add-tags!
  "Add tags to a media item in Pseudovision.
   
   Args:
     config - Pseudovision config map with :base-url
     media-item-id - Pseudovision media_items.id
     tags - Vector of tag strings
   
   Returns:
     Response map with :item-id and :tags-added"
  [config media-item-id tags]
  (request! :post
            (api-url config (str "/api/media-items/" media-item-id "/tags"))
            {:content-type :json
             :form-params {:tags tags}}))

(defn get-tags
  "Get all tags for a media item.
   
   Returns vector of tag strings."
  [config media-item-id]
  (request! :get
            (api-url config (str "/api/media-items/" media-item-id "/tags"))
            {}))

(defn delete-tag!
  "Remove a specific tag from a media item."
  [config media-item-id tag]
  (request! :delete
            (api-url config (str "/api/media-items/" media-item-id "/tags/" tag))
            {}))

(defn list-all-tags
  "List all unique tags with usage counts.
   
   Returns vector of maps: [{:name 'comedy' :count 42} ...]"
  [config]
  (request! :get
            (api-url config "/api/tags")
            {}))

;; ---------------------------------------------------------------------------
;; Media Discovery
;; ---------------------------------------------------------------------------

(defn find-media-item-by-jellyfin-id
  "Find a Pseudovision media item by its Jellyfin remote_key.
   
   Returns media item map or nil if not found."
  [config jellyfin-id]
  ;; TODO: Implement this - needs a query endpoint in Pseudovision
  ;; For now, we'll need to query all items and filter
  (log/warn "find-media-item-by-jellyfin-id not yet optimized" 
            {:jellyfin-id jellyfin-id})
  nil)

(defn get-collections
  "List all collections (smart/manual/playlist)."
  [config]
  (request! :get
            (api-url config "/api/collections")
            {}))

;; ---------------------------------------------------------------------------
;; Schedule Management
;; ---------------------------------------------------------------------------

(defn create-schedule!
  "Create a new schedule.
   
   Args:
     config - Pseudovision config
     schedule-data - Map with :name and optional :description
   
   Returns:
     Created schedule with :id"
  [config schedule-data]
  (request! :post
            (api-url config "/api/schedules")
            {:content-type :json
             :form-params schedule-data}))

(defn add-slot!
  "Add a slot to a schedule.
   
   Slot data fields:
     :slot-index - Position in schedule (0-based)
     :anchor - 'fixed' or 'sequential'
     :start-time - Time string like '18:00:00' (for fixed anchors)
     :fill-mode - 'once', 'count', 'block', or 'flood'
     :item-count - Number of items (for count mode)
     :block-duration - Duration string like 'PT2H' (for block mode)
     :collection-id - Collection to pull from
     :media-item-id - Specific item (overrides collection)
     :required-tags - Vector of tags (item must have ALL)
     :excluded-tags - Vector of tags (item must have NONE)
     :playback-order - 'chronological', 'random', 'shuffle', 'semi-sequential', etc.
     :marathon-batch-size - For semi-sequential mode
   
   Returns:
     Created slot with :id"
  [config schedule-id slot-data]
  (request! :post
            (api-url config (str "/api/schedules/" schedule-id "/slots"))
            {:content-type :json
             :form-params slot-data}))

(defn get-schedule
  "Get schedule details including slots."
  [config schedule-id]
  (request! :get
            (api-url config (str "/api/schedules/" schedule-id))
            {}))

(defn list-schedules
  "List all schedules."
  [config]
  (request! :get
            (api-url config "/api/schedules")
            {}))

;; ---------------------------------------------------------------------------
;; Channel & Playout Management
;; ---------------------------------------------------------------------------

(defn list-channels
  "List all channels."
  [config]
  (request! :get
            (api-url config "/api/channels")
            {}))

(defn get-channel
  "Get channel by ID."
  [config channel-id]
  (request! :get
            (api-url config (str "/api/channels/" channel-id))
            {}))

(defn create-channel!
  "Create a new channel.
   
   Channel data:
     :name - Channel name
     :uuid - Channel UUID (optional, will be generated if not provided)
     :number - Channel number string
     :description - Channel description (optional)
   
   Returns:
     Created channel with assigned :id"
  [config channel-data]
  (request! :post
            (api-url config "/api/channels")
            {:content-type :json
             :form-params channel-data}))

(defn update-channel!
  "Update channel configuration.
   
   Common updates:
     :schedule-id - Attach a schedule to the channel
     :name - Update channel name
     :number - Update channel number"
  [config channel-id updates]
  (request! :put
            (api-url config (str "/api/channels/" channel-id))
            {:content-type :json
             :form-params updates}))

(defn rebuild-playout!
  "Trigger playout rebuild for a channel.
   
   Options:
     :from - 'now' (delete all future events) or 'horizon' (extend future)
     :horizon - Number of days to generate (default 14)
   
   Returns:
     Map with :message, :events-generated, :horizon-days"
  [config channel-id & [{:keys [from horizon] :or {from "now" horizon 14}}]]
  (request! :post
            (api-url config (str "/api/channels/" channel-id "/playout"))
            {:query-params {"from" from "horizon" (str horizon)}}))

;; ---------------------------------------------------------------------------
;; Health & Version
;; ---------------------------------------------------------------------------

(defn health-check
  "Check if Pseudovision is reachable and healthy.
   
   Returns map with :status and :version info"
  [config]
  (try
    (let [version (request! :get (api-url config "/api/version") {})]
      {:status "ok"
       :version version
       :reachable true})
    (catch Exception e
      (log/error e "Pseudovision health check failed")
      {:status "error"
       :error (.getMessage e)
       :reachable false})))

;; ---------------------------------------------------------------------------
;; Backend Protocol Implementation
;; ---------------------------------------------------------------------------

(defrecord PseudovisionBackend [config]
  proto/ChannelBackend
  
  (create-channel [_ channel-spec]
    (log/info "Creating Pseudovision channel" {:name (:name channel-spec)})
    (create-channel! config channel-spec))
  
  (update-channel [_ channel-id updates]
    (update-channel! config channel-id updates))
  
  (delete-channel [_ channel-id]
    (try
      (request! :delete
                (api-url config (str "/api/channels/" channel-id))
                {})
      {:success true}
      (catch Exception e
        {:success false :message (.getMessage e)})))
  
  (get-channels [_]
    (list-channels config))
  
  (upload-schedule [this channel-id schedule]
    ;; Convert schedule spec to Pseudovision schedule+slots
    (log/info "Uploading schedule to Pseudovision" 
              {:channel-id channel-id
               :slots (count (:slots schedule))})
    
    ;; Create schedule
    (let [sched (create-schedule! config {:name (:name schedule)})
          schedule-id (:id sched)]
      
      ;; Create slots
      (doseq [[idx slot] (map-indexed vector (:slots schedule))]
        (add-slot! config schedule-id
                  (assoc slot :slot-index idx)))
      
      ;; Attach schedule to channel and rebuild
      (update-channel! config channel-id {:schedule-id schedule-id})
      (rebuild-playout! config channel-id {:from "now" :horizon 14})
      
      {:success true :schedule-id schedule-id}))
  
  (get-schedule [_ channel-id]
    (let [channel (get-channel config channel-id)
          schedule-id (:schedule-id channel)]
      (when schedule-id
        (get-schedule config schedule-id))))
  
  (validate-config [_ config]
    (let [base-url (:base-url config)]
      (if (and base-url (string? base-url) (not (empty? base-url)))
        (let [health (health-check config)]
          (if (:reachable health)
            {:valid? true :version (:version health)}
            {:valid? false
             :errors ["Pseudovision is not reachable" (:error health)]}))
        {:valid? false
         :errors ["base-url is required and must be a non-empty string"]}))))

(defn create
  "Create a Pseudovision backend client.
   
   Config map should include:
     :base-url - Pseudovision API base URL (e.g. 'https://pseudovision.kube.sea.fudo.link')
   
   Returns:
     PseudovisionBackend record implementing ChannelBackend protocol"
  [config]
  (log/info "Creating Pseudovision backend client" {:base-url (:base-url config)})
  (->PseudovisionBackend config))
