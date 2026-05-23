-- Orchestration schema for scheduler/job-runner state machine.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS orchestration.scheduled_jobs (
  job_name TEXT PRIMARY KEY,
  cron_expr TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true,
  max_attempts INT NOT NULL DEFAULT 10,
  retry_backoff_seconds INT NOT NULL DEFAULT 30,
  timeout_seconds INT NOT NULL DEFAULT 1800,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS orchestration.job_runs (
  run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  job_name TEXT NOT NULL REFERENCES orchestration.scheduled_jobs(job_name),
  business_key TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('queued', 'running', 'retry_wait', 'succeeded', 'failed', 'cancelled')),
  attempt_count INT NOT NULL DEFAULT 0,
  not_before TIMESTAMPTZ NOT NULL DEFAULT now(),
  started_at TIMESTAMPTZ,
  finished_at TIMESTAMPTZ,
  worker_id TEXT NOT NULL DEFAULT '',
  last_error TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (job_name, business_key)
);

CREATE INDEX IF NOT EXISTS job_runs_status_due_idx
  ON orchestration.job_runs (status, not_before, created_at);

CREATE TABLE IF NOT EXISTS orchestration.job_artifacts (
  run_id UUID NOT NULL REFERENCES orchestration.job_runs(run_id),
  artifact_type TEXT NOT NULL,
  artifact_uri TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  row_count BIGINT NOT NULL DEFAULT 0,
  metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (run_id, artifact_type, artifact_uri)
);

CREATE OR REPLACE FUNCTION orchestration.fn_enqueue_job_run_v1(
  p_job_name TEXT,
  p_business_key TEXT,
  p_not_before TIMESTAMPTZ DEFAULT now()
) RETURNS UUID
LANGUAGE plpgsql
AS $$
DECLARE
  v_run_id UUID;
BEGIN
  INSERT INTO orchestration.job_runs(
    job_name,
    business_key,
    status,
    not_before
  ) VALUES (
    p_job_name,
    p_business_key,
    'queued',
    COALESCE(p_not_before, now())
  )
  ON CONFLICT (job_name, business_key) DO UPDATE
  SET not_before = LEAST(orchestration.job_runs.not_before, EXCLUDED.not_before),
      updated_at = now()
  RETURNING run_id INTO v_run_id;

  RETURN v_run_id;
END;
$$;

CREATE OR REPLACE FUNCTION orchestration.fn_claim_due_run_v1(
  p_worker_id TEXT
) RETURNS orchestration.job_runs
LANGUAGE plpgsql
AS $$
DECLARE
  v_row orchestration.job_runs;
BEGIN
  WITH candidate AS (
    SELECT run_id
    FROM orchestration.job_runs
    WHERE status IN ('queued', 'retry_wait')
      AND not_before <= now()
    ORDER BY not_before, created_at
    FOR UPDATE SKIP LOCKED
    LIMIT 1
  )
  UPDATE orchestration.job_runs jr
  SET status = 'running',
      worker_id = COALESCE(p_worker_id, ''),
      started_at = COALESCE(jr.started_at, now()),
      attempt_count = jr.attempt_count + 1,
      updated_at = now()
  FROM candidate c
  WHERE jr.run_id = c.run_id
  RETURNING jr.* INTO v_row;

  RETURN v_row;
END;
$$;

CREATE OR REPLACE FUNCTION orchestration.fn_mark_run_success_v1(
  p_run_id UUID
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE orchestration.job_runs
  SET status = 'succeeded',
      finished_at = now(),
      updated_at = now(),
      last_error = ''
  WHERE run_id = p_run_id;
END;
$$;

CREATE OR REPLACE FUNCTION orchestration.fn_mark_run_retry_v1(
  p_run_id UUID,
  p_error TEXT,
  p_delay_seconds INT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE orchestration.job_runs
  SET status = 'retry_wait',
      not_before = now() + make_interval(secs => GREATEST(COALESCE(p_delay_seconds, 1), 1)),
      updated_at = now(),
      last_error = LEFT(COALESCE(p_error, ''), 4000)
  WHERE run_id = p_run_id;
END;
$$;

CREATE OR REPLACE FUNCTION orchestration.fn_mark_run_failed_v1(
  p_run_id UUID,
  p_error TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE orchestration.job_runs
  SET status = 'failed',
      finished_at = now(),
      updated_at = now(),
      last_error = LEFT(COALESCE(p_error, ''), 4000)
  WHERE run_id = p_run_id;
END;
$$;

CREATE OR REPLACE FUNCTION orchestration.fn_heartbeat_run_v1(
  p_run_id UUID,
  p_worker_id TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
  UPDATE orchestration.job_runs
  SET worker_id = COALESCE(p_worker_id, worker_id),
      updated_at = now()
  WHERE run_id = p_run_id
    AND status = 'running';
END;
$$;
