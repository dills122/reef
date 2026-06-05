-- Live runtime persistence tables used by PostgresRuntimePersistence.
-- This reconciles the current runtime write path with the migration-owned schema model.

CREATE SCHEMA IF NOT EXISTS runtime;

CREATE TABLE IF NOT EXISTS runtime.reference_instruments (
  instrument_id TEXT PRIMARY KEY,
  symbol TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.reference_participants (
  participant_id TEXT PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.reference_accounts (
  account_id TEXT PRIMARY KEY,
  participant_id TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.orders (
  order_id TEXT PRIMARY KEY,
  engine_order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  participant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  side TEXT NOT NULL,
  order_type TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  limit_price TEXT NOT NULL,
  currency TEXT NOT NULL,
  time_in_force TEXT NOT NULL,
  accepted_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.executions (
  event_id TEXT PRIMARY KEY,
  execution_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  execution_price TEXT NOT NULL,
  currency TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.trades (
  event_id TEXT PRIMARY KEY,
  trade_id TEXT NOT NULL,
  execution_id TEXT NOT NULL,
  buy_order_id TEXT NOT NULL,
  sell_order_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  quantity_units TEXT NOT NULL,
  price TEXT NOT NULL,
  currency TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS runtime.runtime_events (
  event_id TEXT PRIMARY KEY,
  event_type TEXT NOT NULL,
  order_id TEXT NOT NULL,
  trace_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  producer TEXT NOT NULL,
  schema_version TEXT NOT NULL,
  sequence_number BIGINT NOT NULL,
  occurred_at TEXT NOT NULL
);

ALTER TABLE runtime.runtime_events
  ALTER COLUMN event_id TYPE TEXT USING event_id::TEXT;

ALTER TABLE runtime.runtime_events
  ALTER COLUMN occurred_at TYPE TEXT USING occurred_at::TEXT;

ALTER TABLE runtime.runtime_events
  ALTER COLUMN schema_version DROP DEFAULT;

CREATE TABLE IF NOT EXISTS runtime.runtime_trace_sequences (
  trace_id TEXT PRIMARY KEY,
  next_sequence BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_runtime_events_trace_sequence
  ON runtime.runtime_events(trace_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_runtime_events_order_trace_sequence
  ON runtime.runtime_events(order_id, trace_id, sequence_number);

CREATE INDEX IF NOT EXISTS idx_runtime_events_occurred_event
  ON runtime.runtime_events(occurred_at DESC, event_id DESC);

CREATE INDEX IF NOT EXISTS idx_executions_order_occurred
  ON runtime.executions(order_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_trades_buy_order_occurred
  ON runtime.trades(buy_order_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_trades_sell_order_occurred
  ON runtime.trades(sell_order_id, occurred_at);

CREATE TABLE IF NOT EXISTS runtime.submit_results (
  command_id TEXT PRIMARY KEY,
  result_type TEXT NOT NULL,
  event_id TEXT NOT NULL,
  order_id TEXT NOT NULL,
  engine_order_id TEXT NOT NULL,
  code TEXT NOT NULL,
  reason TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);

CREATE OR REPLACE FUNCTION runtime.runtime_validate_reference_data(
  p_instrument_id TEXT,
  p_participant_id TEXT,
  p_account_id TEXT
)
RETURNS TABLE(
  instrument_exists BOOLEAN,
  participant_exists BOOLEAN,
  account_exists BOOLEAN,
  account_belongs_to_participant BOOLEAN
)
LANGUAGE SQL
STABLE
AS $$
  SELECT
    EXISTS(SELECT 1 FROM runtime.reference_instruments WHERE instrument_id = p_instrument_id),
    EXISTS(SELECT 1 FROM runtime.reference_participants WHERE participant_id = p_participant_id),
    EXISTS(SELECT 1 FROM runtime.reference_accounts WHERE account_id = p_account_id),
    EXISTS(SELECT 1 FROM runtime.reference_accounts WHERE account_id = p_account_id AND participant_id = p_participant_id)
$$;

CREATE OR REPLACE FUNCTION runtime.runtime_persist_submit_outcome(
  p_command_id TEXT,
  p_result_type TEXT,
  p_result_event_id TEXT,
  p_result_order_id TEXT,
  p_result_engine_order_id TEXT,
  p_result_code TEXT,
  p_result_reason TEXT,
  p_result_occurred_at TEXT,
  p_accepted_order JSONB,
  p_executions JSONB,
  p_trades JSONB,
  p_events JSONB
)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO runtime.submit_results(command_id, result_type, event_id, order_id, engine_order_id, code, reason, occurred_at)
  VALUES (
    p_command_id,
    p_result_type,
    p_result_event_id,
    p_result_order_id,
    p_result_engine_order_id,
    p_result_code,
    p_result_reason,
    p_result_occurred_at
  )
  ON CONFLICT (command_id) DO UPDATE SET
    result_type = EXCLUDED.result_type,
    event_id = EXCLUDED.event_id,
    order_id = EXCLUDED.order_id,
    engine_order_id = EXCLUDED.engine_order_id,
    code = EXCLUDED.code,
    reason = EXCLUDED.reason,
    occurred_at = EXCLUDED.occurred_at;

  IF p_accepted_order IS NOT NULL THEN
    INSERT INTO runtime.orders(order_id, engine_order_id, instrument_id, participant_id, account_id, side, order_type, quantity_units, limit_price, currency, time_in_force, accepted_at)
    VALUES (
      p_accepted_order->>'orderId',
      p_accepted_order->>'engineOrderId',
      p_accepted_order->>'instrumentId',
      p_accepted_order->>'participantId',
      p_accepted_order->>'accountId',
      p_accepted_order->>'side',
      p_accepted_order->>'orderType',
      p_accepted_order->>'quantityUnits',
      p_accepted_order->>'limitPrice',
      p_accepted_order->>'currency',
      p_accepted_order->>'timeInForce',
      p_accepted_order->>'acceptedAt'
    )
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
      accepted_at = EXCLUDED.accepted_at;
  END IF;

  IF p_executions IS NOT NULL AND jsonb_array_length(p_executions) > 0 THEN
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
    FROM jsonb_array_elements(p_executions) AS execution
    ON CONFLICT (event_id) DO NOTHING;
  END IF;

  IF p_trades IS NOT NULL AND jsonb_array_length(p_trades) > 0 THEN
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
    FROM jsonb_array_elements(p_trades) AS trade
    ON CONFLICT (event_id) DO NOTHING;
  END IF;

  IF p_events IS NULL OR jsonb_array_length(p_events) = 0 THEN
    RETURN;
  END IF;

  WITH parsed_events AS (
    SELECT event, ordinality
    FROM jsonb_array_elements(p_events) WITH ORDINALITY AS event_rows(event, ordinality)
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
      parsed.ordinality,
      row_number() OVER (
        PARTITION BY parsed.event->>'traceId'
        ORDER BY parsed.ordinality
      ) - 1 AS trace_offset
    FROM parsed_events parsed
  )
  INSERT INTO runtime.runtime_events(event_id, event_type, order_id, trace_id, causation_id, correlation_id, producer, schema_version, sequence_number, occurred_at)
  SELECT
    event->>'eventId',
    event->>'eventType',
    event->>'orderId',
    event->>'traceId',
    event->>'causationId',
    event->>'correlationId',
    event->>'producer',
    event->>'schemaVersion',
    trace_starts.start_sequence + ordered_events.trace_offset,
    event->>'occurredAt'
  FROM ordered_events
  JOIN trace_starts ON trace_starts.trace_id = ordered_events.event->>'traceId'
  ORDER BY ordered_events.ordinality
  ON CONFLICT (event_id) DO NOTHING;
END;
$$;
