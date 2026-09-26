-- Stable source identity for isolated post-match consumers. Database restarts
-- retain this row; a rebuilt runtime database receives a different generation.
CREATE TABLE IF NOT EXISTS runtime.postmatch_source_generation (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    generation UUID NOT NULL DEFAULT gen_random_uuid()
);

INSERT INTO runtime.postmatch_source_generation (singleton)
VALUES (TRUE)
ON CONFLICT (singleton) DO NOTHING;
