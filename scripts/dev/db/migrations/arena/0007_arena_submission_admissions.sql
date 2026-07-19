CREATE TABLE IF NOT EXISTS arena.submission_admissions (
  repository TEXT NOT NULL,
  pull_request_number BIGINT NOT NULL,
  bot_id TEXT NOT NULL,
  head_repository TEXT NOT NULL,
  head_owner_login TEXT NOT NULL,
  github_user_id BIGINT NOT NULL,
  github_login TEXT NOT NULL,
  head_sha TEXT NOT NULL,
  state TEXT NOT NULL,
  invitation_actor TEXT,
  invitation_reason TEXT NOT NULL DEFAULT '',
  invited_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (repository, pull_request_number),
  CHECK (pull_request_number > 0),
  CHECK (github_user_id > 0),
  CHECK (bot_id ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
  CHECK (state IN ('pending_invite_review', 'invite_approved'))
);

CREATE INDEX IF NOT EXISTS idx_arena_submission_admissions_pending
  ON arena.submission_admissions(state, updated_at DESC);
