CREATE TABLE IF NOT EXISTS runtime.canonical_command_results (
  command_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  partition_seq BIGINT NOT NULL,
  stream_name TEXT NOT NULL,
  stream_seq BIGINT NOT NULL,
  idempotency_key TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  command_type TEXT NOT NULL,
  result_status TEXT NOT NULL,
  reject_code TEXT NOT NULL,
  accepted_at TEXT NOT NULL,
  completed_at TEXT NOT NULL,
  engine_shard_id TEXT NOT NULL,
  result_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (stream_name, stream_seq)
);

CREATE INDEX IF NOT EXISTS idx_canonical_command_results_partition_seq
  ON runtime.canonical_command_results(partition_id, partition_seq);

CREATE INDEX IF NOT EXISTS idx_canonical_command_results_run_session
  ON runtime.canonical_command_results(run_id, venue_session_id, partition_id, partition_seq);

CREATE TABLE IF NOT EXISTS runtime.canonical_venue_events (
  event_id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL,
  venue_session_id TEXT NOT NULL,
  partition_id INTEGER NOT NULL,
  partition_seq BIGINT NOT NULL,
  event_seq BIGINT NOT NULL,
  command_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  instrument_id TEXT NOT NULL,
  deterministic_event_index INTEGER NOT NULL,
  payload JSONB NOT NULL DEFAULT '{}'::jsonb,
  emitted_at TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_partition_seq
  ON runtime.canonical_venue_events(partition_id, event_seq);

CREATE INDEX IF NOT EXISTS idx_canonical_venue_events_run_session
  ON runtime.canonical_venue_events(run_id, venue_session_id, partition_id, event_seq);

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
        WHEN jsonb_typeof(outcome->'events') = 'array' THEN outcome->'events'
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
  SELECT COUNT(*) INTO appended_count FROM outcomes;

  RETURN appended_count;
END;
$$;
