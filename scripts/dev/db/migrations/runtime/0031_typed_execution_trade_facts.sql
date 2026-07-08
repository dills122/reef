-- Add typed companion facts for executions and trades.
--
-- Existing text columns remain the API/read compatibility surface. The typed
-- columns give Postgres native numeric and timestamp facts for execution/trade
-- audit, order history, trade tape, and intraday bar queries.

ALTER TABLE runtime.executions
  ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
  ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS execution_price_num NUMERIC,
  ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ;

ALTER TABLE runtime.trades
  ADD COLUMN IF NOT EXISTS event_id_uuid UUID,
  ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS price_num NUMERIC,
  ADD COLUMN IF NOT EXISTS occurred_at_ts TIMESTAMPTZ;

UPDATE runtime.executions
SET
  event_id_uuid = CASE
    WHEN event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN event_id::UUID
    ELSE NULL
  END,
  quantity_units_num = CASE
    WHEN quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN quantity_units::NUMERIC
    ELSE NULL
  END,
  execution_price_num = CASE
    WHEN execution_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN execution_price::NUMERIC
    ELSE NULL
  END,
  occurred_at_ts = CASE
    WHEN occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

UPDATE runtime.trades
SET
  event_id_uuid = CASE
    WHEN event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN event_id::UUID
    ELSE NULL
  END,
  quantity_units_num = CASE
    WHEN quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN quantity_units::NUMERIC
    ELSE NULL
  END,
  price_num = CASE
    WHEN price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN price::NUMERIC
    ELSE NULL
  END,
  occurred_at_ts = CASE
    WHEN occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

CREATE OR REPLACE FUNCTION runtime.executions_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.event_id_uuid := CASE
    WHEN NEW.event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN NEW.event_id::UUID
    ELSE NULL
  END;

  NEW.quantity_units_num := CASE
    WHEN NEW.quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN NEW.quantity_units::NUMERIC
    ELSE NULL
  END;

  NEW.execution_price_num := CASE
    WHEN NEW.execution_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN NEW.execution_price::NUMERIC
    ELSE NULL
  END;

  NEW.occurred_at_ts := CASE
    WHEN NEW.occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.trades_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.event_id_uuid := CASE
    WHEN NEW.event_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' THEN NEW.event_id::UUID
    ELSE NULL
  END;

  NEW.quantity_units_num := CASE
    WHEN NEW.quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN NEW.quantity_units::NUMERIC
    ELSE NULL
  END;

  NEW.price_num := CASE
    WHEN NEW.price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN NEW.price::NUMERIC
    ELSE NULL
  END;

  NEW.occurred_at_ts := CASE
    WHEN NEW.occurred_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.occurred_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS executions_set_typed_facts ON runtime.executions;
DROP TRIGGER IF EXISTS trades_set_typed_facts ON runtime.trades;

CREATE TRIGGER executions_set_typed_facts
BEFORE INSERT OR UPDATE OF event_id, quantity_units, execution_price, occurred_at ON runtime.executions
FOR EACH ROW
EXECUTE FUNCTION runtime.executions_set_typed_facts();

CREATE TRIGGER trades_set_typed_facts
BEFORE INSERT OR UPDATE OF event_id, quantity_units, price, occurred_at ON runtime.trades
FOR EACH ROW
EXECUTE FUNCTION runtime.trades_set_typed_facts();

CREATE INDEX IF NOT EXISTS idx_executions_order_occurred_typed
  ON runtime.executions(order_id, occurred_at_ts, event_id_uuid)
  WHERE occurred_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_trades_buy_order_occurred_typed
  ON runtime.trades(buy_order_id, occurred_at_ts, event_id_uuid)
  WHERE occurred_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_trades_sell_order_occurred_typed
  ON runtime.trades(sell_order_id, occurred_at_ts, event_id_uuid)
  WHERE occurred_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_trades_instrument_occurred_typed
  ON runtime.trades(instrument_id, occurred_at_ts, sequence)
  INCLUDE (price_num, quantity_units_num)
  WHERE occurred_at_ts IS NOT NULL
    AND price_num IS NOT NULL
    AND quantity_units_num IS NOT NULL;
