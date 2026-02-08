-- Add media categorization rationale

ALTER TABLE media_categorization
  ADD COLUMN rationale VARCHAR(512) NOT NULL;

--;;
