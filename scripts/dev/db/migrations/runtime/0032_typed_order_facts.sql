-- Add typed companion facts for accepted orders.
--
-- Existing text columns remain the compatibility surface for API payloads.
-- Typed companions support native time and numeric access for order history,
-- client-order lookups, lifecycle projection inputs, and future risk checks.

ALTER TABLE runtime.orders
  ADD COLUMN IF NOT EXISTS quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC,
  ADD COLUMN IF NOT EXISTS accepted_at_ts TIMESTAMPTZ;

UPDATE runtime.orders
SET
  quantity_units_num = CASE
    WHEN quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN quantity_units::NUMERIC
    ELSE NULL
  END,
  limit_price_num = CASE
    WHEN limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN limit_price::NUMERIC
    ELSE NULL
  END,
  accepted_at_ts = CASE
    WHEN accepted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN accepted_at::TIMESTAMPTZ
    ELSE NULL
  END;

CREATE OR REPLACE FUNCTION runtime.orders_set_typed_facts()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.quantity_units_num := CASE
    WHEN NEW.quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN NEW.quantity_units::NUMERIC
    ELSE NULL
  END;

  NEW.limit_price_num := CASE
    WHEN NEW.limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN NEW.limit_price::NUMERIC
    ELSE NULL
  END;

  NEW.accepted_at_ts := CASE
    WHEN NEW.accepted_at ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' THEN NEW.accepted_at::TIMESTAMPTZ
    ELSE NULL
  END;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS orders_set_typed_facts ON runtime.orders;

CREATE TRIGGER orders_set_typed_facts
BEFORE INSERT OR UPDATE OF quantity_units, limit_price, accepted_at ON runtime.orders
FOR EACH ROW
EXECUTE FUNCTION runtime.orders_set_typed_facts();

CREATE INDEX IF NOT EXISTS idx_orders_participant_instrument_accepted_typed
  ON runtime.orders(participant_id, instrument_id, accepted_at_ts)
  WHERE accepted_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_participant_client_order_accepted_typed
  ON runtime.orders(participant_id, client_order_id, accepted_at_ts DESC)
  WHERE client_order_id <> ''
    AND accepted_at_ts IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_orders_accepted_typed
  ON runtime.orders(accepted_at_ts, order_id)
  WHERE accepted_at_ts IS NOT NULL;
