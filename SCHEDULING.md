# TV Scheduling System Design

This document outlines the architecture for an LLM-powered TV scheduling system that manages Pseudovision channels with minimal human intervention.

## Philosophy

- **Agent has full control** - no manual/auto block distinction
- **Monthly cadence** - stable schedules, infrequent reshuffles
- **Constraint-driven** - users provide natural language instructions
- **Pleasant surprises** - occasional reshuffles (1-2x/year), no approval needed
- **Disciplined** - consistent structure, not chaotic changes
- **Always something playing** - Pseudovision fallback ensures no dead air

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      USER INSTRUCTIONS                          │
│  (Web UI → stored in DB per channel)                            │
│                                                                 │
│  Natural language, e.g.:                                        │
│    - Seinfeld should air at least 3x/week in primetime          │
│    - Weekend mornings: light, family-friendly content           │
│    - No adult content before 10pm                               │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 0: SEASONAL PLANNER                         │
│               (quarterly, or on-demand via 'big red button')    │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Theme weeks (Spy Week, Action Week)                        │
│    • Holiday scheduling (Christmas movies Dec 15-25)            │
│    • Marathon weekends                                          │
│    • Major lineup reshuffles (1-2x/year)                        │
│                                                                 │
│  Constraints:                                                   │
│    • Max 1-2 theme weeks per month                              │
│    • Reshuffles only on calendar triggers or manual request     │
│    • Must respect content inventory limits                      │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 1: MONTHLY PLANNER                          │
│               (runs ~1st of each month)                         │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Weekly block templates (recurring structure)               │
│    • Show assignments to recurring slots                        │
│    • Special event blocks from seasonal plan                    │
│    • Gap-filler strategy per time-of-day                        │
│                                                                 │
│  Example output:                                                │
│    Mon-Fri 18:00-19:00: Seinfeld (sequential)                   │
│    Mon-Fri 19:00-20:00: Friends (sequential)                    │
│    Mon-Fri 20:00-22:00: Rotating comedies (shuffle)             │
│    Sat 08:00-12:00: Classic sitcom marathon                     │
│    Sat 20:00-00:00: Movie night                                 │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│               LEVEL 2: WEEKLY EXECUTOR                          │
│               (runs weekly)                                     │
├─────────────────────────────────────────────────────────────────┤
│  Decides:                                                       │
│    • Concrete media for variable/shuffle slots                  │
│    • Episode numbers for sequential shows                       │
│    • Adjustments for that specific week                         │
│                                                                 │
│  Actions:                                                       │
│    • Generate Pseudovision Schedule + Slots via API             │
│    • Assign schedule to channel                                 │
│    • Trigger playout rebuild                                    │
└─────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│                        PSEUDOVISION                             │
├─────────────────────────────────────────────────────────────────┤
│  Tag-based content selection (required_tags/excluded_tags)      │
│  Fallback collections ensure no dead air                        │
│  Native scheduling engine builds playout events                 │
│  HLS streaming to viewers                                       │
└─────────────────────────────────────────────────────────────────┘
```

## User Instructions

Instructions are stored per-channel in the database as text blobs, editable via web UI. They use natural language so non-technical users can contribute.

### Example Instructions

```
Channel: Sitcom Spectrum

Recurring Shows:
- Seinfeld should air at least 3 times per week during primetime
- Friends should have a regular evening slot
- The Office is good for late-night

Time-of-Day Rules:
- Weekend mornings (8am-12pm): light, family-friendly sitcoms
- Primetime (8pm-11pm): popular shows, variety
- Late night (11pm+): edgier content is okay

Preferences:
- Multi-episode blocks are preferred (2-3 episodes back-to-back)
- Don't put Friends and Seinfeld back-to-back
- Variety across the week - don't repeat same show same timeslot
- 30-minute time slots as the base unit
```

### Instruction Categories

1. **Recurring show requirements** - minimum frequency, preferred timeslots
2. **Time-of-day rules** - content appropriateness per time slot
3. **Structural preferences** - block sizes, variety, adjacency rules
4. **Special instructions** - holiday content, seasonal themes

## Content Categories

Media is pre-categorized (via the recategorize system) into:

| Category | Values | Purpose |
|----------|--------|---------|
| `time-slot` | morning, daytime, primetime, late-night | Time-appropriateness filtering |
| `audience` | kids, family, teen, adult | Daytime-safe filtering |
| `season` | any, spring, summer, fall, winter, christmas, halloween, valentines, independence | Seasonal scheduling |
| `freshness` | classic, retro, modern, contemporary | Era-based channel targeting |
| `channel` | enigma, toontown, galaxy, nippon, spectrum, britannia, etc. | Which channel content fits |

### Category Assignment Examples

**Spooky content** (flexible):
- season: `[:halloween, :any]` - Can air during Halloween OR year-round

**Christmas movie** (specific):
- season: `[:christmas]` - Only December

**Family sitcom**:
- time-slot: `[:daytime, :primetime]`
- audience: `[:family]`
- channel: `[:spectrum]`

## Episode Tracking

The system tracks progress through sequential shows:

```
Series: Seinfeld
  Current: Season 4, Episode 12
  Total Episodes: 180
  Last Aired: 2026-02-01
  Status: active

Series: Cheers  
  Current: Season 11, Episode 28 (final)
  Total Episodes: 275
  Last Aired: 2026-01-15
  Status: completed → rotated out
```

### Series Lifecycle

1. **Active** - Currently in rotation, episodes advance sequentially
2. **Completed** - All episodes played, automatically rotated out for a while
3. **Resting** - Temporarily removed (will return based on agent decisions)
4. **Retired** - Manually removed from rotation

When a series completes:
- Default: Rotate out to avoid staleness
- Unless: User instructions explicitly say keep series in rotation
- The agent will naturally select different content when generating schedules

## Reshuffle Triggers

Major schedule reshuffles happen on:

### 1. Calendar Events (Automatic)
- Quarterly (seasonal changes)
- New Year
- Major holidays (if configured)

### 2. Manual Trigger (User)
- Big red button in web UI
- Triggers Level 0 Seasonal Planner
- No approval needed - agent executes immediately

### 3. NOT Triggered By
- New content being added (too frequent)
- Series completing (handled by rotation)
- Weekly schedule runs (those use existing templates)

## Block Structure

### Time Slot Granularity

- Base unit: **30 minutes**
- Multi-episode blocks preferred (60-90 min for sitcoms)
- Movies get flexible duration (round to nearest 30 min)

### Block Types

| Type | Duration | Content Selection | Example |
|------|----------|-------------------|---------|
| Sequential Show | 30-90 min | Next episodes in order | Seinfeld S4E12-E13 |
| Shuffle Block | 60-180 min | Random from criteria | Classic sitcoms (shuffle) |
| Marathon | 4-12 hours | Theme-based, shuffled | Spy Movie Marathon |
| Movie Slot | 90-180 min | Single film | Random primetime movie |
| Filler | Variable | Random appropriate content | Channel-appropriate fallback |

## Pseudovision Integration

### Schedule Format

The agent generates schedules via Pseudovision HTTP API:

**Step 1: Create Schedule**
```bash
POST /api/schedules
{
  name: 'Sitcom Spectrum Week 1'
}
```

**Step 2: Add Slots**
```bash
POST /api/schedules/:id/slots
{
  slot_index: 0,
  anchor: 'fixed',
  start_time: '18:00:00',
  fill_mode: 'flood',
  required_tags: ['comedy', 'sitcom'],
  excluded_tags: ['explicit'],
  playback_order: 'shuffle'
}
```

**Step 3: Attach to Channel & Rebuild**
```bash
PATCH /api/channels/:id
{schedule_id: 123}

POST /api/channels/:id/playout?from=now&horizon=14
```

### Slot Configuration Options

| Option | Values | Description |
|--------|--------|-------------|
| `anchor` | `fixed`, `sequential` | Fixed time or follows previous slot |
| `start_time` | HH:MM:SS | For fixed anchors |
| `fill_mode` | `once`, `count`, `block`, `flood` | How to fill the slot |
| `block_duration` | ISO-8601 | Duration for block mode (PT2H = 2 hours) |
| `item_count` | integer | Count for count mode |
| `collection_id` | integer | Use specific collection |
| `required_tags` | string[] | Items must have ALL these tags |
| `excluded_tags` | string[] | Items must NOT have ANY of these tags |
| `playback_order` | `chronological`, `random`, `shuffle`, `semi-sequential`, `season-episode` | How to select items |

### Fallback Configuration

Each channel has fallback collections configured in Pseudovision:

**Daytime Fallback:**
```
Collection: random-spectrum-daytime
Query: channel:spectrum AND time_slot:(daytime OR primetime) AND audience:(family OR kids)
```

**Late-Night Fallback:**
```
Collection: random-spectrum-latenight  
Query: channel:spectrum AND time_slot:late-night
```

These ensure content always plays, even if:
- Scheduler fails to run
- Gaps exist between scheduled blocks
- Content runs short
- Agent makes a mistake

## Agent Tools

The scheduling agent needs these capabilities:

### Content Inventory
```
get_content_inventory(channel, filters) → {tag: count, ...}
get_shows_for_channel(channel) → [{show_id, title, episode_count, tags}, ...]
get_media_by_criteria(channel, tags, categories, limit) → [media]
get_movies_by_duration(channel, min_duration, max_duration) → [movies]
```

### Episode Tracking
```
get_series_progress(show_id) → {season, episode, total, status, last_aired}
advance_series(show_id, count) → updated_progress
get_active_series(channel) → [show_ids]
rotate_out_series(show_id) → success
rotate_in_series(show_id) → success
```

### Pseudovision
```
create_schedule(channel, name) → schedule_id
add_slot(schedule_id, slot_config) → success
attach_schedule_to_channel(channel_id, schedule_id) → success
rebuild_playout(channel_id, horizon_days) → {events_generated, ...}
get_channel_playout(channel_id) → {current_events, ...}
```

### Scheduling Helpers
```
validate_against_instructions(schedule, instructions) → {valid, issues}
get_upcoming_events(date_range) → [holidays, special_dates]
check_content_exhaustion(theme, duration) → {feasible, warnings}
```

### Manual Triggers
```
trigger_reshuffle(channel, reason) → job_id
get_schedule_preview(channel, date_range) → rendered_schedule
```

## Implementation Phases

### Phase 1: Foundation
- [x] ✅ Pseudovision HTTP client (backends/pseudovision/client.clj)
- [x] ✅ Channel sync to Pseudovision (channels/sync.clj)
- [x] ✅ Tag sync to Pseudovision (media/pseudovision_sync.clj)
- [x] ✅ Schedule generation (scheduling/pseudovision.clj)
- [ ] Episode tracking database schema and CRUD operations
- [ ] User instruction storage schema (TEXT field per channel)
- [ ] Basic web UI for editing instructions

### Phase 2: Weekly Executor (Level 2)
- [ ] Template → API generation logic
- [ ] Episode advancement logic
- [ ] Pseudovision schedule upload integration
- [ ] Validation against user instructions
- [ ] HTTP endpoint: `POST /api/channels/:channel/generate-week`

### Phase 3: Monthly Planner (Level 1)
- [ ] LLM agent that generates weekly templates from instructions
- [ ] Instruction parsing and constraint checking
- [ ] Special event handling (holidays, theme weeks)
- [ ] Content inventory integration
- [ ] HTTP endpoint: `POST /api/channels/:channel/generate-month`

### Phase 4: Seasonal Planner (Level 0)
- [ ] Theme week planning logic
- [ ] Marathon scheduling
- [ ] Holiday content management
- [ ] Lineup reshuffle logic (1-2x/year)
- [ ] HTTP endpoint: `POST /api/channels/:channel/reshuffle` (big red button)

### Phase 5: Polish
- [ ] Full web UI for instructions and schedule preview
- [ ] Schedule review/preview before execution
- [ ] Better logging and monitoring
- [ ] Error recovery and retry logic

### Phase 6: Multi-Channel Coordination (Stretch Goal)
- [ ] Avoid scheduling same content on multiple channels simultaneously
- [ ] Coordinate theme weeks across channels
- [ ] Share marathon content appropriately

## Data Models

### User Instructions (Database)

```sql
CREATE TABLE channel_instructions (
  channel_id VARCHAR(128) PRIMARY KEY REFERENCES channel(name) ON DELETE CASCADE,
  instructions TEXT NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT NOW()
);
```

### Episode Tracking

```sql
CREATE TABLE series_progress (
  show_id VARCHAR(128) PRIMARY KEY,
  show_name VARCHAR(512) NOT NULL,
  channel VARCHAR(128) NOT NULL REFERENCES channel(name),
  current_season INT NOT NULL DEFAULT 1,
  current_episode INT NOT NULL DEFAULT 1,
  total_episodes INT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'completed', 'resting', 'retired')),
  last_aired DATE,
  created_at timestamptz NOT NULL DEFAULT NOW(),
  updated_at timestamptz NOT NULL DEFAULT NOW()
);
```

### Schedule State

```sql
CREATE TABLE schedule_state (
  id BIGSERIAL PRIMARY KEY,
  channel VARCHAR(128) NOT NULL REFERENCES channel(name),
  generated_at timestamptz NOT NULL DEFAULT NOW(),
  schedule_type VARCHAR(20) NOT NULL CHECK (schedule_type IN ('weekly', 'monthly', 'seasonal')),
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  pseudovision_schedule_id INT,
  applied BOOLEAN NOT NULL DEFAULT false,
  applied_at timestamptz,
  notes TEXT
);
```

## Example User Instructions

### Sitcom Spectrum

```
Recurring Shows:
- Seinfeld should air at least 3 times per week during primetime
- Friends should have a regular evening slot
- The Office is good for late-night

Time-of-Day Rules:
- Weekend mornings (8am-12pm): light, family-friendly sitcoms
- Primetime (8pm-11pm): popular shows, variety
- Late night (11pm+): edgier content is okay

Preferences:
- Multi-episode blocks are preferred (2-3 episodes back-to-back)
- Don't put Friends and Seinfeld back-to-back
- Variety across the week - don't repeat same show same timeslot
- 30-minute time slots as the base unit
```

### Toon Town

```
Content Rules:
- Saturday mornings (8am-12pm): classic cartoons, Disney movies
- Weekday afternoons (3pm-6pm): kid-friendly anime and animated series
- Evenings (7pm-9pm): family animation (Pixar, DreamWorks)
- Late night (10pm+): adult animation (Futurama, Rick & Morty)

Special Events:
- Christmas season: Disney holiday specials
- Halloween: spooky but kid-appropriate (Scooby-Doo, etc.)

Always:
- No content rated above TV-PG before 9pm
- Multi-episode blocks for series
```

### Golden Reels

```
Content Focus:
- Only classic (pre-1970) and retro (1970s-1990s) content
- Prefer black-and-white films during daytime
- Color films in evening slots

Structure:
- Weekday mornings: classic sitcoms (I Love Lucy, etc.)
- Weekday afternoons: westerns
- Evenings: classic films (noirs, screwball comedies)
- Weekend afternoons: Charlie Chaplin/Buster Keaton marathons

When Seinfeld completes its run:
- Keep it in rotation, restart from S1E1
```

## Pseudovision Schedule Example

Full example of what the agent would generate via API:

### Step 1: Create Schedule
```bash
POST /api/schedules
```
Response: `{id: 42, name: 'Sitcom Spectrum Week 1'}`

### Step 2: Add Slots

**Monday through Friday pattern:**
```bash
POST /api/schedules/42/slots
{
  slot_index: 0,
  anchor: 'fixed',
  start_time: '18:00:00',
  fill_mode: 'flood',
  required_tags: ['comedy', 'sitcom'],
  playback_order: 'semi-sequential',
  marathon_batch_size: 2
}
```

**Evening variety (shuffle):**
```bash
POST /api/schedules/42/slots
{
  slot_index: 1,
  anchor: 'sequential',
  fill_mode: 'block',
  block_duration: 'PT3H',
  required_tags: ['comedy', 'time_slot:primetime'],
  excluded_tags: ['sitcom'],
  playback_order: 'shuffle'
}
```

**Saturday morning marathon:**
```bash
POST /api/schedules/42/slots
{
  slot_index: 2,
  anchor: 'fixed',
  start_time: '08:00:00',
  fill_mode: 'block',
  block_duration: 'PT4H',
  required_tags: ['audience:kids', 'channel:toontown'],
  playback_order: 'shuffle'
}
```

### Step 3: Assign & Rebuild

```bash
# Assign schedule to channel
curl -X PATCH http://pseudovision:8080/api/channels/6 -d '{
  schedule_id: 42
}'

# Rebuild playout for 14 days
curl -X POST 'http://pseudovision:8080/api/channels/6/playout?from=now&horizon=14'
```

## Reshuffle Behavior

### Calendar-Based Reshuffles

The Seasonal Planner (Level 0) automatically runs on:

- **January 1** - New year, fresh lineup
- **April 1** - Spring refresh
- **July 1** - Summer programming
- **October 1** - Fall lineup

During these runs, the agent may:
- Shuffle primetime show order
- Introduce new recurring shows
- Rotate out completed series
- Adjust timeslots based on content inventory changes

### Manual Reshuffle (Big Red Button)

Web UI provides a reshuffle trigger per channel:

```
POST /api/channels/:channel/reshuffle
{
  reason: 'User requested fresh lineup',
  preserve_favorites: false  // optional: keep certain shows in place
}
```

The agent will:
1. Re-run Seasonal Planner for next 3 months
2. Generate new monthly plan
3. Create new weekly schedules
4. Push to Pseudovision
5. Trigger playout rebuild

## Future Considerations

### Multi-Channel Coordination (Stretch Goal)

The agent could be aware of other channels when scheduling:

- Don't schedule Star Trek on Galaxy AND Spotlight simultaneously
- Coordinate theme weeks (Spy Week on Enigma, Action Week on Spotlight)
- Share marathon content across channels appropriately

### Feedback Integration

Potential future enhancements:

- Track what actually played vs. what was scheduled
- Learn from manual overrides (if users edit Pseudovision directly)
- Adjust based on (hypothetical) viewership data
- User feedback: that marathon was too long

### Bumper Generation

Auto-generate channel promotions:

- Coming up next bumpers
- Theme-appropriate interstitials
- Schedule-aware announcements (Tonight at 8...)

## Current Implementation Status

| Component | Status | Location |
|-----------|--------|----------|
| Pseudovision HTTP client | ✅ Implemented | `src/tunarr/scheduler/backends/pseudovision/client.clj` |
| Channel sync | ✅ Implemented | `src/tunarr/scheduler/channels/sync.clj` |
| Tag sync to Pseudovision | ✅ Implemented | `src/tunarr/scheduler/media/pseudovision_sync.clj` |
| Schedule generator | ✅ Implemented | `src/tunarr/scheduler/scheduling/pseudovision.clj` |
| Episode tracking | ❌ Not implemented | Planned |
| User instructions | ❌ Not implemented | Planned |
| LLM scheduling agent | ❌ Not implemented | Planned |
| Web UI | ❌ Not implemented | Planned |

See `TODO.md` for the complete roadmap.