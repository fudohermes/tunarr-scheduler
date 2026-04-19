(ns tunarr.scheduler.media.pseudovision-sync
  "Sync catalog tags and metadata to Pseudovision.
   
   Replaces the Jellyfin tag sync - instead of pushing tags to Jellyfin
   (for ErsatzTV to read), we now push directly to Pseudovision's tag API."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Jellyfin ID Mapping
;; ---------------------------------------------------------------------------

(defn- build-jellyfin-id-map
  "Build a map of Jellyfin ID → Pseudovision media_item for fast lookup.
   
   This is needed because tunarr-scheduler's catalog uses Jellyfin IDs,
   but Pseudovision uses its own media_item IDs.
   
   Returns map: {jellyfin-id → {:id media-item-id, ...}}"
  [pv-config]
  (log/info "Building Jellyfin ID → Pseudovision media_item mapping")
  ;; TODO: This needs an efficient endpoint in Pseudovision
  ;; For now, return empty map - will need manual mapping or query optimization
  (log/warn "Jellyfin ID mapping not yet implemented - needs Pseudovision query endpoint")
  {})

;; ---------------------------------------------------------------------------
;; Tag Sync
;; ---------------------------------------------------------------------------

(defn sync-item-tags!
  "Sync tags for a single media item from catalog to Pseudovision.
   
   Args:
     pv-config - Pseudovision client config
     pv-item-id - Pseudovision media_items.id
     catalog - Catalog instance
     catalog-item-id - Item ID in catalog
   
   Returns:
     {:synced true/false, :tags [...], :error ...}"
  [pv-config pv-item-id catalog catalog-item-id]
  (try
    (let [tags (catalog/get-media-tags catalog catalog-item-id)
          tag-strings (map name tags)]  ; Convert keywords to strings
      (if (seq tag-strings)
        (do
          (pv/add-tags! pv-config pv-item-id tag-strings)
          (log/debug "Synced tags to Pseudovision" 
                    {:pv-item-id pv-item-id
                     :tags tag-strings})
          {:synced true :tags tag-strings})
        (do
          (log/debug "No tags to sync" {:pv-item-id pv-item-id})
          {:synced false :tags []})))
    (catch Exception e
      (log/error e "Failed to sync tags" {:pv-item-id pv-item-id})
      {:synced false :error (.getMessage e)})))

(defn sync-library-tags!
  "Sync all tags from catalog to Pseudovision for a library.
   
   This is the main entry point called by the API endpoint.
   
   Args:
     catalog - Catalog instance with tagged media
     pv-config - Pseudovision client config  
     library - Library name (keyword like :movies)
     opts - Options map with :report-progress function
   
   Returns:
     Map with :synced, :failed, :errors counts"
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))
        items (catalog/get-media-by-library catalog library)
        total (count items)
        id-map (build-jellyfin-id-map pv-config)]
    
    (log/info "Starting Pseudovision tag sync" 
              {:library library :items total})
    
    (report-progress {:phase "mapping" :current 0 :total total})
    
    (loop [remaining items
           idx 0
           synced 0
           failed 0
           errors []]
      (if (empty? remaining)
        (do
          (log/info "Pseudovision tag sync complete" 
                    {:library library 
                     :synced synced 
                     :failed failed})
          {:synced synced :failed failed :errors errors})
        
        (let [item (first remaining)
              jf-id (get item :jellyfin-id)
              pv-item (get id-map jf-id)]
          
          (if pv-item
            ;; Found matching Pseudovision item - sync tags
            (let [catalog-item-id (:id item)
                  result (sync-item-tags! pv-config (:id pv-item) catalog catalog-item-id)]
              (report-progress {:phase "syncing" :current (inc idx) :total total})
              (recur (rest remaining)
                     (inc idx)
                     (if (:synced result) (inc synced) synced)
                     (if (:error result) (inc failed) failed)
                     (if (:error result) 
                       (conj errors {:jellyfin-id jf-id :error (:error result)})
                       errors)))
            
            ;; No Pseudovision item found - skip
            (do
              (log/warn "No Pseudovision item found for Jellyfin ID" 
                       {:jellyfin-id jf-id :title (:title item)})
              (report-progress {:phase "syncing" :current (inc idx) :total total})
              (recur (rest remaining)
                     (inc idx)
                     synced
                     (inc failed)
                     (conj errors {:jellyfin-id jf-id 
                                  :error "No matching Pseudovision media item"})))))))))

;; ---------------------------------------------------------------------------
;; Convenience Wrappers
;; ---------------------------------------------------------------------------

(defn sync-all-libraries!
  "Sync tags for all configured libraries to Pseudovision.
   
   Returns map of library → sync results"
  [catalog pv-config libraries opts]
  (reduce
    (fn [results library]
      (log/info "Syncing library to Pseudovision" {:library library})
      (let [result (sync-library-tags! catalog pv-config library opts)]
        (assoc results library result)))
    {}
    libraries))
