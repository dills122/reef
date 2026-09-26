-- Parse each outcome trade array once for persistence and lifecycle invalidation.
-- Reject conflicting trade replays while preserving identical replay and
-- dirty-marker serialization.

CREATE OR REPLACE FUNCTION runtime.runtime_reject_trade_replay_conflict(
  p_event_id TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'trade replay conflict for existing event_id %', p_event_id
    USING ERRCODE = '23505';
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_reject_submit_result_replay_conflict(
  p_command_id TEXT
)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'submit result replay conflict for existing command_id %', p_command_id
    USING ERRCODE = '23505';
END;
$$;

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

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  parsed_trades AS MATERIALIZED (
    SELECT trade
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
        ELSE '[]'::jsonb
      END
    ) AS trade
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
      command_id = runtime.runtime_reject_submit_result_replay_conflict(EXCLUDED.command_id)
    WHERE ROW(
      runtime.submit_results.result_type,
      runtime.submit_results.event_id,
      runtime.submit_results.order_id,
      runtime.submit_results.engine_order_id,
      runtime.submit_results.code,
      runtime.submit_results.reason,
      runtime.submit_results.occurred_at
    ) IS DISTINCT FROM ROW(
      EXCLUDED.result_type,
      EXCLUDED.event_id,
      EXCLUDED.order_id,
      EXCLUDED.engine_order_id,
      EXCLUDED.code,
      EXCLUDED.reason,
      EXCLUDED.occurred_at
    )
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
    FROM parsed_trades
    ON CONFLICT (event_id) DO UPDATE SET
      event_id = runtime.runtime_reject_trade_replay_conflict(EXCLUDED.event_id)
    WHERE ROW(
      runtime.trades.trade_id,
      runtime.trades.execution_id,
      runtime.trades.buy_order_id,
      runtime.trades.sell_order_id,
      runtime.trades.instrument_id,
      runtime.trades.quantity_units,
      runtime.trades.price,
      runtime.trades.currency,
      runtime.trades.occurred_at
    ) IS DISTINCT FROM ROW(
      EXCLUDED.trade_id,
      EXCLUDED.execution_id,
      EXCLUDED.buy_order_id,
      EXCLUDED.sell_order_id,
      EXCLUDED.instrument_id,
      EXCLUDED.quantity_units,
      EXCLUDED.price,
      EXCLUDED.currency,
      EXCLUDED.occurred_at
    )
    RETURNING 1
  ),
  dirty_ids AS (
    SELECT DISTINCT order_id FROM (
      SELECT outcome->>'orderId' AS order_id FROM outcomes
      UNION ALL
      SELECT trade_order.order_id
      FROM parsed_trades
      CROSS JOIN LATERAL (VALUES (trade->>'buyOrderId'), (trade->>'sellOrderId')) AS trade_order(order_id)
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
