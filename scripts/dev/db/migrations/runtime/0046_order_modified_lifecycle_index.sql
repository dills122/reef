-- Replace the broad order-scoped runtime-event occurred index with a narrow
-- lifecycle index for latest OrderModified lookup. The all-event lifecycle
-- rollup keeps using the remaining order-scoped event indexes.

CREATE INDEX IF NOT EXISTS idx_runtime_events_order_modified_lifecycle
  ON runtime.runtime_events(
    order_id,
    occurred_at_ts DESC NULLS LAST,
    occurred_at DESC,
    sequence_number DESC,
    event_id_uuid DESC NULLS LAST,
    event_id DESC
  )
  WHERE event_type = 'OrderModified';

DROP INDEX IF EXISTS runtime.runtime_events_order_occurred_idx;

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
    ORDER BY dirtied_at, order_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
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
      COALESCE(NULLIF(modify_quantity_units, ''), '') AS modified_quantity_units,
      COALESCE(NULLIF(modify_limit_price, ''), '') AS modified_limit_price,
      occurred_at
    FROM runtime.runtime_events
    WHERE event_type = 'OrderModified'
      AND order_id IN (SELECT order_id FROM selected_dirty)
    ORDER BY order_id,
      occurred_at_ts DESC NULLS LAST,
      occurred_at DESC,
      sequence_number DESC,
      event_id_uuid DESC NULLS LAST,
      event_id DESC
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
    INSERT INTO runtime.order_lifecycle_state AS lifecycle(
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
        WHEN remaining_quantity_units = 0 THEN 'FILLED'
        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
        ELSE 'OPEN'
      END,
      accepted_at,
      last_event_at,
      NOW(),
      original_quantity_units::NUMERIC,
      remaining_quantity_units,
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
    WHERE lifecycle.engine_order_id IS DISTINCT FROM EXCLUDED.engine_order_id
      OR lifecycle.instrument_id IS DISTINCT FROM EXCLUDED.instrument_id
      OR lifecycle.participant_id IS DISTINCT FROM EXCLUDED.participant_id
      OR lifecycle.account_id IS DISTINCT FROM EXCLUDED.account_id
      OR lifecycle.side IS DISTINCT FROM EXCLUDED.side
      OR lifecycle.order_type IS DISTINCT FROM EXCLUDED.order_type
      OR lifecycle.original_quantity_units IS DISTINCT FROM EXCLUDED.original_quantity_units
      OR lifecycle.remaining_quantity_units IS DISTINCT FROM EXCLUDED.remaining_quantity_units
      OR lifecycle.filled_quantity_units IS DISTINCT FROM EXCLUDED.filled_quantity_units
      OR lifecycle.limit_price IS DISTINCT FROM EXCLUDED.limit_price
      OR lifecycle.currency IS DISTINCT FROM EXCLUDED.currency
      OR lifecycle.time_in_force IS DISTINCT FROM EXCLUDED.time_in_force
      OR lifecycle.status IS DISTINCT FROM EXCLUDED.status
      OR lifecycle.accepted_at IS DISTINCT FROM EXCLUDED.accepted_at
      OR lifecycle.last_event_at IS DISTINCT FROM EXCLUDED.last_event_at
      OR lifecycle.original_quantity_units_num IS DISTINCT FROM EXCLUDED.original_quantity_units_num
      OR lifecycle.remaining_quantity_units_num IS DISTINCT FROM EXCLUDED.remaining_quantity_units_num
      OR lifecycle.filled_quantity_units_num IS DISTINCT FROM EXCLUDED.filled_quantity_units_num
      OR lifecycle.limit_price_num IS DISTINCT FROM EXCLUDED.limit_price_num
    RETURNING order_id, instrument_id
  ),
  touched_instruments AS (
    SELECT DISTINCT instrument_id
    FROM upserted
    WHERE instrument_id <> ''
  ),
  mark_market_dirty AS (
    INSERT INTO runtime.market_data_snapshot_dirty(instrument_id, dirtied_at)
    SELECT instrument_id, NOW()
    FROM touched_instruments
    ORDER BY instrument_id
    ON CONFLICT (instrument_id) DO NOTHING
  ),
  cleared AS (
    DELETE FROM runtime.order_lifecycle_dirty dirty
    USING selected_dirty
    WHERE dirty.order_id = selected_dirty.order_id
    RETURNING dirty.order_id
  )
  SELECT COUNT(*) INTO projected_count FROM cleared;

  RETURN projected_count;
END;
$$;
