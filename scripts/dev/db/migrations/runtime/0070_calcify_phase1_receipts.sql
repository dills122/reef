-- Calcify Phase 1: source generation registry and processing receipts only.
-- A receipt means verified commitment recorded, never financial settlement.
CREATE TABLE IF NOT EXISTS runtime.calcify_source_generations (
    source_generation integer PRIMARY KEY CHECK (source_generation > 0),
    source_topic text NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO runtime.calcify_source_generations (source_generation, source_topic)
VALUES (1, 'REEF_VENUE_EVENTS')
ON CONFLICT (source_generation) DO NOTHING;

CREATE TABLE IF NOT EXISTS runtime.calcify_commitment_receipts (
    source_generation integer NOT NULL REFERENCES runtime.calcify_source_generations(source_generation),
    source_partition integer NOT NULL CHECK (source_partition >= 0),
    source_offset bigint NOT NULL CHECK (source_offset >= 0),
    trade_ordinal integer NOT NULL CHECK (trade_ordinal >= 0),
    policy_version integer NOT NULL CHECK (policy_version > 0),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_generation, source_partition, source_offset, trade_ordinal)
);
