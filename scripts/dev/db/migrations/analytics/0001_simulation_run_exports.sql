CREATE SCHEMA IF NOT EXISTS analytics;

CREATE TABLE IF NOT EXISTS analytics.simulation_run_exports (
  run_id TEXT PRIMARY KEY,
  scenario_id TEXT NOT NULL DEFAULT '',
  run_kind TEXT NOT NULL DEFAULT '',
  source TEXT NOT NULL DEFAULT '',
  git_sha TEXT NOT NULL DEFAULT '',
  profile TEXT NOT NULL DEFAULT '',
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  exported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  status TEXT NOT NULL DEFAULT '',
  attempted_count BIGINT NOT NULL DEFAULT 0,
  accepted_count BIGINT NOT NULL DEFAULT 0,
  completed_count BIGINT NOT NULL DEFAULT 0,
  materialized_count BIGINT NOT NULL DEFAULT 0,
  projected_count BIGINT NOT NULL DEFAULT 0,
  failed_count BIGINT NOT NULL DEFAULT 0,
  p50_latency_ms DOUBLE PRECISION,
  p95_latency_ms DOUBLE PRECISION,
  p99_latency_ms DOUBLE PRECISION,
  artifact_manifest JSONB NOT NULL DEFAULT '[]'::jsonb,
  summary JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_analytics_run_exports_completed
ON analytics.simulation_run_exports(completed_at DESC, exported_at DESC);

CREATE INDEX IF NOT EXISTS idx_analytics_run_exports_scenario
ON analytics.simulation_run_exports(scenario_id, completed_at DESC);
