-- Serialize dirty invalidation with claim/recompute/delete at READ COMMITTED.
-- A producer must update an existing marker so it waits for a consumer holding
-- that marker; after the consumer deletes/commits, the producer leaves a new
-- marker instead of silently losing a concurrently committed source change.
-- Conflict updates preserve the existing oldest dirtied_at and queue order;
-- the unchanged value still acquires the row/update lock needed for serialization.
-- Consumers claim at most the existing 5000-row bound in a separate statement,
-- then recompute with a fresh snapshot while retaining claimed row locks.
-- Business formulas, replay checks, function signatures and queue schema stay
-- unchanged. No backfill: previously stale rows behind empty queues require
-- explicit quiesced lifecycle rebuild followed by market refresh and reference
-- verification before writers/maintainers resume.

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_status_stage(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  persisted_count BIGINT := 0;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM jsonb_array_elements(p_outcomes) AS outcome
    JOIN runtime.submit_results existing
      ON existing.command_id = outcome->>'commandId'
    WHERE existing.result_type IS DISTINCT FROM outcome->>'resultType'
       OR existing.event_id IS DISTINCT FROM outcome->>'eventId'
       OR existing.order_id IS DISTINCT FROM outcome->>'orderId'
       OR existing.engine_order_id IS DISTINCT FROM outcome->>'engineOrderId'
       OR existing.code IS DISTINCT FROM outcome->>'code'
       OR existing.reason IS DISTINCT FROM outcome->>'reason'
       OR existing.occurred_at IS DISTINCT FROM outcome->>'occurredAt'
  ) THEN
    RAISE EXCEPTION 'submit result conflict for existing command_id';
  END IF;

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  upsert_results AS (
    INSERT INTO runtime.submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
    SELECT
      outcome->>'commandId',
      outcome->>'resultType',
      outcome->>'eventId',
      outcome->>'orderId',
      outcome->>'engineOrderId',
      outcome->>'code',
      outcome->>'reason',
      outcome->>'occurredAt'
    FROM outcomes
    ON CONFLICT (command_id) DO UPDATE SET
      command_id = runtime.submit_results.command_id
    WHERE runtime.submit_results.result_type = EXCLUDED.result_type
      AND runtime.submit_results.event_id = EXCLUDED.event_id
      AND runtime.submit_results.order_id = EXCLUDED.order_id
      AND runtime.submit_results.engine_order_id = EXCLUDED.engine_order_id
      AND runtime.submit_results.code = EXCLUDED.code
      AND runtime.submit_results.reason = EXCLUDED.reason
      AND runtime.submit_results.occurred_at = EXCLUDED.occurred_at
    RETURNING 1
  ),
  accepted_orders AS (
    SELECT NULLIF(outcome->'acceptedOrder', 'null'::jsonb) AS accepted_order
    FROM outcomes
  ),
  upsert_orders AS (
    INSERT INTO runtime.orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
    SELECT
      accepted_order->>'orderId',
      accepted_order->>'engineOrderId',
      accepted_order->>'instrumentId',
      accepted_order->>'participantId',
      accepted_order->>'accountId',
      accepted_order->>'side',
      accepted_order->>'orderType',
      accepted_order->>'quantityUnits',
      accepted_order->>'limitPrice',
      accepted_order->>'currency',
      accepted_order->>'timeInForce',
      accepted_order->>'acceptedAt',
      COALESCE(accepted_order->>'clientOrderId', ''),
      COALESCE(accepted_order->>'runId', ''),
      COALESCE(accepted_order->>'venueSessionId', '')
    FROM accepted_orders
    WHERE accepted_order IS NOT NULL
      AND jsonb_typeof(accepted_order) = 'object'
    ON CONFLICT (order_id) DO UPDATE SET
      engine_order_id = EXCLUDED.engine_order_id,
      instrument_id = EXCLUDED.instrument_id,
      participant_id = EXCLUDED.participant_id,
      account_id = EXCLUDED.account_id,
      side = EXCLUDED.side,
      order_type = EXCLUDED.order_type,
      quantity_units = EXCLUDED.quantity_units,
      limit_price = EXCLUDED.limit_price,
      currency = EXCLUDED.currency,
      time_in_force = EXCLUDED.time_in_force,
      accepted_at = EXCLUDED.accepted_at,
      client_order_id = EXCLUDED.client_order_id,
      run_id = EXCLUDED.run_id,
      venue_session_id = EXCLUDED.venue_session_id
    RETURNING 1
  ),
  insert_executions AS (
    INSERT INTO runtime.executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role)
    SELECT
      execution->>'eventId',
      execution->>'executionId',
      execution->>'orderId',
      execution->>'instrumentId',
      execution->>'quantityUnits',
      execution->>'executionPrice',
      execution->>'currency',
      execution->>'occurredAt',
      COALESCE(NULLIF(execution->>'liquidityRole', ''), 'UNSPECIFIED')
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
        ELSE '[]'::jsonb
      END
    ) AS execution
    ON CONFLICT (event_id) DO UPDATE SET
      event_id = runtime.runtime_reject_execution_replay_conflict(EXCLUDED.event_id)
    WHERE ROW(
      runtime.executions.execution_id,
      runtime.executions.order_id,
      runtime.executions.instrument_id,
      runtime.executions.quantity_units,
      runtime.executions.execution_price,
      runtime.executions.currency,
      runtime.executions.occurred_at,
      runtime.executions.liquidity_role
    ) IS DISTINCT FROM ROW(
      EXCLUDED.execution_id,
      EXCLUDED.order_id,
      EXCLUDED.instrument_id,
      EXCLUDED.quantity_units,
      EXCLUDED.execution_price,
      EXCLUDED.currency,
      EXCLUDED.occurred_at,
      EXCLUDED.liquidity_role
    )
    RETURNING 1
  ),
  insert_trades AS (
    INSERT INTO runtime.trades(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at)
    SELECT
      trade->>'eventId',
      trade->>'tradeId',
      trade->>'executionId',
      trade->>'buyOrderId',
      trade->>'sellOrderId',
      trade->>'instrumentId',
      trade->>'quantityUnits',
      trade->>'price',
      trade->>'currency',
      trade->>'occurredAt'
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
        ELSE '[]'::jsonb
      END
    ) AS trade
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  ),
  dirty_ids AS (
    SELECT DISTINCT order_id FROM (
      SELECT outcome->>'orderId' AS order_id FROM outcomes
      UNION ALL
      SELECT trade->>'buyOrderId'
      FROM outcomes
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades' ELSE '[]'::jsonb END
      ) AS trade
      UNION ALL
      SELECT trade->>'sellOrderId'
      FROM outcomes
      CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades' ELSE '[]'::jsonb END
      ) AS trade
    ) ids
    WHERE COALESCE(order_id, '') <> ''
  ),
  mark_dirty AS (
    INSERT INTO runtime.order_lifecycle_dirty AS dirty(order_id)
    SELECT order_id FROM dirty_ids
    ORDER BY order_id
    ON CONFLICT (order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_project_order_lifecycle_state(
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
  claimed_ids TEXT[];
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  -- Claim locks first. The next SQL statement receives a fresh READ COMMITTED
  -- snapshot in this VOLATILE function, including source facts committed before
  -- these locks were acquired.
  SELECT array_agg(claimed.order_id) INTO claimed_ids
  FROM (
    SELECT order_id
    FROM runtime.order_lifecycle_dirty
    ORDER BY dirtied_at, order_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
  ) claimed;

  IF claimed_ids IS NULL THEN
    RETURN 0;
  END IF;

  WITH selected_dirty AS (
    SELECT unnest(claimed_ids) AS order_id
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
        WHEN current_quantity_units::NUMERIC > 0 AND remaining_quantity_units = 0 THEN 'FILLED'
        WHEN filled_quantity_units > 0 THEN 'PARTIALLY_FILLED'
        ELSE 'OPEN'
      END,
      accepted_at,
      last_event_at,
      NOW(),
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
    INSERT INTO runtime.market_data_snapshot_dirty AS dirty(instrument_id, dirtied_at)
    SELECT instrument_id, NOW()
    FROM touched_instruments
    ORDER BY instrument_id
    ON CONFLICT (instrument_id) DO UPDATE SET dirtied_at = dirty.dirtied_at
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

CREATE OR REPLACE FUNCTION runtime.runtime_project_market_data_snapshots(
  p_projection_name TEXT,
  p_source_projection_name TEXT,
  p_last_partition_seq BIGINT,
  p_lag BIGINT,
  p_batch_size INTEGER
)
RETURNS BIGINT
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
  projected_count BIGINT := 0;
  effective_batch_size INTEGER := 0;
  claimed_ids TEXT[];
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  -- Claim locks first. The next SQL statement receives a fresh READ COMMITTED
  -- snapshot in this VOLATILE function, including source facts committed before
  -- these locks were acquired.
  SELECT array_agg(claimed.instrument_id) INTO claimed_ids
  FROM (
    SELECT instrument_id
    FROM runtime.market_data_snapshot_dirty
    ORDER BY instrument_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
  ) claimed;

  IF claimed_ids IS NULL THEN
    RETURN 0;
  END IF;

  WITH selected_dirty AS (
    SELECT unnest(claimed_ids) AS instrument_id
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
    DELETE FROM runtime.market_data_snapshots snapshots
    USING selected_dirty
    WHERE snapshots.projection_name = p_projection_name
      AND snapshots.instrument_id = selected_dirty.instrument_id
      AND NOT EXISTS (
        SELECT 1
        FROM present_instruments present
        WHERE present.instrument_id = selected_dirty.instrument_id
      )
    RETURNING 1
  ),
  cleared AS (
    DELETE FROM runtime.market_data_snapshot_dirty dirty
    USING selected_dirty
    WHERE dirty.instrument_id = selected_dirty.instrument_id
    RETURNING 1
  )
  SELECT COUNT(*) INTO projected_count FROM selected_dirty;

  RETURN projected_count;
END;
$$;
