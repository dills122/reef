-- One committed live-state window is the durable input to the independent
-- market maintainer. A zero-change window still advances market coverage.
CREATE TABLE postmatch.live_market_change_windows (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  from_exclusive_sequence BIGINT NOT NULL CHECK (from_exclusive_sequence >= 0),
  through_inclusive_sequence BIGINT NOT NULL,
  source_digest TEXT NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
  change_count INTEGER NOT NULL CHECK (change_count >= 0),
  committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, source_generation, partition_id, through_inclusive_sequence),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE TABLE postmatch.live_market_order_changes (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  through_inclusive_sequence BIGINT NOT NULL,
  order_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  old_price NUMERIC CHECK (old_price >= 0),
  old_quantity NUMERIC NOT NULL CHECK (old_quantity >= 0),
  new_price NUMERIC CHECK (new_price >= 0),
  new_quantity NUMERIC NOT NULL CHECK (new_quantity >= 0),
  source_stream_sequence BIGINT NOT NULL CHECK (source_stream_sequence > 0),
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  PRIMARY KEY (event_stream, source_generation, partition_id, through_inclusive_sequence, order_id),
  FOREIGN KEY (event_stream, source_generation, partition_id, through_inclusive_sequence)
    REFERENCES postmatch.live_market_change_windows(event_stream, source_generation, partition_id, through_inclusive_sequence),
  FOREIGN KEY (event_stream, source_generation, order_id)
    REFERENCES postmatch.canonical_order_directory(event_stream, source_generation, order_id),
  CHECK ((old_quantity = 0 AND old_price IS NULL) OR (old_quantity > 0 AND old_price IS NOT NULL)),
  CHECK ((new_quantity = 0 AND new_price IS NULL) OR (new_quantity > 0 AND new_price IS NOT NULL))
);

CREATE TABLE postmatch.market_price_levels (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  price NUMERIC NOT NULL CHECK (price >= 0),
  quantity NUMERIC NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (event_stream, source_generation, run_id, venue_session_id, instrument_id, currency, side, price)
);

CREATE TABLE postmatch.market_snapshots (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  best_bid_price NUMERIC,
  best_bid_quantity NUMERIC,
  best_ask_price NUMERIC,
  best_ask_quantity NUMERIC,
  last_partition_id INTEGER NOT NULL CHECK (last_partition_id >= 0),
  last_stream_sequence BIGINT NOT NULL CHECK (last_stream_sequence > 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, source_generation, run_id, venue_session_id, instrument_id, currency),
  CHECK ((best_bid_price IS NULL) = (best_bid_quantity IS NULL)),
  CHECK ((best_ask_price IS NULL) = (best_ask_quantity IS NULL))
);
