CREATE SCHEMA IF NOT EXISTS arena;

CREATE TABLE IF NOT EXISTS arena.bots (
  bot_id TEXT PRIMARY KEY,
  file_name TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  publisher TEXT NOT NULL,
  email TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  version TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS arena.bot_versions (
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  source_hash TEXT NOT NULL,
  artifact_hash TEXT NOT NULL,
  sdk_version TEXT NOT NULL,
  api_version TEXT NOT NULL,
  dependency_manifest_hash TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (bot_id, version_id),
  FOREIGN KEY (bot_id) REFERENCES arena.bots(bot_id)
);

CREATE TABLE IF NOT EXISTS arena.qualification_reports (
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  report_id TEXT NOT NULL PRIMARY KEY,
  status TEXT NOT NULL,
  policy_version TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE TABLE IF NOT EXISTS arena.qualification_report_issues (
  report_id TEXT NOT NULL,
  issue_order INTEGER NOT NULL,
  issue TEXT NOT NULL,
  PRIMARY KEY (report_id, issue_order),
  FOREIGN KEY (report_id) REFERENCES arena.qualification_reports(report_id)
);

CREATE TABLE IF NOT EXISTS arena.operator_decisions (
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  decision_order BIGSERIAL PRIMARY KEY,
  from_status TEXT NOT NULL,
  to_status TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  reason TEXT NOT NULL,
  correlation_id TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE TABLE IF NOT EXISTS arena.run_records (
  run_id TEXT PRIMARY KEY,
  mode_id TEXT NOT NULL,
  scenario_id TEXT NOT NULL,
  seed BIGINT NOT NULL,
  policy_version TEXT NOT NULL,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS arena.run_bot_versions (
  run_id TEXT NOT NULL,
  bot_order INTEGER NOT NULL,
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  PRIMARY KEY (run_id, bot_order),
  FOREIGN KEY (run_id) REFERENCES arena.run_records(run_id),
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE TABLE IF NOT EXISTS arena.runtime_config_descriptors (
  bot_id TEXT NOT NULL,
  version_id TEXT NOT NULL,
  config_key TEXT NOT NULL,
  provider TEXT NOT NULL,
  secret_path TEXT NOT NULL,
  required BOOLEAN NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (bot_id, version_id, config_key),
  FOREIGN KEY (bot_id, version_id) REFERENCES arena.bot_versions(bot_id, version_id)
);

CREATE INDEX IF NOT EXISTS idx_arena_bot_versions_status ON arena.bot_versions(status);
CREATE INDEX IF NOT EXISTS idx_arena_runs_status_created ON arena.run_records(status, created_at DESC);
