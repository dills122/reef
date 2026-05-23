-- Runtime event backbone tables and procedure-first routine contracts.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS runtime.runtime_events (
  event_id UUID PRIMARY KEY,
  event_type TEXT NOT NULL,
  order_id TEXT NOT NULL,
  trace_id TEXT NOT NULL,
  causation_id TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  actor_id TEXT NOT NULL DEFAULT '',
  producer TEXT NOT NULL,
  schema_version TEXT NOT NULL DEFAULT 'v1',
  sequence_number BIGINT NOT NULL,
  payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  occurred_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS runtime_events_occurred_at_idx
  ON runtime.runtime_events (occurred_at);

CREATE INDEX IF NOT EXISTS runtime_events_trace_seq_idx
  ON runtime.runtime_events (trace_id, sequence_number);

CREATE INDEX IF NOT EXISTS runtime_events_order_occurred_idx
  ON runtime.runtime_events (order_id, occurred_at);

CREATE TABLE IF NOT EXISTS runtime.event_outbox (
  outbox_id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  stream TEXT NOT NULL,
  subject TEXT NOT NULL,
  payload_json JSONB NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending', 'published', 'retry_wait', 'dead_letter')),
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error TEXT NOT NULL DEFAULT '',
  worker_id TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS event_outbox_status_due_idx
  ON runtime.event_outbox (status, next_attempt_at, outbox_id);

CREATE OR REPLACE FUNCTION runtime.fn_append_event_and_outbox_v1(
  p_event_id UUID,
  p_event_type TEXT,
  p_order_id TEXT,
  p_trace_id TEXT,
  p_causation_id TEXT,
  p_correlation_id TEXT,
  p_actor_id TEXT,
  p_producer TEXT,
  p_schema_version TEXT,
  p_sequence_number BIGINT,
  p_payload_json JSONB,
  p_occurred_at TIMESTAMPTZ,
  p_stream TEXT,
  p_subject TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  INSERT INTO runtime.runtime_events(
    event_id,
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
    occurred_at
  ) VALUES (
    p_event_id,
    p_event_type,
    p_order_id,
    p_trace_id,
    p_causation_id,
    p_correlation_id,
    COALESCE(p_actor_id, ''),
    p_producer,
    COALESCE(NULLIF(p_schema_version, ''), 'v1'),
    p_sequence_number,
    COALESCE(p_payload_json, '{}'::jsonb),
    p_occurred_at
  );

  INSERT INTO runtime.event_outbox(
    event_id,
    stream,
    subject,
    payload_json,
    status
  ) VALUES (
    p_event_id,
    p_stream,
    p_subject,
    COALESCE(p_payload_json, '{}'::jsonb),
    'pending'
  );
END;
$$;

CREATE OR REPLACE FUNCTION runtime.fn_outbox_claim_batch_v1(
  p_worker_id TEXT,
  p_batch_size INT
) RETURNS SETOF runtime.event_outbox
LANGUAGE plpgsql
AS $$
BEGIN
  RETURN QUERY
  WITH candidates AS (
    SELECT outbox_id
    FROM runtime.event_outbox
    WHERE status IN ('pending', 'retry_wait')
      AND next_attempt_at <= now()
    ORDER BY next_attempt_at, outbox_id
    FOR UPDATE SKIP LOCKED
    LIMIT GREATEST(p_batch_size, 1)
  ),
  updated AS (
    UPDATE runtime.event_outbox e
    SET worker_id = COALESCE(p_worker_id, ''),
        attempt_count = e.attempt_count + 1
    FROM candidates c
    WHERE e.outbox_id = c.outbox_id
    RETURNING e.*
  )
  SELECT * FROM updated;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.fn_outbox_mark_published_v1(
  p_outbox_ids BIGINT[]
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  IF p_outbox_ids IS NULL OR array_length(p_outbox_ids, 1) IS NULL THEN
    RETURN;
  END IF;

  UPDATE runtime.event_outbox
  SET status = 'published',
      published_at = now(),
      next_attempt_at = now(),
      last_error = ''
  WHERE outbox_id = ANY(p_outbox_ids);
END;
$$;

CREATE OR REPLACE FUNCTION runtime.fn_outbox_mark_retry_v1(
  p_outbox_id BIGINT,
  p_error TEXT,
  p_delay_seconds INT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE runtime.event_outbox
  SET status = 'retry_wait',
      next_attempt_at = now() + make_interval(secs => GREATEST(COALESCE(p_delay_seconds, 1), 1)),
      last_error = LEFT(COALESCE(p_error, ''), 4000)
  WHERE outbox_id = p_outbox_id;
END;
$$;

CREATE OR REPLACE FUNCTION runtime.fn_outbox_mark_dead_letter_v1(
  p_outbox_id BIGINT,
  p_error TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE runtime.event_outbox
  SET status = 'dead_letter',
      next_attempt_at = now(),
      last_error = LEFT(COALESCE(p_error, ''), 4000)
  WHERE outbox_id = p_outbox_id;
END;
$$;
