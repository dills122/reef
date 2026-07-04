CREATE TABLE IF NOT EXISTS runtime.order_lifecycle_state (
  order_id TEXT PRIMARY KEY,
  engine_order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  side TEXT NOT NULL,
  order_type TEXT NOT NULL,
  original_quantity_units TEXT NOT NULL,
  remaining_quantity_units TEXT NOT NULL,
  filled_quantity_units TEXT NOT NULL,
  limit_price TEXT NOT NULL,
  currency TEXT NOT NULL,
  time_in_force TEXT NOT NULL,
  status TEXT NOT NULL,
  accepted_at TEXT NOT NULL,
  last_event_at TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_lifecycle_state_book
  ON runtime.order_lifecycle_state(instrument_id, status, side, limit_price);
