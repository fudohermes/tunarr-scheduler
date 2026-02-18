-- Add parent_id to media for episode->series linkage
ALTER TABLE media
  ADD COLUMN parent_id VARCHAR(128) REFERENCES media(id) ON DELETE CASCADE;

--;;

-- Add season and episode number columns
ALTER TABLE media
  ADD COLUMN season_number INT;

--;;

ALTER TABLE media
  ADD COLUMN episode_number INT;

--;;

-- Index for fast child lookups
CREATE INDEX idx_media_parent_id ON media(parent_id);

--;;

-- Index for ordered episode listing within a series
CREATE INDEX idx_media_episode_order ON media(parent_id, season_number, episode_number);

--;;

-- Constraint: episodes must have a parent, series/movies must not
ALTER TABLE media
  ADD CONSTRAINT chk_episode_parent CHECK (
    (media_type = 'episode' AND parent_id IS NOT NULL)
    OR (media_type != 'episode')
  );

--;;

-- Constraint: episodes must have season and episode numbers
ALTER TABLE media
  ADD CONSTRAINT chk_episode_numbers CHECK (
    (media_type = 'episode' AND season_number IS NOT NULL AND episode_number IS NOT NULL)
    OR (media_type != 'episode')
  );
