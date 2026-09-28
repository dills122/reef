-- Default-off sibling market projection. Matching outcomes remain execution authority.
CREATE TABLE postmatch.matching_market_candidate_frontiers (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL CHECK (partition_id >= 0),
  projector_generation TEXT NOT NULL CHECK (projector_generation <> ''),
  source_generation TEXT NOT NULL CHECK (source_generation <> ''),
  last_stream_sequence BIGINT NOT NULL CHECK (last_stream_sequence >= 0),
  last_window_digest TEXT,
  PRIMARY KEY (event_stream, partition_id, projector_generation),
  CHECK (last_window_digest IS NULL OR last_window_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE postmatch.matching_market_candidate_windows (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  projector_generation TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  from_exclusive_sequence BIGINT NOT NULL,
  through_inclusive_sequence BIGINT NOT NULL,
  verified_source_digest TEXT NOT NULL CHECK (verified_source_digest ~ '^[0-9a-f]{64}$'),
  exact_source_digest TEXT NOT NULL CHECK (exact_source_digest ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY (event_stream, partition_id, projector_generation, through_inclusive_sequence),
  FOREIGN KEY (event_stream, partition_id, projector_generation)
    REFERENCES postmatch.matching_market_candidate_frontiers
      (event_stream, partition_id, projector_generation),
  CHECK (through_inclusive_sequence > from_exclusive_sequence)
);

CREATE TABLE postmatch.matching_market_candidate_orders (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  projector_generation TEXT NOT NULL,
  order_id TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  order_type TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')),
  original_quantity NUMERIC NOT NULL CHECK (original_quantity >= 0),
  remaining_quantity NUMERIC NOT NULL CHECK (remaining_quantity >= 0),
  filled_quantity NUMERIC NOT NULL CHECK (filled_quantity >= 0),
  limit_price NUMERIC NOT NULL CHECK (limit_price >= 0),
  PRIMARY KEY (event_stream, partition_id, projector_generation, order_id),
  FOREIGN KEY (event_stream, partition_id, projector_generation)
    REFERENCES postmatch.matching_market_candidate_frontiers
      (event_stream, partition_id, projector_generation),
  CHECK (filled_quantity + remaining_quantity <= original_quantity),
  CHECK (status = 'CANCELLED' OR filled_quantity + remaining_quantity = original_quantity),
  CHECK (status <> 'ACCEPTED' OR
         (filled_quantity = 0 AND remaining_quantity = original_quantity)),
  CHECK (status <> 'PARTIALLY_FILLED' OR
         (filled_quantity > 0 AND remaining_quantity > 0)),
  CHECK (status <> 'FILLED' OR
         (filled_quantity = original_quantity AND remaining_quantity = 0)),
  CHECK (status <> 'CANCELLED' OR remaining_quantity = 0)
);

CREATE TABLE postmatch.matching_market_candidate_levels (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  projector_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
  price NUMERIC NOT NULL CHECK (price >= 0),
  quantity NUMERIC NOT NULL CHECK (quantity > 0),
  PRIMARY KEY (event_stream, partition_id, projector_generation, run_id,
               venue_session_id, instrument_id, currency, side, price),
  FOREIGN KEY (event_stream, partition_id, projector_generation)
    REFERENCES postmatch.matching_market_candidate_frontiers
      (event_stream, partition_id, projector_generation)
);

CREATE TABLE postmatch.matching_market_candidate_tape (
  event_stream TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  projector_generation TEXT NOT NULL,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  quantity_units NUMERIC NOT NULL CHECK (quantity_units > 0),
  price NUMERIC NOT NULL CHECK (price >= 0),
  occurred_at_text TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  source_stream_sequence BIGINT NOT NULL,
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  PRIMARY KEY (event_stream, partition_id, projector_generation, trade_id),
  UNIQUE (event_stream, partition_id, projector_generation, source_stream_sequence,
          source_effect_ordinal),
  FOREIGN KEY (event_stream, partition_id, projector_generation)
    REFERENCES postmatch.matching_market_candidate_frontiers
      (event_stream, partition_id, projector_generation)
);
CREATE INDEX matching_market_candidate_tape_read_idx
  ON postmatch.matching_market_candidate_tape
  (event_stream, partition_id, projector_generation, run_id, venue_session_id,
   instrument_id, currency, source_stream_sequence DESC, source_effect_ordinal DESC);
