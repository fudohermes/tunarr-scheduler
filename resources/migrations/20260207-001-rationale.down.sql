-- Remove media categorization rationale

ALTER TABLE media_categorization
  DROP COLUMN IF EXISTS rationale;

--;;
