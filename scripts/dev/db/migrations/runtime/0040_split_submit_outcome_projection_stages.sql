-- Split submit outcome projection into freshness-critical and timeline stages.
--
-- The one-argument runtime.runtime_persist_submit_outcomes(jsonb) contract
-- remains full-fidelity. The two-argument form lets pressure runs project the
-- command-status/lifecycle inputs without also maintaining runtime_events and
-- trace sequence rows in the same batch.

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_status_stage(
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
    INSERT INTO runtime.order_lifecycle_dirty(order_id)
    SELECT order_id FROM dirty_ids
    ON CONFLICT (order_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome_timeline_stage(
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
  parsed_events AS (
    SELECT
      event,
      outcomes.outcome_ordinality,
      event_ordinality::BIGINT AS event_ordinality,
      CASE
        WHEN COALESCE(outcome->>'streamSequence', '') ~ '^[0-9]+$'
         AND (outcome->>'streamSequence')::NUMERIC BETWEEN 1 AND 92233720368547758
        THEN (outcome->>'streamSequence')::BIGINT
        ELSE NULL
      END AS stream_sequence
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  deterministic_events AS (
    SELECT *
    FROM parsed_events
    WHERE stream_sequence IS NOT NULL
  ),
  legacy_events AS (
    SELECT *
    FROM parsed_events
    WHERE stream_sequence IS NULL
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM legacy_events
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
      legacy.event,
      legacy.outcome_ordinality,
      legacy.event_ordinality,
      row_number() OVER (
        PARTITION BY legacy.event->>'traceId'
        ORDER BY legacy.outcome_ordinality, legacy.event_ordinality
      ) - 1 AS trace_offset
    FROM legacy_events legacy
  ),
  insert_deterministic_events AS (
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
      deterministic_events.stream_sequence * 100 + deterministic_events.event_ordinality,
      COALESCE(event->'payloadJson', '{}'::jsonb),
      event->>'occurredAt'
    FROM deterministic_events
    ORDER BY deterministic_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  ),
  insert_legacy_events AS (
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
    ORDER BY ordered_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
  p_outcomes JSONB,
  p_projection_stage TEXT
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  normalized_stage TEXT := LOWER(COALESCE(NULLIF(p_projection_stage, ''), 'full'));
  persisted_count BIGINT := 0;
BEGIN
  IF normalized_stage IN ('full', 'all') THEN
    persisted_count := runtime.runtime_persist_submit_outcome_status_stage(p_outcomes);
    PERFORM runtime.runtime_persist_submit_outcome_timeline_stage(p_outcomes);
    RETURN persisted_count;
  END IF;

  IF normalized_stage IN ('command-status', 'status', 'lifecycle', 'core') THEN
    RETURN runtime.runtime_persist_submit_outcome_status_stage(p_outcomes);
  END IF;

  IF normalized_stage IN ('timeline', 'event-timeline', 'events') THEN
    RETURN runtime.runtime_persist_submit_outcome_timeline_stage(p_outcomes);
  END IF;

  RAISE EXCEPTION 'unsupported submit outcome projection stage: %', p_projection_stage;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcomes(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
BEGIN
  RETURN runtime.runtime_persist_submit_outcomes(p_outcomes, 'full');
END;
$$;
