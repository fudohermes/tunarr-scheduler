(ns tunarr.scheduler.media.pseudovision-media-sync
  "Sync media items FROM Pseudovision TO tunarr-scheduler catalog.
   
   This replaces the Jellyfin → tunarr-scheduler sync with
   Pseudovision → tunarr-scheduler sync, making Pseudovision the
   single source of truth for media discovery.
   
   Workflow:
   1. Pseudovision scans Jellyfin libraries (separate process)
   2. tunarr-scheduler pulls media items from Pseudovision API
   3. tunarr-scheduler adds LLM categorization/tags to catalog
   4. tunarr-scheduler pushes tags back to Pseudovision
   
   Benefits:
   - Single media discovery pipeline (no duplicate Jellyfin scans)
   - Pseudovision owns media metadata
   - tunarr-scheduler focuses on curation only"
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Media Item Mapping
;; ---------------------------------------------------------------------------

(defn- pseudovision-item->catalog-item
  "Convert a Pseudovision media_item to tunarr-scheduler catalog format.
   
   Preserves Jellyfin ID mapping for tag sync."
  [pv-item]
  {:id           (:id pv-item)              ; Pseudovision media_item.id
   :jellyfin-id  (:remote-key pv-item)      ; Maps back to Jellyfin
   :title        (get-in pv-item [:metadata :title])
   :kind         (:kind pv-item)            ; movie, episode, etc.
   :parent-id    (:parent-id pv-item)       ; For TV shows
   :release-date (get-in pv-item [:metadata :release-date])})

;; ---------------------------------------------------------------------------
;; Sync FROM Pseudovision
;; ---------------------------------------------------------------------------

(defn sync-library-from-pseudovision!
  "Sync media items from Pseudovision into tunarr-scheduler catalog.
   
   This REPLACES the Jellyfin sync - instead of querying Jellyfin directly,
   we pull from Pseudovision which has already scanned Jellyfin.
   
   Args:
     catalog - Catalog instance to update
     pv-config - Pseudovision client config
     library - Library name (keyword like :movies)
     opts - Options with :report-progress
   
   Returns:
     Map with :synced, :skipped, :errors counts
   
   Note: Existing tags in catalog are PRESERVED - we only update media
   metadata, not categorization data."
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))]
    
    (log/info "Syncing media FROM Pseudovision" {:library library})
    
    (try
      ;; TODO: Implement Pseudovision query endpoint for library-specific items
      ;; For now, query all media items and filter client-side
      
      ;; GET /api/media/items?library-id=xxx or ?kind=movie
      (log/warn "Pseudovision media sync not yet implemented - needs query endpoint")
      (log/info "Recommendation: Add GET /api/media/items?source-id=X&library-id=Y to Pseudovision")
      
      {:synced 0
       :skipped 0  
       :errors 1
       :error "Pseudovision media query endpoint not yet implemented"}
      
      (catch Exception e
        (log/error e "Failed to sync from Pseudovision")
        {:synced 0 :skipped 0 :errors 1 :error (.getMessage e)}))))

;; ---------------------------------------------------------------------------
;; Migration Helper
;; ---------------------------------------------------------------------------

(defn migrate-catalog-to-pseudovision!
  "One-time migration: Match existing catalog items to Pseudovision by Jellyfin ID.
   
   This preserves all LLM tags and categorization while switching the
   sync source from Jellyfin to Pseudovision.
   
   Process:
   1. For each item in tunarr-scheduler catalog
   2. Find matching Pseudovision media_item by remote_key (Jellyfin ID)
   3. Update catalog item's :id to Pseudovision media_item.id
   4. Preserve all tags and categories
   
   After migration, future syncs use Pseudovision as source."
  [catalog pv-config library]
  (log/info "Migrating catalog to use Pseudovision IDs" {:library library})
  
  (try
    ;; TODO: Implement
    ;; 1. Get all catalog items for library
    ;; 2. For each, query Pseudovision: GET /api/media/items?remote_key=:jellyfin-id
    ;; 3. Update catalog item ID mapping
    ;; 4. Keep all tags/categories
    
    (log/warn "Migration not yet implemented")
    {:migrated 0 :skipped 0 :errors 1}
    
    (catch Exception e
      (log/error e "Migration failed")
      {:migrated 0 :errors 1 :error (.getMessage e)})))

(comment
  ;; Usage example:
  
  ;; One-time: Migrate existing catalog
  (migrate-catalog-to-pseudovision! catalog pv-config :movies)
  
  ;; Future: Sync from Pseudovision instead of Jellyfin
  (sync-library-from-pseudovision! catalog pv-config :movies {}))
