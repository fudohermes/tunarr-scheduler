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

(defn- library-kind->catalog-library
  "Map Pseudovision library kind to tunarr-scheduler library keyword."
  [kind]
  (case kind
    "movies"       :movies
    "shows"        :shows
    "music_videos" :music-videos
    "other_videos" :other-videos
    "songs"        :songs
    "images"       :images
    (keyword kind)))

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
     library - Library name (keyword like :movies) or integer library ID
     opts - Options with :report-progress

   Returns:
     Map with :synced, :skipped, :errors counts

   Note: Existing tags in catalog are PRESERVED - we only update media
   metadata, not categorization data."
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))]

    (log/info "Syncing media FROM Pseudovision" {:library library})

    (try
      (let [library-id (if (integer? library)
                         library
                         ;; Find matching library by kind
                         (let [all-libs (pv/list-all-libraries pv-config)
                               lib-kind (name library)
                               matched  (first (filter #(= (:kind %) lib-kind) all-libs))]
                           (when-not matched
                             (throw (ex-info "No matching Pseudovision library found"
                                             {:library library :available (map :kind all-libs)})))
                           (:id matched)))]

        (log/info "Fetching items from Pseudovision library" {:library-id library-id})
        (let [item-stubs (pv/list-library-items pv-config library-id {:attrs "remote-key,kind,metadata,parent-id"})
              total      (count item-stubs)]

          (report-progress {:phase "fetching" :current 0 :total total})

          (loop [remaining item-stubs
                 idx    0
                 synced 0
                 skipped 0
                 errors []]
            (if (empty? remaining)
              (do
                (log/info "Pseudovision media sync complete"
                          {:library library :synced synced :skipped skipped})
                {:synced synced :skipped skipped :errors errors})

              (let [stub (first remaining)]
                (try
                  (let [pv-item (if (contains? stub :remote-key)
                                  stub
                                  (pv/get-media-item pv-config (:id stub)))
                        catalog-item (pseudovision-item->catalog-item pv-item)]
                    (catalog/add-media! catalog catalog-item)
                    (report-progress {:phase "syncing" :current (inc idx) :total total})
                    (recur (rest remaining) (inc idx) (inc synced) skipped errors))
                  (catch Exception e
                    (log/warn e "Failed to sync item" {:item-id (:id stub)})
                    (report-progress {:phase "syncing" :current (inc idx) :total total})
                    (recur (rest remaining) (inc idx) synced skipped
                           (conj errors {:item-id (:id stub) :error (.getMessage e)}))))))))

      (catch Exception e
        (log/error e "Failed to sync from Pseudovision")
        {:synced 0 :skipped 0 :errors [{:error (.getMessage e)}]}))))

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
    (let [catalog-items (catalog/get-media-by-library catalog library)
          total         (count catalog-items)]

      (log/info "Building Pseudovision item index for migration" {:items total})

      (loop [remaining catalog-items
             migrated 0
             skipped  0
             errors   []]
        (if (empty? remaining)
          (do
            (log/info "Migration complete" {:library library :migrated migrated :skipped skipped})
            {:migrated migrated :skipped skipped :errors errors})

          (let [item    (first remaining)
                jf-id   (:jellyfin-id item)]
            (if-not jf-id
              (recur (rest remaining) migrated (inc skipped) errors)
              (try
                (let [pv-item (pv/find-media-item-by-jellyfin-id pv-config jf-id)]
                  (if pv-item
                    (do
                      (catalog/add-media! catalog (assoc item :id (:id pv-item)))
                      (recur (rest remaining) (inc migrated) skipped errors))
                    (do
                      (log/warn "No Pseudovision item found for Jellyfin ID"
                                {:jellyfin-id jf-id :title (:title item)})
                      (recur (rest remaining) migrated (inc skipped) errors))))
                (catch Exception e
                  (log/error e "Migration failed for item" {:jellyfin-id jf-id})
                  (recur (rest remaining) migrated skipped
                         (conj errors {:jellyfin-id jf-id :error (.getMessage e)}))))))))

    (catch Exception e
      (log/error e "Migration failed")
      {:migrated 0 :skipped 0 :errors [{:error (.getMessage e)}]})))

(comment
  ;; Usage example:

  ;; One-time: Migrate existing catalog
  (migrate-catalog-to-pseudovision! catalog pv-config :movies)

  ;; Future: Sync from Pseudovision instead of Jellyfin
  (sync-library-from-pseudovision! catalog pv-config :movies {}))
