CREATE TABLE IF NOT EXISTS runtime.market_data_snapshots (
  projection_name TEXT NOT NULL,
  source_projection_name TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  best_bid_price TEXT NOT NULL DEFAULT '',
  best_bid_quantity TEXT NOT NULL DEFAULT '',
  best_ask_price TEXT NOT NULL DEFAULT '',
  best_ask_quantity TEXT NOT NULL DEFAULT '',
  currency TEXT NOT NULL DEFAULT '',
  last_partition_seq BIGINT NOT NULL DEFAULT 0,
  lag BIGINT NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (projection_name, instrument_id)
);
