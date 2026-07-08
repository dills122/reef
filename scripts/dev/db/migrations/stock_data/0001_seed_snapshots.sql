-- Stock-data seed snapshot facts. Persisted once per game seed so
-- simulation/replay/audit never need to call the external stock-data
-- provider again. See docs/STOCK_DATA_SEEDING_PLAN.md.

CREATE SCHEMA IF NOT EXISTS stock_data;

CREATE TABLE IF NOT EXISTS stock_data.seed_snapshot_batches (
  game_seed_id TEXT PRIMARY KEY,
  as_of TIMESTAMPTZ NOT NULL,
  batch_seed_hash TEXT NOT NULL,
  inserted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS stock_data.seed_snapshots (
  game_seed_id TEXT NOT NULL REFERENCES stock_data.seed_snapshot_batches(game_seed_id),
  symbol TEXT NOT NULL,
  provider TEXT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type IN ('intraday_current', 'historical_eod', 'cached_fallback')),
  as_of TIMESTAMPTZ NOT NULL,
  source_timestamp TIMESTAMPTZ NOT NULL,
  retrieved_at TIMESTAMPTZ NOT NULL,
  currency TEXT NOT NULL,
  price NUMERIC NOT NULL,
  open NUMERIC,
  high NUMERIC,
  low NUMERIC,
  previous_close NUMERIC,
  volume BIGINT,
  raw_provider_payload_hash TEXT NOT NULL,
  selection_reason TEXT NOT NULL,
  PRIMARY KEY (game_seed_id, symbol)
);
