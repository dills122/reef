-- Preserve first persisted command outcomes and return real append counts.
--
-- Canonical facts must be immutable once written. Projection rows can still be
-- rebuilt elsewhere, but command-result audit rows should not be overwritten by
-- retries or duplicate projector runs.

CREATE OR REPLACE FUNCTION runtime.runtime_append_canonical_submit_outcomes(
  p_outcomes JSONB
)
RETURNS BIGINT
LANGUAGE plpgsql
AS $$
DECLARE
  appended_count BIGINT := 0;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'canonical submit outcomes payload must be a JSON array';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM jsonb_array_elements(p_outcomes) AS outcome
    JOIN runtime.canonical_command_results existing
      ON existing.command_id = outcome->>'commandId'
    WHERE existing.run_id IS DISTINCT FROM COALESCE(outcome->>'runId', '')
       OR existing.venue_session_id IS DISTINCT FROM COALESCE(outcome->>'venueSessionId', '')
       OR existing.partition_id IS DISTINCT FROM COALESCE((outcome->>'partitionId')::INTEGER, -1)
       OR existing.partition_seq IS DISTINCT FROM COALESCE((outcome->>'partitionSequence')::BIGINT, 0)
       OR existing.stream_name IS DISTINCT FROM COALESCE(outcome->>'streamName', '')
       OR existing.stream_seq IS DISTINCT FROM COALESCE((outcome->>'streamSequence')::BIGINT, 0)
       OR existing.idempotency_key IS DISTINCT FROM COALESCE(outcome->>'idempotencyKey', '')
       OR existing.payload_hash IS DISTINCT FROM COALESCE(outcome->>'payloadHash', '')
       OR existing.instrument_id IS DISTINCT FROM COALESCE(outcome->>'instrumentId', '')
       OR existing.command_type IS DISTINCT FROM COALESCE(outcome->>'commandType', '')
       OR existing.result_status IS DISTINCT FROM COALESCE(outcome->>'resultStatus', '')
       OR existing.reject_code IS DISTINCT FROM COALESCE(outcome->>'rejectCode', '')
       OR existing.accepted_at IS DISTINCT FROM COALESCE(outcome->>'acceptedAt', '')
       OR existing.completed_at IS DISTINCT FROM COALESCE(outcome->>'completedAt', '')
       OR existing.engine_shard_id IS DISTINCT FROM COALESCE(outcome->>'engineShardId', '')
       OR existing.result_payload IS DISTINCT FROM COALESCE(outcome->'resultPayload', '{}'::jsonb)
  ) THEN
    RAISE EXCEPTION 'canonical command result conflict for existing command_id';
  END IF;

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  insert_results AS (
    INSERT INTO runtime.canonical_command_results(
      command_id,
      run_id,
      venue_session_id,
      partition_id,
      partition_seq,
      stream_name,
      stream_seq,
      idempotency_key,
      payload_hash,
      instrument_id,
      command_type,
      result_status,
      reject_code,
      accepted_at,
      completed_at,
      engine_shard_id,
      result_payload
    )
    SELECT
      outcome->>'commandId',
      COALESCE(outcome->>'runId', ''),
      COALESCE(outcome->>'venueSessionId', ''),
      COALESCE((outcome->>'partitionId')::INTEGER, -1),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
      COALESCE(outcome->>'streamName', ''),
      COALESCE((outcome->>'streamSequence')::BIGINT, 0),
      COALESCE(outcome->>'idempotencyKey', ''),
      COALESCE(outcome->>'payloadHash', ''),
      COALESCE(outcome->>'instrumentId', ''),
      COALESCE(outcome->>'commandType', ''),
      COALESCE(outcome->>'resultStatus', ''),
      COALESCE(outcome->>'rejectCode', ''),
      COALESCE(outcome->>'acceptedAt', ''),
      COALESCE(outcome->>'completedAt', ''),
      COALESCE(outcome->>'engineShardId', ''),
      COALESCE(outcome->'resultPayload', '{}'::jsonb)
    FROM outcomes
    ON CONFLICT (command_id) DO NOTHING
    RETURNING 1
  ),
  parsed_events AS (
    SELECT
      outcome,
      event,
      event_ordinality::INTEGER AS deterministic_event_index
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN COALESCE(NULLIF(current_setting('reef.stream_ack_canonical_event_rows_enabled', TRUE), ''), 'true')::BOOLEAN
          AND jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
        ELSE '[]'::jsonb
      END
    ) WITH ORDINALITY AS event_rows(event, event_ordinality)
  ),
  insert_events AS (
    INSERT INTO runtime.canonical_venue_events(
      event_id,
      run_id,
      venue_session_id,
      partition_id,
      partition_seq,
      event_seq,
      command_id,
      event_type,
      aggregate_type,
      aggregate_id,
      instrument_id,
      deterministic_event_index,
      payload,
      emitted_at
    )
    SELECT
      event->>'eventId',
      COALESCE(outcome->>'runId', ''),
      COALESCE(outcome->>'venueSessionId', ''),
      COALESCE((outcome->>'partitionId')::INTEGER, -1),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0),
      COALESCE((outcome->>'partitionSequence')::BIGINT, 0) * 1000 + deterministic_event_index,
      outcome->>'commandId',
      COALESCE(event->>'eventType', ''),
      CASE
        WHEN event->>'eventType' = 'ExecutionCreated' THEN 'execution'
        WHEN event->>'eventType' = 'TradeCreated' THEN 'trade'
        ELSE 'order'
      END,
      COALESCE(event->>'orderId', event->>'eventId', ''),
      COALESCE(outcome->>'instrumentId', ''),
      deterministic_event_index,
      event,
      COALESCE(event->>'occurredAt', outcome->>'completedAt', '')
    FROM parsed_events
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO appended_count FROM insert_results;

  RETURN appended_count;
END;
$$;

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
    ON CONFLICT (order_id) DO UPDATE SET dirtied_at = now()
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
    ORDER BY ordered_events.event->>'eventId'
    ON CONFLICT (event_id) DO NOTHING
    RETURNING 1
  )
  SELECT COUNT(*) INTO persisted_count FROM outcomes;

  RETURN persisted_count;
END;
$$;

CREATE INDEX IF NOT EXISTS idx_order_lifecycle_state_book_numeric_price
  ON runtime.order_lifecycle_state(
    instrument_id,
    status,
    side,
    ((limit_price)::NUMERIC)
  )
  WHERE order_type = 'LIMIT'
    AND status IN ('OPEN', 'PARTIALLY_FILLED')
    AND limit_price ~ '^-?[0-9]+(\.[0-9]+)?$'
    AND remaining_quantity_units ~ '^[0-9]+(\.[0-9]+)?$';
