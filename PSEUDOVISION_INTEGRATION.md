# Pseudovision Integration Design

## Executive Summary

This document outlines the migration from ErsatzTV/Tunarr backend integration to **Pseudovision**, a custom IPTV streaming platform with native scheduling engine and tag-based content selection.

**Key Change:** Instead of syncing tags to Jellyfin → ErsatzTV reading from Jellyfin → building schedules in ErsatzTV, we now **directly manage tags and schedules in Pseudovision via its HTTP API**.

---

## Architecture Comparison

### **Current Architecture (ErsatzTV/Tunarr)**

```
┌──────────────────┐
│ tunarr-scheduler │ - Pulls media from Jellyfin
│                  │ - Categorizes with Tunabrain (LLM)
└────────┬─────────┘ - Stores tags in memory/disk
         │
         │ (1) Sync tags via Jellyfin API
         ▼
┌──────────────────┐
│    Jellyfin      │ - Stores tags on media items
└────────┬─────────┘ - Media server
         │
         │ (2) Library sync
         ▼
┌──────────────────┐
│    ErsatzTV      │ - Reads tags from Jellyfin
│                  │ - Smart collections based on tags
│                  │ - Sequential schedules (YAML)
└──────────────────┘ - Playout + streaming
```

**Problems:**
- 3-hop data flow (scheduler → Jellyfin → ErsatzTV)
- Tag sync via Jellyfin is fragile (API bugs, ETags)
- ErsatzTV doesn't have native tag-based slot filtering
- Must use smart collections as workaround

---

### **New Architecture (Pseudovision)**

```
┌──────────────────┐
│ tunarr-scheduler │ - Pulls media from Jellyfin (shared)
│                  │ - Categorizes with Tunabrain (LLM)
└────────┬─────────┘ - Manages schedules
         │
         │ (1) Direct API integration
         ▼
┌──────────────────┐
│   Pseudovision   │ - Native tag storage (metadata_tags)
│                  │ - Tag-based slot filtering
│                  │ - Scheduling engine (built-in)
│                  │ - Collections (smart/manual/playlist)
└──────────────────┘ - Playout + HLS streaming

         ▲
         │ (both read from same Jellyfin)
         │
┌──────────────────┐
│    Jellyfin      │ - Media server
└──────────────────┘ - Metadata provider
```

**Benefits:**
- Direct 1-hop integration
- Pseudovision owns tags and schedules
- Native tag-based scheduling (required_tags/excluded_tags)
- No fragile Jellyfin tag sync
- Single source of truth

---

## Pseudovision API Endpoints

### **Tags**
- `POST /api/media-items/:id/tags` - Add tags to media item
  ```json
  {"tags": ["comedy", "short", "daytime"]}
  ```
- `GET /api/media-items/:id/tags` - Get tags for item
- `DELETE /api/media-items/:id/tags/:tag` - Remove tag
- `GET /api/tags` - List all tags with counts

### **Schedules**
- `GET /api/schedules` - List schedules
- `POST /api/schedules` - Create schedule
  ```json
  {"name": "Comedy Channel Schedule"}
  ```
- `GET /api/schedules/:id/slots` - List slots
- `POST /api/schedules/:id/slots` - Add slot
  ```json
  {
    "slot_index": 0,
    "anchor": "fixed",
    "start_time": "18:00:00",
    "fill_mode": "flood",
    "collection_id": 1,
    "required_tags": ["comedy", "short"],
    "excluded_tags": ["explicit", "nsfw"],
    "playback_order": "shuffle"
  }
  ```

### **Channels & Playouts**
- `GET /api/channels` - List channels
- `POST /api/channels` - Create channel
- `GET /api/channels/:id/playout` - Get playout state
- `POST /api/channels/:id/playout?from=now&horizon=14` - Rebuild schedule
  ```json
  {"message": "Rebuild complete", "events-generated": 191}
  ```

### **Media Discovery**
- `GET /api/media/sources` - List Jellyfin/local sources
- `GET /api/media/sources/:id/libraries` - List libraries
- `GET /api/collections` - List collections (smart/manual/playlist)

---

## Code Changes Required

### **1. REMOVE (ErsatzTV/Tunarr-specific)**

**Files to delete:**
- `src/tunarr/scheduler/media/jellyfin_sync.clj` (153 lines)
  - Syncs tags to Jellyfin via API
  - No longer needed - Pseudovision has its own tag storage

- `src/tunarr/scheduler/backends/` directory
  - `ersatztv.clj` - ErsatzTV API client
  - `tunarr.clj` - Tunarr API client
  - Replace with single `pseudovision.clj`

**Routes to remove:**
- `POST /api/media/:library/sync-jellyfin-tags`
- Any ErsatzTV/Tunarr schedule upload endpoints

**Config to remove:**
- `:backends {:ersatztv ... :tunarr ...}` section
- ErsatzTV-specific settings

---

### **2. MODIFY (Adapt to Pseudovision)**

**`src/tunarr/scheduler/media/catalog.clj`**
- **Current**: In-memory catalog with EDN persistence
- **Change**: Add Pseudovision sync methods
  - `(sync-tags-to-pseudovision! catalog pv-client library)`
  - Map catalog media IDs to Pseudovision media_item IDs

**`src/tunarr/scheduler/media/sync.clj`**
- **Current**: Syncs from Jellyfin to in-memory catalog
- **Change**: After sync, optionally push to Pseudovision
  - Query Jellyfin items
  - Match to Pseudovision media_items by remote_key
  - Push tags via Pseudovision API

**`src/tunarr/scheduler/curation/core.clj`**
- **Current**: Tags items in local catalog
- **Keep**: LLM categorization logic
- **Add**: Sync to Pseudovision after tagging
  - `(after-tag-fn item tags)` → calls Pseudovision API

**`src/tunarr/scheduler/http/routes.clj`**
- **Remove**: Jellyfin sync route
- **Add**: Pseudovision sync route
  - `POST /api/media/:library/sync-pseudovision-tags`

---

### **3. ADD (New Pseudovision Integration)**

**`src/tunarr/scheduler/backends/pseudovision.clj` (NEW)**

```clojure
(ns tunarr.scheduler.backends.pseudovision
  "Pseudovision IPTV platform client."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defn add-tags!
  "Add tags to a media item in Pseudovision."
  [config media-item-id tags]
  (http/post (str (:base-url config) "/api/media-items/" media-item-id "/tags")
             {:form-params {:tags tags}
              :content-type :json
              :as :json}))

(defn create-schedule!
  "Create a new schedule in Pseudovision."
  [config schedule-data]
  (http/post (str (:base-url config) "/api/schedules")
             {:form-params schedule-data
              :content-type :json
              :as :json}))

(defn add-slot!
  "Add a slot to a Pseudovision schedule."
  [config schedule-id slot-data]
  (http/post (str (:base-url config) "/api/schedules/" schedule-id "/slots")
             {:form-params slot-data
              :content-type :json
              :as :json}))

(defn rebuild-playout!
  "Trigger playout rebuild for a channel."
  [config channel-id & [{:keys [from horizon] :or {from "now" horizon 14}}]]
  (http/post (str (:base-url config) "/api/channels/" channel-id "/playout")
             {:query-params {"from" from "horizon" (str horizon)}
              :as :json}))

(defn list-channels [config]
  (http/get (str (:base-url config) "/api/channels")
            {:as :json}))

(defn get-media-items
  "Get media items from Pseudovision for tag matching."
  [config & [{:keys [limit offset]}]]
  (http/get (str (:base-url config) "/api/media-items")
            {:query-params (cond-> {}
                             limit (assoc "limit" limit)
                             offset (assoc "offset" offset))
             :as :json}))
```

**`src/tunarr/scheduler/scheduling/pseudovision.clj` (NEW)**

```clojure
(ns tunarr.scheduler.scheduling.pseudovision
  "Schedule generation for Pseudovision channels."
  (:require [tunarr.scheduler.backends.pseudovision :as pv]
            [taoensso.timbre :as log]))

(defn generate-daily-schedule!
  "Generate a full day's schedule for a Pseudovision channel.
   
   Takes catalog data and generates schedule slots with tag filters."
  [pv-config catalog channel-spec]
  ;; channel-spec: {:name 'Comedy Channel'
  ;;                :blocks [{:time '18:00' :duration-hours 2 
  ;;                          :required-tags [:comedy :short]}]}
  
  (let [;; Create or get schedule
        schedule (pv/create-schedule! pv-config 
                   {:name (:name channel-spec)})
        schedule-id (:id schedule)]
    
    ;; Create slots for each block
    (doseq [[idx block] (map-indexed vector (:blocks channel-spec))]
      (pv/add-slot! pv-config schedule-id
        {:slot-index idx
         :anchor (if (:time block) "fixed" "sequential")
         :start-time (:time block)
         :fill-mode (or (:fill-mode block) "flood")
         :collection-id (:collection-id block)
         :required-tags (map name (:required-tags block))
         :excluded-tags (map name (:excluded-tags block))
         :playback-order (or (:playback-order block) "shuffle")}))
    
    (log/info "Created Pseudovision schedule" 
              {:schedule-id schedule-id
               :slots (count (:blocks channel-spec))})
    
    schedule))
```

**`src/tunarr/scheduler/media/pseudovision_sync.clj` (NEW)**

```clojure
(ns tunarr.scheduler.media.pseudovision-sync
  "Sync catalog tags to Pseudovision."
  (:require [tunarr.scheduler.backends.pseudovision :as pv]
            [tunarr.scheduler.media.catalog :as catalog]
            [taoensso.timbre :as log]))

(defn sync-library-tags!
  "Sync all tags from catalog to Pseudovision for a library.
   
   Maps catalog media items to Pseudovision media_items via Jellyfin ID,
   then pushes tags via Pseudovision tag API."
  [catalog pv-config library opts]
  (let [report-progress (get opts :report-progress (constantly nil))
        items (catalog/get-media catalog library)
        total (count items)]
    
    (log/info "Starting Pseudovision tag sync" 
              {:library library :items total})
    
    (doseq [[idx item] (map-indexed vector items)]
      (try
        ;; Get Pseudovision media_item_id by Jellyfin remote_key
        (when-let [jf-id (:jellyfin-id item)]
          (when-let [pv-item (pv/find-by-jellyfin-id pv-config jf-id)]
            (let [tags (catalog/get-tags catalog item)]
              (when (seq tags)
                (pv/add-tags! pv-config 
                             (:id pv-item)
                             (map name tags))
                (log/debug "Synced tags to Pseudovision" 
                          {:pv-item-id (:id pv-item)
                           :tags tags})))))
        
        (report-progress {:phase "syncing"
                         :current (inc idx)
                         :total total})
        
        (catch Exception e
          (log/error e "Failed to sync item" {:item item}))))
    
    (log/info "Pseudovision tag sync complete" 
              {:library library :items total})))
```

---

## Configuration Changes

### **Before (config.edn):**
```clojure
{:backends {:ersatztv {:enabled true
                       :base-url "http://localhost:8409"
                       :auto-sync false
                       :default-streaming-mode "HLS Segmenter"}
            :tunarr {:enabled false
                     :base-url "http://localhost:8000"
                     :auto-sync false}}
 :jellyfin {:base-url "http://localhost:8096"
            :api-key "jellyfin-key"
            :library-ids ["movies-lib-id" "tv-lib-id"]}}
```

### **After:**
```clojure
{:pseudovision {:base-url #or [#env PSEUDOVISION_URL "http://localhost:8080"]
                :auto-sync #or [#env PSEUDOVISION_AUTO_SYNC true]
                ;; Map tunarr-scheduler library names to Pseudovision collection IDs
                :library-mappings {:movies 1
                                   :tv-shows 2}}
 :jellyfin {:base-url #or [#env JELLYFIN_URL "http://localhost:8096"]
            :api-key #or [#env JELLYFIN_API_KEY "jellyfin-key"]
            :library-ids []}  ;; Jellyfin is now shared between both systems
 ;; Keep existing LLM config
 :tunabrain {:endpoint "http://localhost:8080"}
 :llm {:provider :mock}}
```

---

## Data Flow Changes

### **Tag Management Flow**

**Before:**
1. tunarr-scheduler categorizes media → in-memory catalog
2. `POST /api/media/:library/sync-jellyfin-tags` → push to Jellyfin
3. ErsatzTV pulls tags from Jellyfin
4. Create ErsatzTV smart collections based on tags

**After:**
1. tunarr-scheduler categorizes media → in-memory catalog
2. `POST /api/media/:library/sync-pseudovision-tags` → direct to Pseudovision
3. Tags stored in Pseudovision's `metadata_tags` table
4. Use tags directly in schedule slots (`required_tags`/`excluded_tags`)

### **Schedule Management Flow**

**Before:**
1. tunarr-scheduler generates ErsatzTV Sequential Schedule YAML
2. Upload to ErsatzTV via API
3. ErsatzTV builds playout from YAML

**After:**
1. tunarr-scheduler generates schedule spec (in-memory)
2. Create schedule: `POST /api/schedules {"name": "Comedy Channel"}`
3. Add slots with tag filters:
   ```
   POST /api/schedules/:id/slots
   {
     "required_tags": ["comedy", "short"],
     "fill_mode": "flood",
     "playback_order": "shuffle"
   }
   ```
4. Attach to channel and rebuild:
   ```
   PATCH /api/channels/:id {"schedule_id": X}
   POST /api/channels/:id/playout?from=now
   ```

---

## Implementation Phases

### **Phase 1: Foundation (1-2 hours)**
- [x] ✅ **DONE IN PSEUDOVISION**: Tag CRUD API endpoints
- [x] ✅ **DONE IN PSEUDOVISION**: Tag-based slot filtering in scheduler
- [ ] Create `backends/pseudovision.clj` HTTP client
- [ ] Update config schema (remove :backends, add :pseudovision)
- [ ] Add Pseudovision health check to startup

### **Phase 2: Tag Sync (2-3 hours)**
- [ ] Create `media/pseudovision_sync.clj`
- [ ] Implement `sync-library-tags!` (catalog → Pseudovision)
- [ ] Map Jellyfin IDs to Pseudovision media_item IDs
- [ ] Add route: `POST /api/media/:library/sync-pseudovision-tags`
- [ ] Delete `media/jellyfin_sync.clj`
- [ ] Delete route: `POST /api/media/:library/sync-jellyfin-tags`

### **Phase 3: Schedule Generation (3-4 hours)**
- [ ] Create `scheduling/pseudovision.clj`
- [ ] Implement schedule spec → Pseudovision API translation
- [ ] Support all Pseudovision slot features:
  - Anchors: fixed, sequential
  - Fill modes: once, count, block, flood
  - Playback orders: chronological, random, shuffle, semi-sequential
- [ ] Add route: `POST /api/channels/:channel-id/schedule`
- [ ] Delete ErsatzTV YAML generation code

### **Phase 4: Integration Testing (1-2 hours)**
- [ ] End-to-end test: Jellyfin → categorize → sync tags → create schedule → rebuild
- [ ] Verify streaming works via Pseudovision
- [ ] Test tag filtering (required/excluded)
- [ ] Test playback orders

### **Phase 5: Cleanup (1 hour)**
- [ ] Remove all ErsatzTV/Tunarr references
- [ ] Update README.md
- [ ] Update JELLYFIN_SYNC.md → PSEUDOVISION_SYNC.md
- [ ] Delete unused dependencies (ersatztv client libs)
- [ ] Consider renaming: tunarr-scheduler → tunabrain-scheduler? cataloger?

**Total estimated time:** 8-12 hours

---

## Key Design Decisions

### **1. Media ID Mapping**

**Challenge:** tunarr-scheduler's catalog uses its own IDs, Pseudovision uses different IDs.

**Solution:** Map via Jellyfin `remote_key`:
```sql
-- In Pseudovision
SELECT id FROM media_items WHERE remote_key = :jellyfin-id
```

Store mapping in catalog or query on-demand during sync.

### **2. Catalog Persistence**

**Option A (Recommended):** Keep tunarr-scheduler's in-memory catalog
- ✅ Fast categorization without DB queries
- ✅ Decoupled from Pseudovision
- ✅ Can batch-sync changes
- Sync to Pseudovision after curation

**Option B:** Use Pseudovision as source of truth
- Query Pseudovision for media items
- Store categorizations directly in Pseudovision
- More complex, tighter coupling

**Decision:** Keep in-memory catalog, sync periodically

### **3. Tag Format**

**tunarr-scheduler uses keywords:** `:comedy`, `:sci-fi`, `:action-adventure`

**Pseudovision uses strings:** `"comedy"`, `"sci-fi"`, `"action-adventure"`

**Conversion:**
```clojure
(defn tag->string [tag]
  (name tag))  ; :comedy → "comedy"

(defn string->tag [s]
  (keyword s)) ; "comedy" → :comedy
```

### **4. Schedule Ownership**

**Who owns the schedule?**
- **tunarr-scheduler**: Decides WHAT to schedule (tags, timing, rules)
- **Pseudovision**: Executes schedules (picks items, generates events, streams)

**Flow:**
1. tunarr-scheduler: "From 6pm-8pm, play comedy shorts"
2. → Creates Pseudovision slot with `required_tags: ["comedy", "short"]`
3. Pseudovision scheduling engine: Picks specific items matching tags
4. Pseudovision: Generates playout events and streams

---

## API Interaction Examples

### **Example 1: Tag Sync**

```clojure
;; After LLM categorization in tunarr-scheduler:
(def catalog-item 
  {:id "local-123"
   :jellyfin-id "jf-abc123"
   :title "Seinfeld S01E01"
   :tags #{:comedy :sitcom :90s :daytime}})

;; Find matching Pseudovision media item
(def pv-item (pv/find-by-jellyfin-id pv-config "jf-abc123"))
;; → {:id 28954, :remote-key "jf-abc123", ...}

;; Push tags to Pseudovision
(pv/add-tags! pv-config 28954 ["comedy" "sitcom" "90s" "daytime"])
;; → POST /api/media-items/28954/tags
```

### **Example 2: Schedule Creation**

```clojure
;; tunarr-scheduler generates schedule spec
(def comedy-channel-spec
  {:name "Comedy Channel"
   :blocks [{:time "18:00:00"
             :fill-mode "block"
             :block-duration "PT2H"  ; 2 hours
             :required-tags #{:comedy :short}
             :excluded-tags #{:explicit}
             :playback-order "shuffle"}
            {:time "20:00:00"
             :fill-mode "flood"
             :required-tags #{:sitcom}
             :playback-order "semi-sequential"}]})

;; Create in Pseudovision
(def schedule (pv/create-schedule! pv-config {:name "Comedy Channel"}))

;; Add slots
(pv/add-slot! pv-config (:id schedule)
  {:slot-index 0
   :anchor "fixed"
   :start-time "18:00:00"
   :fill-mode "block"
   :block-duration "PT2H"
   :required-tags ["comedy" "short"]
   :excluded-tags ["explicit"]
   :playback-order "shuffle"})

;; Attach to channel and rebuild
(pv/rebuild-playout! pv-config 27 {:horizon 14})
;; → Pseudovision engine picks items matching tags, generates events
```

---

## Migration Checklist

### **Code Changes**
- [ ] Create `backends/pseudovision.clj` HTTP client
- [ ] Create `media/pseudovision_sync.clj` tag sync logic
- [ ] Create `scheduling/pseudovision.clj` schedule generator
- [ ] Update `http/routes.clj` - remove ErsatzTV routes, add Pseudovision routes
- [ ] Update `system.clj` - wire Pseudovision client, remove ErsatzTV backend
- [ ] Delete `backends/ersatztv.clj` and `backends/tunarr.clj`
- [ ] Delete `media/jellyfin_sync.clj`
- [ ] Update `curation/core.clj` to call Pseudovision after tagging

### **Configuration**
- [ ] Update `resources/config.edn` with Pseudovision settings
- [ ] Add `PSEUDOVISION_URL` env var support
- [ ] Remove ErsatzTV/Tunarr config sections

### **Documentation**
- [ ] Update README.md - replace ErsatzTV with Pseudovision
- [ ] Rename JELLYFIN_SYNC.md → PSEUDOVISION_SYNC.md
- [ ] Update SCHEDULING.md to reflect Pseudovision architecture
- [ ] Add integration examples

### **Testing**
- [ ] Test tag sync end-to-end
- [ ] Test schedule creation
- [ ] Verify playout rebuild works
- [ ] Test all playback orders and fill modes

### **Deployment**
- [ ] Update Kubernetes manifests (if any)
- [ ] Update Docker build
- [ ] Update Nix flake outputs
- [ ] Environment variable documentation

---

## Benefits of This Migration

### **Simplified Architecture**
- **Before**: 4 components (tunarr-scheduler, Jellyfin, ErsatzTV, network)
- **After**: 3 components (tunarr-scheduler, Jellyfin, Pseudovision)

### **Direct Integration**
- **Before**: Tags go through Jellyfin as intermediary
- **After**: Direct API calls to Pseudovision

### **Native Tag Filtering**
- **Before**: Use smart collections as workaround
- **After**: Native `required_tags`/`excluded_tags` in slots

### **Better Control**
- **Before**: ErsatzTV owns playout, limited schedule control
- **After**: Full control over Pseudovision scheduling engine

### **Performance**
- **Before**: 3-hop sync (scheduler → Jellyfin → ErsatzTV)
- **After**: 1-hop (scheduler → Pseudovision)

---

## Open Questions

1. **Library Discovery**: Should tunarr-scheduler query Pseudovision's media_sources, or configure library mappings statically?

2. **Catalog Sync Direction**: Should tags only flow scheduler → Pseudovision, or should we support bidirectional sync?

3. **Schedule Updates**: When regenerating schedules, should we:
   - Delete old schedule and create new one?
   - Update existing slots in-place?
   - Version schedules for rollback?

4. **Multi-Channel Support**: How should tunarr-scheduler manage multiple channels?
   - One schedule per channel?
   - Shared schedule with channel-specific overrides?

5. **Naming**: Should we rename `tunarr-scheduler` to something Pseudovision-specific?
   - Options: `tunabrain-scheduler`, `pseudovision-curator`, `tv-cataloger`

---

## Next Steps

**Immediate:**
1. Review this design with team
2. Decide on open questions
3. Create Phase 1 implementation tasks
4. Set up dev environment with both systems running

**Follow-up:**
- Implement Pseudovision client (Phase 1)
- Test tag sync (Phase 2)
- Build schedule generator (Phase 3)
- End-to-end integration test (Phase 4)
- Production deployment

---

## Appendix: Pseudovision Features Used

### **Scheduling Engine**
- ✅ Playback orders: chronological, random, shuffle, semi-sequential, season-episode
- ✅ Fill modes: once, count, block, flood
- ✅ Tag-based filtering: required_tags (AND), excluded_tags (NOT)
- ✅ Smart collections: SQL-based queries for "all movies", "all comedies", etc.
- ✅ Rebuild API: from=now (config change), from=horizon (daily extend)

### **Collections**
- ✅ Manual collections: Explicit item lists
- ✅ Smart collections: SQL queries with filters
- ✅ Playlist collections: Imported playlists

### **Streaming**
- ✅ HLS segmenter mode (FFmpeg transcode)
- ✅ EPG (XMLTV) generation
- ✅ M3U8 playlists
- ⚠️ HLS direct mode (in progress - copy mode for H.264/AAC)

**What tunarr-scheduler won't need:**
- No direct streaming (Pseudovision handles that)
- No playout event management (Pseudovision scheduling engine)
- No FFmpeg management (Pseudovision owns transcoding)

**What tunarr-scheduler WILL do:**
- LLM-powered categorization/tagging
- High-level schedule design ("primetime comedy block")
- Tag management and curation
- Schedule generation and updates
