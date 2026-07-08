-- Add typed projection facts for top-of-book and lifecycle book access.
--
-- Existing text columns remain the API/read compatibility surface. The numeric
-- companion columns give Postgres native ordering, grouping, and index support
-- for HFT-sensitive market-data projection and depth reads.

ALTER TABLE runtime.order_lifecycle_state
  ADD COLUMN IF NOT EXISTS original_quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS remaining_quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS filled_quantity_units_num NUMERIC,
  ADD COLUMN IF NOT EXISTS limit_price_num NUMERIC;

ALTER TABLE runtime.market_data_snapshots
  ADD COLUMN IF NOT EXISTS best_bid_price_num NUMERIC,
  ADD COLUMN IF NOT EXISTS best_bid_quantity_num NUMERIC,
  ADD COLUMN IF NOT EXISTS best_ask_price_num NUMERIC,
  ADD COLUMN IF NOT EXISTS best_ask_quantity_num NUMERIC;

UPDATE runtime.order_lifecycle_state
SET
  original_quantity_units_num = CASE
    WHEN original_quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN original_quantity_units::NUMERIC
    ELSE NULL
  END,
  remaining_quantity_units_num = CASE
    WHEN remaining_quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN remaining_quantity_units::NUMERIC
    ELSE NULL
  END,
  filled_quantity_units_num = CASE
    WHEN filled_quantity_units ~ '^[0-9]+(\.[0-9]+)?$' THEN filled_quantity_units::NUMERIC
    ELSE NULL
  END,
  limit_price_num = CASE
    WHEN limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN limit_price::NUMERIC
    ELSE NULL
  END;

UPDATE runtime.market_data_snapshots
SET
  best_bid_price_num = CASE
    WHEN best_bid_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN best_bid_price::NUMERIC
    ELSE NULL
  END,
  best_bid_quantity_num = CASE
    WHEN best_bid_quantity ~ '^[0-9]+(\.[0-9]+)?$' THEN best_bid_quantity::NUMERIC
    ELSE NULL
  END,
  best_ask_price_num = CASE
    WHEN best_ask_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN best_ask_price::NUMERIC
    ELSE NULL
  END,
  best_ask_quantity_num = CASE
    WHEN best_ask_quantity ~ '^[0-9]+(\.[0-9]+)?$' THEN best_ask_quantity::NUMERIC
    ELSE NULL
  END;

DROP INDEX IF EXISTS runtime.idx_order_lifecycle_state_book_numeric_price;

CREATE INDEX IF NOT EXISTS idx_order_lifecycle_state_book_bid_native
  ON runtime.order_lifecycle_state(instrument_id, limit_price_num DESC, order_id)
  INCLUDE (remaining_quantity_units_num, currency)
  WHERE order_type = 'LIMIT'
    AND side = 'BUY'
    AND status IN ('OPEN', 'PARTIALLY_FILLED')
    AND limit_price_num IS NOT NULL
    AND remaining_quantity_units_num > 0;

CREATE INDEX IF NOT EXISTS idx_order_lifecycle_state_book_ask_native
  ON runtime.order_lifecycle_state(instrument_id, limit_price_num ASC, order_id)
  INCLUDE (remaining_quantity_units_num, currency)
  WHERE order_type = 'LIMIT'
    AND side = 'SELL'
    AND status IN ('OPEN', 'PARTIALLY_FILLED')
    AND limit_price_num IS NOT NULL
    AND remaining_quantity_units_num > 0;

CREATE OR REPLACE FUNCTION runtime.runtime_project_order_lifecycle_state(
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  WITH selected_dirty AS (
    SELECT order_id
    FROM runtime.order_lifecycle_dirty
    ORDER BY dirtied_at
    LIMIT effective_batch_size
  ),
  execution_totals AS (
    SELECT
      order_id,
      SUM(quantity_units::NUMERIC) AS filled_quantity_units
    FROM runtime.executions
    WHERE order_id IN (SELECT order_id FROM selected_dirty)
      AND quantity_units ~ '^[0-9]+(\.[0-9]+)?$'
    GROUP BY order_id
  ),
  latest_modify AS (
    SELECT DISTINCT ON (order_id)
      order_id,
      COALESCE(NULLIF(payload_json->>'quantityUnits', ''), '') AS modified_quantity_units,
      COALESCE(NULLIF(payload_json->>'limitPrice', ''), '') AS modified_limit_price,
      occurred_at
    FROM runtime.runtime_events
    WHERE event_type = 'OrderModified'
      AND order_id IN (SELECT order_id FROM selected_dirty)
    ORDER BY order_id, occurred_at DESC, sequence_number DESC, event_id DESC
  ),
  order_event_state AS (
    SELECT
      order_id,
      BOOL_OR(event_type = 'OrderCancelled') AS cancelled,
      BOOL_OR(event_type = 'OrderRejected') AS rejected,
      COALESCE(MAX(NULLIF(occurred_at, '')), '') AS last_event_at
    FROM runtime.runtime_events
    WHERE order_id IN (SELECT order_id FROM selected_dirty)
    GROUP BY order_id
  ),
  shaped AS (
    SELECT
      orders.order_id,
      orders.engine_order_id,
      orders.instrument_id,
      orders.participant_id,
      orders.account_id,
      orders.side,
      orders.order_type,
      orders.quantity_units AS original_quantity_units,
      COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) AS current_quantity_units,
      COALESCE(NULLIF(latest_modify.modified_limit_price, ''), orders.limit_price) AS current_limit_price,
      orders.currency,
      orders.time_in_force,
      orders.accepted_at,
      COALESCE(execution_totals.filled_quantity_units, 0) AS filled_quantity_units,
      COALESCE(order_event_state.cancelled, FALSE) AS cancelled,
      COALESCE(order_event_state.rejected, FALSE) AS rejected,
      COALESCE(NULLIF(order_event_state.last_event_at, ''), orders.accepted_at) AS last_event_at
    FROM runtime.orders orders
    JOIN selected_dirty ON selected_dirty.order_id = orders.order_id
    LEFT JOIN execution_totals ON execution_totals.order_id = orders.order_id
    LEFT JOIN latest_modify ON latest_modify.order_id = orders.order_id
    LEFT JOIN order_event_state ON order_event_state.order_id = orders.order_id
    WHERE COALESCE(NULLIF(latest_modify.modified_quantity_units, ''), orders.quantity_units) ~ '^[0-9]+(\.[0-9]+)?$'
  ),
  calculated AS (
    SELECT
      *,
      GREATEST(current_quantity_units::NUMERIC - filled_quantity_units, 0) AS remaining_quantity_units,
      CASE
        WHEN current_limit_price ~ '^-?[0-9]+(\.[0-9]+)?$' THEN current_limit_price::NUMERIC
        ELSE NULL
      END AS current_limit_price_num
    FROM shaped
  ),
  upserted AS (
    INSERT INTO runtime.order_lifecycle_state(
      order_id,
      engine_order_id,
      instrument_id,
      participant_id,
      account_id,
      side,
      order_type,
      original_quantity_units,
      remaining_quantity_units,
      filled_quantity_units,
      limit_price,
      currency,
      time_in_force,
      status,
      accepted_at,
      last_event_at,
      updated_at,
      original_quantity_units_num,
      remaining_quantity_units_num,
      filled_quantity_units_num,
      limit_price_num
    )
    SELECT
      order_id,
      engine_order_id,
      instrument_id,
      participant_id,
      account_id,
      side,
      order_type,
      original_quantity_units,
      CASE WHEN cancelled OR rejected THEN '0' ELSE remaining_quantity_units::TEXT END,
      filled_quantity_units::TEXT,
      current_limit_price,
      currency,
      time_in_force,
      CASE
        WHEN rejected THEN 'REJECTED'
        WHEN cancelled THEN 'CANCELLED'
        WHEN current_quantity_units::NUMERIC > 0 AND remaining_quantity_units = 0 THEN 'FILLED'
        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
        ELSE 'OPEN'
      END,
      accepted_at,
      last_event_at,
      now(),
      original_quantity_units::NUMERIC,
      CASE WHEN cancelled OR rejected THEN 0 ELSE remaining_quantity_units END,
      filled_quantity_units,
      current_limit_price_num
    FROM calculated
    ON CONFLICT (order_id) DO UPDATE SET
      engine_order_id = EXCLUDED.engine_order_id,
      instrument_id = EXCLUDED.instrument_id,
      participant_id = EXCLUDED.participant_id,
      account_id = EXCLUDED.account_id,
      side = EXCLUDED.side,
      order_type = EXCLUDED.order_type,
      original_quantity_units = EXCLUDED.original_quantity_units,
      remaining_quantity_units = EXCLUDED.remaining_quantity_units,
      filled_quantity_units = EXCLUDED.filled_quantity_units,
      limit_price = EXCLUDED.limit_price,
      currency = EXCLUDED.currency,
      time_in_force = EXCLUDED.time_in_force,
      status = EXCLUDED.status,
      accepted_at = EXCLUDED.accepted_at,
      last_event_at = EXCLUDED.last_event_at,
      updated_at = EXCLUDED.updated_at,
      original_quantity_units_num = EXCLUDED.original_quantity_units_num,
      remaining_quantity_units_num = EXCLUDED.remaining_quantity_units_num,
      filled_quantity_units_num = EXCLUDED.filled_quantity_units_num,
      limit_price_num = EXCLUDED.limit_price_num
    RETURNING 1
  ),
  mark_market_data_dirty AS (
    INSERT INTO runtime.market_data_snapshot_dirty(instrument_id)
    SELECT DISTINCT instrument_id FROM calculated
    ON CONFLICT (instrument_id) DO UPDATE SET dirtied_at = now()
    RETURNING 1
  ),
  cleared AS (
    DELETE FROM runtime.order_lifecycle_dirty
    WHERE order_id IN (SELECT order_id FROM selected_dirty)
    RETURNING 1
  )
  SELECT COUNT(*) INTO projected_count FROM selected_dirty;

  RETURN projected_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_project_market_data_snapshots(
  p_projection_name TEXT,
  p_source_projection_name TEXT,
  p_last_partition_seq BIGINT,
  p_lag BIGINT,
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  WITH selected_dirty AS (
    SELECT instrument_id
    FROM runtime.market_data_snapshot_dirty
    ORDER BY dirtied_at
    LIMIT effective_batch_size
  ),
  priced_orders AS (
    SELECT
      instrument_id,
      side,
      currency,
      limit_price_num AS price_num,
      remaining_quantity_units_num AS quantity_num
    FROM runtime.order_lifecycle_state
    WHERE instrument_id IN (SELECT instrument_id FROM selected_dirty)
      AND order_type = 'LIMIT'
      AND status IN ('OPEN', 'PARTIALLY_FILLED')
      AND limit_price_num IS NOT NULL
      AND remaining_quantity_units_num > 0
  ),
  bid_prices AS (
    SELECT instrument_id, MAX(price_num) AS best_bid_price
    FROM priced_orders
    WHERE side = 'BUY'
    GROUP BY instrument_id
  ),
  ask_prices AS (
    SELECT instrument_id, MIN(price_num) AS best_ask_price
    FROM priced_orders
    WHERE side = 'SELL'
    GROUP BY instrument_id
  ),
  bid_totals AS (
    SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_bid_quantity
    FROM priced_orders priced
    JOIN bid_prices best
      ON best.instrument_id = priced.instrument_id
     AND best.best_bid_price = priced.price_num
    WHERE priced.side = 'BUY'
    GROUP BY priced.instrument_id
  ),
  ask_totals AS (
    SELECT priced.instrument_id, SUM(priced.quantity_num) AS best_ask_quantity
    FROM priced_orders priced
    JOIN ask_prices best
      ON best.instrument_id = priced.instrument_id
     AND best.best_ask_price = priced.price_num
    WHERE priced.side = 'SELL'
    GROUP BY priced.instrument_id
  ),
  present_instruments AS (
    SELECT instrument_id, MAX(currency) AS currency
    FROM priced_orders
    GROUP BY instrument_id
  ),
  upsert_present AS (
    INSERT INTO runtime.market_data_snapshots(
      projection_name,
      source_projection_name,
      instrument_id,
      best_bid_price,
      best_bid_quantity,
      best_ask_price,
      best_ask_quantity,
      currency,
      last_partition_seq,
      lag,
      updated_at,
      best_bid_price_num,
      best_bid_quantity_num,
      best_ask_price_num,
      best_ask_quantity_num
    )
    SELECT
      p_projection_name,
      p_source_projection_name,
      present.instrument_id,
      COALESCE(bid_prices.best_bid_price::TEXT, ''),
      COALESCE(bid_totals.best_bid_quantity::TEXT, ''),
      COALESCE(ask_prices.best_ask_price::TEXT, ''),
      COALESCE(ask_totals.best_ask_quantity::TEXT, ''),
      COALESCE(present.currency, ''),
      p_last_partition_seq,
      p_lag,
      now(),
      bid_prices.best_bid_price,
      bid_totals.best_bid_quantity,
      ask_prices.best_ask_price,
      ask_totals.best_ask_quantity
    FROM present_instruments present
    LEFT JOIN bid_prices ON bid_prices.instrument_id = present.instrument_id
    LEFT JOIN bid_totals ON bid_totals.instrument_id = present.instrument_id
    LEFT JOIN ask_prices ON ask_prices.instrument_id = present.instrument_id
    LEFT JOIN ask_totals ON ask_totals.instrument_id = present.instrument_id
    ON CONFLICT (projection_name, instrument_id) DO UPDATE SET
      source_projection_name = EXCLUDED.source_projection_name,
      best_bid_price = EXCLUDED.best_bid_price,
      best_bid_quantity = EXCLUDED.best_bid_quantity,
      best_ask_price = EXCLUDED.best_ask_price,
      best_ask_quantity = EXCLUDED.best_ask_quantity,
      currency = EXCLUDED.currency,
      last_partition_seq = EXCLUDED.last_partition_seq,
      lag = EXCLUDED.lag,
      updated_at = EXCLUDED.updated_at,
      best_bid_price_num = EXCLUDED.best_bid_price_num,
      best_bid_quantity_num = EXCLUDED.best_bid_quantity_num,
      best_ask_price_num = EXCLUDED.best_ask_price_num,
      best_ask_quantity_num = EXCLUDED.best_ask_quantity_num
    RETURNING 1
  ),
  delete_absent AS (
    DELETE FROM runtime.market_data_snapshots
    WHERE projection_name = p_projection_name
      AND instrument_id IN (
        SELECT instrument_id FROM selected_dirty
        EXCEPT
        SELECT instrument_id FROM present_instruments
      )
    RETURNING 1
  ),
  cleared AS (
    DELETE FROM runtime.market_data_snapshot_dirty
    WHERE instrument_id IN (SELECT instrument_id FROM selected_dirty)
    RETURNING 1
  )
  SELECT COUNT(*) INTO projected_count FROM selected_dirty;

  RETURN projected_count;
END;
$$;
