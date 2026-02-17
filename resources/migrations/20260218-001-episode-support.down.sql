ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_numbers;

--;;

ALTER TABLE media DROP CONSTRAINT IF EXISTS chk_episode_parent;

--;;

DROP INDEX IF EXISTS idx_media_episode_order;

--;;

DROP INDEX IF EXISTS idx_media_parent_id;

--;;

ALTER TABLE media DROP COLUMN IF EXISTS episode_number;

--;;

ALTER TABLE media DROP COLUMN IF EXISTS season_number;

--;;

ALTER TABLE media DROP COLUMN IF EXISTS parent_id;
