CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
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
      result_type = EXCLUDED.result_type,
      event_id = EXCLUDED.event_id,
      order_id = EXCLUDED.order_id,
      engine_order_id = EXCLUDED.engine_order_id,
      code = EXCLUDED.code,
      reason = EXCLUDED.reason,
      occurred_at = EXCLUDED.occurred_at
    RETURNING 1
  ),
  accepted_orders AS (
    SELECT NULLIF(outcome->'acceptedOrder', 'null'::jsonb) AS accepted_order
    FROM outcomes
  ),
  upsert_orders AS (
    INSERT INTO runtime.orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
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
      accepted_order->>'acceptedAt'
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
      accepted_at = EXCLUDED.accepted_at
    RETURNING 1
  ),
  insert_executions AS (
    INSERT INTO runtime.executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at)
    SELECT
      execution->>'eventId',
      execution->>'executionId',
      execution->>'orderId',
      execution->>'instrumentId',
      execution->>'quantityUnits',
      execution->>'executionPrice',
      execution->>'currency',
      execution->>'occurredAt'
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'executions') = 'array' THEN outcome->'executions'
        ELSE '[]'::jsonb
      END
    ) AS execution
    ON CONFLICT (event_id) DO NOTHING
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
  parsed_events AS (
    SELECT
      event,
      outcomes.outcome_ordinality,
      event_ordinality::BIGINT AS event_ordinality
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM parsed_events
    GROUP BY event->>'traceId'
  ),
  trace_allocations AS (
    INSERT INTO runtime.runtime_trace_sequences AS trace_sequence(trace_id, next_sequence)
    SELECT trace_id, event_count FROM trace_counts
    ON CONFLICT (trace_id) DO UPDATE SET next_sequence = trace_sequence.next_sequence + EXCLUDED.next_sequence
    RETURNING trace_id, next_sequence
  ),
  trace_starts AS (
    SELECT
      counts.trace_id,
      allocations.next_sequence - counts.event_count + 1 AS start_sequence
    FROM trace_counts counts
    JOIN trace_allocations allocations ON allocations.trace_id = counts.trace_id
  ),
  ordered_events AS (
    SELECT
      parsed.event,
      parsed.outcome_ordinality,
      parsed.event_ordinality,
      row_number() OVER (
        PARTITION BY parsed.event->>'traceId'
        ORDER BY parsed.outcome_ordinality, parsed.event_ordinality
      ) - 1 AS trace_offset
    FROM parsed_events parsed
  ),
  insert_events AS (
    INSERT INTO runtime.runtime_events(event_id, event_type, order_id, trace_id, causation_id, correlation_id, actor_id, producer, schema_version, sequence_number, payload_json, occurred_at)
    SELECT
      event->>'eventId',
      event->>'eventType',
      event->>'orderId',
      event->>'traceId',
      event->>'causationId',
      event->>'correlationId',
      COALESCE(event->>'actorId', ''),
      event->>'producer',
      event->>'schemaVersion',
      trace_starts.start_sequence + ordered_events.trace_offset,
      COALESCE(event->'payloadJson', '{}'::jsonb),
      event->>'occurredAt'
    FROM ordered_events
    JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
    ORDER BY ordered_events.outcome_ordinality, ordered_events.event_ordinality
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;
