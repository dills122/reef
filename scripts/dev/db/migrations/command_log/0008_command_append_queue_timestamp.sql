-- Keep stored-function append aligned with indexed active-queue claim order.

CREATE OR REPLACE FUNCTION command_log.command_append(
  p_command_id TEXT,
  p_client_id TEXT,
  p_route TEXT,
  p_idempotency_key TEXT,
  p_trace_id TEXT,
  p_correlation_id TEXT,
  p_actor_id TEXT,
  p_command_type TEXT,
  p_received_at TIMESTAMPTZ,
  p_payload_json JSONB
)
RETURNS TABLE (
  out_appended BOOLEAN,
  out_command_id TEXT,
  out_client_id TEXT,
  out_route TEXT,
  out_idempotency_key TEXT,
  out_trace_id TEXT,
  out_correlation_id TEXT,
  out_actor_id TEXT,
  out_command_type TEXT,
  out_received_at TIMESTAMPTZ,
  out_payload_json JSONB,
  out_status TEXT,
  out_attempt_count INTEGER,
  out_last_error TEXT,
  out_response_status INTEGER,
  out_response_payload_json JSONB
)
LANGUAGE plpgsql
AS $$
DECLARE
  v_inserted INTEGER := 0;
  v_command_id TEXT;
BEGIN
  INSERT INTO command_log.commands AS command_row(
    command_id,
    client_id,
    route,
    idempotency_key,
    trace_id,
    correlation_id,
    actor_id,
    command_type,
    received_at,
    payload_json,
    status,
    attempt_count,
    last_error
  )
  VALUES (
    p_command_id,
    p_client_id,
    p_route,
    p_idempotency_key,
    p_trace_id,
    p_correlation_id,
    p_actor_id,
    p_command_type,
    p_received_at,
    p_payload_json,
    'RECEIVED',
    0,
    ''
  )
  ON CONFLICT DO NOTHING;

  GET DIAGNOSTICS v_inserted = ROW_COUNT;

  IF v_inserted = 1 THEN
    INSERT INTO command_log.command_work_queue(command_id, status, attempt_count, last_error, updated_at)
    VALUES (p_command_id, 'RECEIVED', 0, '', p_received_at)
    ON CONFLICT (command_id) DO NOTHING;

    v_command_id := p_command_id;
  ELSE
    SELECT c.command_id
    INTO v_command_id
    FROM command_log.commands c
    WHERE c.client_id = p_client_id
      AND c.route = p_route
      AND c.idempotency_key = p_idempotency_key;

    IF v_command_id IS NULL THEN
      SELECT c.command_id
      INTO v_command_id
      FROM command_log.commands c
      WHERE c.command_id = p_command_id;
    END IF;
  END IF;

  RETURN QUERY
  SELECT
    v_inserted = 1 AS out_appended,
    c.command_id AS out_command_id,
    c.client_id AS out_client_id,
    c.route AS out_route,
    c.idempotency_key AS out_idempotency_key,
    c.trace_id AS out_trace_id,
    c.correlation_id AS out_correlation_id,
    c.actor_id AS out_actor_id,
    c.command_type AS out_command_type,
    c.received_at AS out_received_at,
    c.payload_json AS out_payload_json,
    COALESCE(q.status, r.status, c.status) AS out_status,
    COALESCE(q.attempt_count, r.attempt_count, c.attempt_count) AS out_attempt_count,
    COALESCE(q.last_error, r.last_error, c.last_error) AS out_last_error,
    COALESCE(r.response_status, c.response_status, 0) AS out_response_status,
    COALESCE(r.response_payload_json, c.response_payload_json, '{}'::jsonb) AS out_response_payload_json
  FROM command_log.commands c
  LEFT JOIN command_log.command_work_queue q ON q.command_id = c.command_id
  LEFT JOIN command_log.command_results r ON r.command_id = c.command_id
  WHERE c.command_id = v_command_id;
END;
$$;
