ALTER TABLE runtime.runtime_events ADD COLUMN IF NOT EXISTS payload_sha256 BYTEA;
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

CREATE OR REPLACE FUNCTION runtime.runtime_reject_event_replay_conflict(p_event_id TEXT)
RETURNS TEXT
LANGUAGE plpgsql
AS $$
BEGIN
  RAISE EXCEPTION 'runtime event replay conflict for existing event_id %', p_event_id
    USING ERRCODE = '23505';
END;
$$;

-- Runtime order IDs are unique within run, including explicit legacy blank-run namespace.
-- Quiesce producers/projectors; execute transactionally. See docs/RUNTIME_ORDER_IDENTITY.md.
ALTER TABLE runtime.submit_results ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.executions ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.trades_archive ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.runtime_events_archive ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.trades ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.runtime_events ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
ALTER TABLE runtime.order_lifecycle_state ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';
CREATE TABLE IF NOT EXISTS runtime.order_lifecycle_dirty(order_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE IF NOT EXISTS runtime.market_data_snapshot_dirty(instrument_id TEXT PRIMARY KEY, dirtied_at TIMESTAMPTZ NOT NULL DEFAULT now());
ALTER TABLE runtime.order_lifecycle_dirty ADD COLUMN IF NOT EXISTS run_id TEXT NOT NULL DEFAULT '';

DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_constraint WHERE conrelid = 'runtime.orders'::regclass AND contype = 'p' AND array_length(conkey, 1) = 1
  ) THEN
-- Never infer historical fact ownership from runtime.orders: that row may already
-- have been overwritten. Preserve unproven legacy rows in blank-run namespace.
-- Canonical submit outcomes carry event-ID provenance and can repair known facts.
  IF to_regclass('runtime.canonical_command_results') IS NOT NULL THEN
    UPDATE runtime.submit_results result SET run_id = canonical.run_id
    FROM runtime.canonical_command_results canonical WHERE result.command_id = canonical.command_id AND result.run_id = '';
    WITH evidence AS (
      SELECT canonical.run_id, execution->>'eventId' AS event_id
      FROM runtime.canonical_command_results canonical
      CROSS JOIN LATERAL jsonb_array_elements(COALESCE(canonical.result_payload->'executions', '[]'::jsonb)) execution
    ), unique_evidence AS (
      SELECT event_id, min(run_id) AS run_id FROM evidence GROUP BY event_id HAVING count(DISTINCT run_id) = 1
    )
    UPDATE runtime.executions fact SET run_id = evidence.run_id FROM unique_evidence evidence WHERE fact.event_id = evidence.event_id AND fact.run_id = '';
    WITH evidence AS (
      SELECT canonical.run_id, trade->>'eventId' AS event_id
      FROM runtime.canonical_command_results canonical
      CROSS JOIN LATERAL jsonb_array_elements(COALESCE(canonical.result_payload->'trades', '[]'::jsonb)) trade
    ), unique_evidence AS (
      SELECT event_id, min(run_id) AS run_id FROM evidence GROUP BY event_id HAVING count(DISTINCT run_id) = 1
    )
    UPDATE runtime.trades fact SET run_id = evidence.run_id FROM unique_evidence evidence WHERE fact.event_id = evidence.event_id AND fact.run_id = '';
    WITH evidence AS (
      SELECT canonical.run_id, event->>'eventId' AS event_id
      FROM runtime.canonical_command_results canonical
      CROSS JOIN LATERAL jsonb_array_elements(COALESCE(canonical.result_payload->'events', '[]'::jsonb)) event
    ), unique_evidence AS (
      SELECT event_id, min(run_id) AS run_id FROM evidence GROUP BY event_id HAVING count(DISTINCT run_id) = 1
    )
    UPDATE runtime.runtime_events fact SET run_id = evidence.run_id FROM unique_evidence evidence WHERE fact.event_id = evidence.event_id AND fact.run_id = '';
  END IF;
-- Existing lifecycle rows are rebuildable; retain canonical facts unchanged.
UPDATE runtime.order_lifecycle_state lifecycle SET run_id = orders.run_id FROM runtime.orders orders WHERE lifecycle.order_id = orders.order_id AND lifecycle.run_id = '';
DELETE FROM runtime.order_lifecycle_dirty;
ALTER TABLE runtime.orders DROP CONSTRAINT IF EXISTS orders_pkey;
ALTER TABLE runtime.orders ADD PRIMARY KEY(run_id, order_id);
ALTER TABLE runtime.order_lifecycle_state DROP CONSTRAINT IF EXISTS order_lifecycle_state_pkey;
ALTER TABLE runtime.order_lifecycle_state ADD PRIMARY KEY(run_id, order_id);
ALTER TABLE runtime.order_lifecycle_dirty DROP CONSTRAINT IF EXISTS order_lifecycle_dirty_pkey;
ALTER TABLE runtime.order_lifecycle_dirty ADD PRIMARY KEY(run_id, order_id);
-- Recover overwritten acceptances only from immutable canonical snapshots.
INSERT INTO runtime.orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at, client_order_id, run_id, venue_session_id)
SELECT COALESCE(accepted_order->>'orderId', ''),
       COALESCE(accepted_order->>'engineOrderId', ''),
       COALESCE(accepted_order->>'instrumentId', ''),
       COALESCE(accepted_order->>'participantId', ''),
       COALESCE(accepted_order->>'accountId', ''),
       COALESCE(accepted_order->>'side', ''),
       COALESCE(accepted_order->>'orderType', ''),
       COALESCE(accepted_order->>'quantityUnits', ''),
       COALESCE(accepted_order->>'limitPrice', ''),
       COALESCE(accepted_order->>'currency', ''),
       COALESCE(accepted_order->>'timeInForce', ''),
       COALESCE(accepted_order->>'acceptedAt', ''),
       COALESCE(accepted_order->>'clientOrderId', ''),
       canonical.run_id,
       COALESCE(accepted_order->>'venueSessionId', '')
FROM runtime.canonical_command_results canonical
CROSS JOIN LATERAL (SELECT canonical.result_payload->'acceptedOrder' AS accepted_order) snapshot
WHERE jsonb_typeof(accepted_order) = 'object'
  AND COALESCE(accepted_order->>'orderId', '') <> ''
  AND COALESCE(accepted_order->>'participantId', '') <> ''
  AND COALESCE(accepted_order->>'accountId', '') <> ''
ORDER BY canonical.run_id, accepted_order->>'orderId'
ON CONFLICT (run_id, order_id) DO NOTHING;
INSERT INTO runtime.order_lifecycle_dirty(run_id, order_id) SELECT run_id, order_id FROM runtime.orders;
  END IF;
END;
$$;
CREATE INDEX IF NOT EXISTS idx_executions_run_order ON runtime.executions(run_id, order_id);
CREATE INDEX IF NOT EXISTS idx_runtime_events_run_order ON runtime.runtime_events(run_id, order_id, occurred_at);
CREATE INDEX IF NOT EXISTS idx_trades_run_buy_order ON runtime.trades(run_id, buy_order_id);
CREATE INDEX IF NOT EXISTS idx_trades_run_sell_order ON runtime.trades(run_id, sell_order_id);

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
    SELECT trade, COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '') AS run_id
    FROM outcomes
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE
        WHEN jsonb_typeof(outcome->'trades') = 'array' THEN outcome->'trades'
        ELSE '[]'::jsonb
      END
    ) AS trade
  ),
  upsert_results AS (
    INSERT INTO runtime.submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at, run_id)
    SELECT
      outcome->>'commandId',
      outcome->>'resultType',
      outcome->>'eventId',
      outcome->>'orderId',
      outcome->>'engineOrderId',
      outcome->>'code',
      outcome->>'reason',
      outcome->>'occurredAt',
      COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '')
    FROM outcomes
    ON CONFLICT (command_id) DO UPDATE SET
      command_id = runtime.runtime_reject_submit_result_replay_conflict(EXCLUDED.command_id)
    WHERE ROW(
      runtime.submit_results.run_id,
      runtime.submit_results.result_type,
      runtime.submit_results.event_id,
      runtime.submit_results.order_id,
      runtime.submit_results.engine_order_id,
      runtime.submit_results.code,
      runtime.submit_results.reason,
      runtime.submit_results.occurred_at
    ) IS DISTINCT FROM ROW(
      EXCLUDED.run_id,
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
    ON CONFLICT (run_id, order_id) DO UPDATE SET
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
    INSERT INTO runtime.executions(event_id, execution_id, order_id, instrument_id, quantity_units, execution_price, currency, occurred_at, liquidity_role, run_id)
    SELECT
      execution->>'eventId',
      execution->>'executionId',
      execution->>'orderId',
      execution->>'instrumentId',
      execution->>'quantityUnits',
      execution->>'executionPrice',
      execution->>'currency',
      execution->>'occurredAt',
      COALESCE(NULLIF(execution->>'liquidityRole', ''), 'UNSPECIFIED'),
      COALESCE(NULLIF(execution->>'runId', ''), outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '')
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
      runtime.executions.run_id,
      runtime.executions.occurred_at,
      runtime.executions.liquidity_role
    ) IS DISTINCT FROM ROW(
      EXCLUDED.execution_id,
      EXCLUDED.order_id,
      EXCLUDED.instrument_id,
      EXCLUDED.quantity_units,
      EXCLUDED.execution_price,
      EXCLUDED.currency,
      EXCLUDED.run_id,
      EXCLUDED.occurred_at,
      EXCLUDED.liquidity_role
    )
    RETURNING 1
  ),
  insert_trades AS (
    INSERT INTO runtime.trades(event_id, trade_id, execution_id, buy_order_id, sell_order_id, instrument_id, quantity_units, price, currency, occurred_at, run_id)
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
      trade->>'occurredAt',
      COALESCE(NULLIF(trade->>'runId', ''), run_id)
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
      runtime.trades.run_id,
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
      EXCLUDED.run_id,
      EXCLUDED.occurred_at
    )
    RETURNING 1
  ),
  dirty_ids AS (
    SELECT DISTINCT run_id, order_id FROM (
      SELECT COALESCE(outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '') AS run_id, outcome->>'orderId' AS order_id FROM outcomes
      UNION ALL
      SELECT COALESCE(NULLIF(trade->>'runId', ''), run_id), trade_order.order_id
      FROM parsed_trades
      CROSS JOIN LATERAL (VALUES (trade->>'buyOrderId'), (trade->>'sellOrderId')) AS trade_order(order_id)
    ) ids
    WHERE COALESCE(order_id, '') <> ''
  ),
  mark_dirty AS (
    INSERT INTO runtime.order_lifecycle_dirty AS dirty(run_id, order_id)
    SELECT run_id, order_id FROM dirty_ids
    ORDER BY run_id, order_id
    ON CONFLICT (run_id, order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at WHERE FALSE
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
  v_trace TEXT;
BEGIN
  IF p_outcomes IS NULL THEN
    RETURN 0;
  END IF;

  IF jsonb_typeof(p_outcomes) <> 'array' THEN
    RAISE EXCEPTION 'runtime submit outcomes payload must be a JSON array';
  END IF;

  -- Legacy sequences are allocated only for IDs absent after same-trace writers
  -- finish. Acquire locks in one order to avoid deadlocks between batches.
  FOR v_trace IN
    SELECT DISTINCT event->>'traceId'
    FROM jsonb_array_elements(p_outcomes) AS outcome_rows(outcome)
    CROSS JOIN LATERAL jsonb_array_elements(
      CASE WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events' ELSE '[]'::jsonb END
    ) AS event_rows(event)
    WHERE CASE
      WHEN COALESCE(outcome->>'streamSequence', '') ~ '^[0-9]+$'
       AND (outcome->>'streamSequence')::NUMERIC BETWEEN 1 AND 92233720368547758
      THEN false ELSE true
    END
    ORDER BY 1
  LOOP
    PERFORM pg_advisory_xact_lock(198765432, hashtext(COALESCE(v_trace, '')));
  END LOOP;

  WITH outcomes AS (
    SELECT outcome, ordinality::BIGINT AS outcome_ordinality
    FROM jsonb_array_elements(p_outcomes) WITH ORDINALITY AS outcome_rows(outcome, ordinality)
  ),
  parsed_events AS (
    SELECT
      event || jsonb_build_object('runId', COALESCE(NULLIF(event->>'runId', ''), outcome->>'runId', outcome#>>'{acceptedOrder,runId}', '')) AS event,
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
  existing_legacy_events AS (
    SELECT legacy.event, legacy.outcome_ordinality, legacy.event_ordinality, stored.sequence_number
    FROM legacy_events legacy
    JOIN runtime.runtime_events stored ON stored.event_id = legacy.event->>'eventId'
  ),
  new_legacy_events AS (
    SELECT legacy.*
    FROM legacy_events legacy
    WHERE NOT EXISTS (
      SELECT 1 FROM runtime.runtime_events stored WHERE stored.event_id = legacy.event->>'eventId'
    )
  ),
  trace_counts AS (
    SELECT event->>'traceId' AS trace_id, COUNT(*)::BIGINT AS event_count
    FROM new_legacy_events
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
  ordered_legacy_events AS (
    SELECT
      legacy.event,
      legacy.outcome_ordinality,
      legacy.event_ordinality,
      trace_starts.start_sequence + row_number() OVER (
        PARTITION BY legacy.event->>'traceId'
        ORDER BY legacy.outcome_ordinality, legacy.event_ordinality
      ) - 1 AS sequence_number
    FROM new_legacy_events legacy
    JOIN trace_starts ON trace_starts.trace_id = legacy.event->>'traceId'
  ),
  all_events AS (
    SELECT
      event,
      outcome_ordinality,
      event_ordinality,
      stream_sequence * 100 + event_ordinality AS sequence_number
    FROM deterministic_events
    UNION ALL
    SELECT
      event,
      outcome_ordinality,
      event_ordinality,
      sequence_number
    FROM ordered_legacy_events
    UNION ALL
    SELECT event, outcome_ordinality, event_ordinality, sequence_number
    FROM existing_legacy_events
  ),
  insert_events AS (
    INSERT INTO runtime.runtime_events AS stored(
      event_id,
      run_id,
      event_type,
      order_id,
      trace_id,
      causation_id,
      correlation_id,
      actor_id,
      producer,
      schema_version,
      sequence_number,
      payload_json,
      occurred_at,
      modify_quantity_units,
      modify_limit_price,
      payload_sha256
    )
    SELECT
      event->>'eventId',
      COALESCE(NULLIF(event->>'runId', ''), ''),
      event->>'eventType',
      event->>'orderId',
      event->>'traceId',
      event->>'causationId',
      event->>'correlationId',
      COALESCE(event->>'actorId', ''),
      event->>'producer',
      event->>'schemaVersion',
      all_events.sequence_number,
      '{}'::jsonb,
      event->>'occurredAt',
      CASE
        WHEN event->>'eventType' = 'OrderModified' THEN COALESCE(NULLIF(event->'payloadJson'->>'quantityUnits', ''), '')
        ELSE ''
      END,
      CASE
        WHEN event->>'eventType' = 'OrderModified' THEN COALESCE(NULLIF(event->'payloadJson'->>'limitPrice', ''), '')
        ELSE ''
      END,
      sha256(COALESCE(event->'payloadJson', '{}'::jsonb)::text::bytea)
    FROM all_events
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO UPDATE
      SET event_id = runtime.runtime_reject_event_replay_conflict(EXCLUDED.event_id)
      WHERE ROW(
        stored.run_id, stored.event_type, stored.order_id, stored.trace_id, stored.causation_id,
        stored.correlation_id, stored.actor_id, stored.producer, stored.schema_version,
        stored.sequence_number, stored.occurred_at, stored.modify_quantity_units,
        stored.modify_limit_price,
        COALESCE(stored.payload_sha256, sha256(COALESCE(
          (SELECT payload_json FROM runtime.runtime_event_payloads WHERE event_id = stored.event_id),
          stored.payload_json
        )::text::bytea))
      ) IS DISTINCT FROM ROW(
        EXCLUDED.run_id, EXCLUDED.event_type, EXCLUDED.order_id, EXCLUDED.trace_id, EXCLUDED.causation_id,
        EXCLUDED.correlation_id, EXCLUDED.actor_id, EXCLUDED.producer, EXCLUDED.schema_version,
        EXCLUDED.sequence_number, EXCLUDED.occurred_at, EXCLUDED.modify_quantity_units,
        EXCLUDED.modify_limit_price, EXCLUDED.payload_sha256
      )
    RETURNING event_id
  ),
  insert_payloads AS (
    INSERT INTO runtime.runtime_event_payloads AS stored(event_id, payload_json)
    SELECT
      event->>'eventId',
      COALESCE(event->'payloadJson', '{}'::jsonb)
    FROM all_events
    WHERE COALESCE(event->'payloadJson', '{}'::jsonb) <> '{}'::jsonb
    ORDER BY all_events.event->>'eventId'
    ON CONFLICT (event_id) DO UPDATE
      SET event_id = runtime.runtime_reject_event_replay_conflict(EXCLUDED.event_id)
      WHERE stored.payload_json IS DISTINCT FROM EXCLUDED.payload_json
    RETURNING 1
  ),
  mark_dirty AS (
    INSERT INTO runtime.order_lifecycle_dirty AS dirty(run_id, order_id)
    SELECT DISTINCT COALESCE(NULLIF(event->>'runId', ''), ''), event->>'orderId' FROM all_events
    WHERE COALESCE(event->>'orderId', '') <> ''
    ORDER BY 1, 2
    ON CONFLICT (run_id, order_id) DO UPDATE SET dirtied_at = dirty.dirtied_at WHERE FALSE
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
  claimed_ids JSONB;
BEGIN
  IF p_batch_size IS NULL OR p_batch_size <= 0 THEN
    RETURN 0;
  END IF;

  effective_batch_size := LEAST(p_batch_size, 5000);

  -- Claim locks first. The next SQL statement receives a fresh READ COMMITTED
  -- snapshot in this VOLATILE function, including source facts committed before
  -- these locks were acquired.
  SELECT jsonb_agg(jsonb_build_object('run_id', claimed.run_id, 'order_id', claimed.order_id)) INTO claimed_ids
  FROM (
    SELECT run_id, order_id
    FROM runtime.order_lifecycle_dirty
    ORDER BY dirtied_at, run_id, order_id
    LIMIT effective_batch_size
    FOR UPDATE SKIP LOCKED
  ) claimed;

  IF claimed_ids IS NULL THEN
    RETURN 0;
  END IF;

  WITH selected_dirty AS (
    SELECT run_id, order_id FROM jsonb_to_recordset(claimed_ids) AS identity(run_id TEXT, order_id TEXT)
  ),
  execution_totals AS (
    SELECT
      run_id, order_id,
      SUM(quantity_units::NUMERIC) AS filled_quantity_units
    FROM runtime.executions
    WHERE (run_id, order_id) IN (SELECT run_id, order_id FROM selected_dirty)
      AND quantity_units ~ '^[0-9]+(\.[0-9]+)?$'
    GROUP BY run_id, order_id
  ),
  latest_modify AS (
    SELECT DISTINCT ON (run_id, order_id)
      run_id, order_id,
      COALESCE(NULLIF(modify_quantity_units, ''), '') AS modified_quantity_units,
      COALESCE(NULLIF(modify_limit_price, ''), '') AS modified_limit_price,
      occurred_at
    FROM runtime.runtime_events
    WHERE event_type = 'OrderModified'
      AND (run_id, order_id) IN (SELECT run_id, order_id FROM selected_dirty)
    ORDER BY run_id, order_id,
      occurred_at_ts DESC NULLS LAST,
      occurred_at DESC,
      sequence_number DESC,
      event_id_uuid DESC NULLS LAST,
      event_id DESC
  ),
  order_event_state AS (
    SELECT
      run_id, order_id,
      BOOL_OR(event_type = 'OrderCancelled') AS cancelled,
      BOOL_OR(event_type = 'OrderRejected') AS rejected,
      COALESCE(MAX(NULLIF(occurred_at, '')), '') AS last_event_at
    FROM runtime.runtime_events
    WHERE (run_id, order_id) IN (SELECT run_id, order_id FROM selected_dirty)
    GROUP BY run_id, order_id
  ),
  shaped AS (
    SELECT
      orders.run_id, orders.order_id,
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
    JOIN selected_dirty ON selected_dirty.run_id = orders.run_id AND selected_dirty.order_id = orders.order_id
    LEFT JOIN execution_totals ON execution_totals.run_id = orders.run_id AND execution_totals.order_id = orders.order_id
    LEFT JOIN latest_modify ON latest_modify.run_id = orders.run_id AND latest_modify.order_id = orders.order_id
    LEFT JOIN order_event_state ON order_event_state.run_id = orders.run_id AND order_event_state.order_id = orders.order_id
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
      run_id, order_id,
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
      run_id, order_id,
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
    ON CONFLICT (run_id, order_id) DO UPDATE SET
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
    ON CONFLICT (instrument_id) DO UPDATE SET dirtied_at = dirty.dirtied_at WHERE FALSE
  ),
  cleared AS (
    DELETE FROM runtime.order_lifecycle_dirty dirty
    USING selected_dirty
    WHERE dirty.run_id = selected_dirty.run_id AND dirty.order_id = selected_dirty.order_id
    RETURNING dirty.order_id
  )
  SELECT COUNT(*) INTO projected_count FROM cleared;

  RETURN projected_count;
END;
$$;

ALTER FUNCTION runtime.runtime_project_order_lifecycle_state(INTEGER) SET plan_cache_mode = force_custom_plan;

-- Canonical command projectors shape runtime facts from captured command scope.
-- Keep claim, prefix, replay and watermark behavior of installed functions.
DO $$
DECLARE
  target RECORD;
  definition TEXT;
BEGIN
  FOR target IN
    SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'runtime' AND p.proname IN (
      'runtime_project_canonical_command_outcome_members', 'runtime_project_canonical_command_outcomes'
    )
  LOOP
    definition := pg_get_functiondef(target.oid);
    IF position('AS order_payload' IN definition) > 0 AND position('''runId'', COALESCE(NULLIF(order_payload' IN definition) = 0 THEN
      definition := replace(definition, '''commandId'', command_id,', '''commandId'', command_id, ''runId'', COALESCE(NULLIF(order_payload->>''runId'', ''''), command_payload->>''runId'', ''''),');
      definition := replace(definition, '''instrumentId'', COALESCE(order_payload->>''instrumentId'', ''''),', '''runId'', COALESCE(NULLIF(order_payload->>''runId'', ''''), command_payload->>''runId'', ''''), ''venueSessionId'', COALESCE(order_payload->>''venueSessionId'', command_payload->>''venueSessionId'', ''''), ''clientOrderId'', COALESCE(order_payload->>''clientOrderId'', ''''), ''instrumentId'', COALESCE(order_payload->>''instrumentId'', ''''),');
      EXECUTE definition;
    END IF;
  END LOOP;
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
