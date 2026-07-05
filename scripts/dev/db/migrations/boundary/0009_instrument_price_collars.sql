CREATE SCHEMA IF NOT EXISTS boundary;

CREATE TABLE IF NOT EXISTS boundary.instrument_price_collars (
  instrument_id TEXT NOT NULL PRIMARY KEY,
  min_price TEXT NOT NULL DEFAULT '',
  max_price TEXT NOT NULL DEFAULT '',
  currency TEXT NOT NULL DEFAULT '',
  reason TEXT NOT NULL DEFAULT '',
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
