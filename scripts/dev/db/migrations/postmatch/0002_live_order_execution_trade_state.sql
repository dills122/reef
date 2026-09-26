-- Rebuildable operational state. The canonical outcome and batch tables remain
-- in the source database; these rows advance with the live consumer frontier.
CREATE TABLE postmatch.live_order_state (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED')),
  original_quantity NUMERIC NOT NULL CHECK (original_quantity >= 0),
  remaining_quantity NUMERIC NOT NULL CHECK (remaining_quantity >= 0),
  filled_quantity NUMERIC NOT NULL CHECK (filled_quantity >= 0),
  limit_price NUMERIC NOT NULL CHECK (limit_price >= 0),
  currency TEXT NOT NULL,
  last_event_at TIMESTAMPTZ NOT NULL,
  source_partition_id INTEGER NOT NULL CHECK (source_partition_id >= 0),
  source_stream_sequence BIGINT NOT NULL CHECK (source_stream_sequence > 0),
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY (event_stream, source_generation, order_id),
  FOREIGN KEY (event_stream, source_generation, order_id)
    REFERENCES postmatch.canonical_order_directory(event_stream, source_generation, order_id),
  CHECK (filled_quantity + remaining_quantity <= original_quantity),
  CHECK (status = 'CANCELLED' OR filled_quantity + remaining_quantity = original_quantity),
  CHECK (status <> 'CANCELLED' OR remaining_quantity = 0),
  CHECK (status <> 'FILLED' OR (filled_quantity = original_quantity AND remaining_quantity = 0)),
  CHECK (status <> 'PARTIALLY_FILLED' OR (filled_quantity > 0 AND remaining_quantity > 0)),
  CHECK (status <> 'ACCEPTED' OR (filled_quantity = 0 AND remaining_quantity = original_quantity)),
  CHECK (event_stream <> '' AND source_generation <> '' AND order_id <> '' AND instrument_id <> ''
    AND status <> '' AND currency <> '')
);

CREATE TABLE postmatch.live_execution_facts (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units NUMERIC NOT NULL CHECK (quantity_units > 0),
  execution_price NUMERIC NOT NULL CHECK (execution_price >= 0),
  currency TEXT NOT NULL,
  liquidity_role TEXT NOT NULL CHECK (liquidity_role IN ('MAKER', 'TAKER')),
  occurred_at TIMESTAMPTZ NOT NULL,
  source_partition_id INTEGER NOT NULL CHECK (source_partition_id >= 0),
  source_stream_sequence BIGINT NOT NULL CHECK (source_stream_sequence > 0),
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  PRIMARY KEY (event_stream, source_generation, execution_id),
  UNIQUE (event_stream, source_generation, event_id),
  FOREIGN KEY (event_stream, source_generation, order_id)
    REFERENCES postmatch.canonical_order_directory(event_stream, source_generation, order_id),
  CHECK (event_stream <> '' AND source_generation <> '' AND execution_id <> '' AND event_id <> ''
    AND order_id <> '' AND instrument_id <> '' AND currency <> '')
);

CREATE INDEX idx_live_execution_order_time
  ON postmatch.live_execution_facts(event_stream, source_generation, order_id, occurred_at DESC, execution_id);

CREATE TABLE postmatch.live_trade_facts (
  event_stream TEXT NOT NULL,
  source_generation TEXT NOT NULL,
  trade_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  buy_order_id TEXT NOT NULL,
  sell_order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units NUMERIC NOT NULL CHECK (quantity_units > 0),
  price NUMERIC NOT NULL CHECK (price >= 0),
  currency TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  source_partition_id INTEGER NOT NULL CHECK (source_partition_id >= 0),
  source_stream_sequence BIGINT NOT NULL CHECK (source_stream_sequence > 0),
  source_effect_ordinal INTEGER NOT NULL CHECK (source_effect_ordinal >= 0),
  PRIMARY KEY (event_stream, source_generation, trade_id),
  UNIQUE (event_stream, source_generation, event_id),
  FOREIGN KEY (event_stream, source_generation, buy_order_id)
    REFERENCES postmatch.canonical_order_directory(event_stream, source_generation, order_id),
  FOREIGN KEY (event_stream, source_generation, sell_order_id)
    REFERENCES postmatch.canonical_order_directory(event_stream, source_generation, order_id),
  CHECK (buy_order_id <> sell_order_id),
  CHECK (event_stream <> '' AND source_generation <> '' AND trade_id <> '' AND event_id <> ''
    AND execution_id <> '' AND buy_order_id <> '' AND sell_order_id <> ''
    AND instrument_id <> '' AND currency <> '')
);

CREATE INDEX idx_live_trade_buy_order_time
  ON postmatch.live_trade_facts(event_stream, source_generation, buy_order_id, occurred_at DESC, trade_id);
CREATE INDEX idx_live_trade_sell_order_time
  ON postmatch.live_trade_facts(event_stream, source_generation, sell_order_id, occurred_at DESC, trade_id);
