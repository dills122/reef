CREATE TABLE IF NOT EXISTS admin.oauth_states (
  state_hash TEXT PRIMARY KEY,
  provider TEXT NOT NULL,
  redirect_path TEXT NOT NULL DEFAULT '/',
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ,
  CHECK (length(state_hash) = 64),
  CHECK (provider IN ('github')),
  CHECK (redirect_path LIKE '/%' AND redirect_path NOT LIKE '//%'),
  CHECK (expires_at > created_at)
);

CREATE TABLE IF NOT EXISTS admin.sessions (
  session_hash TEXT PRIMARY KEY,
  reef_user_id TEXT NOT NULL REFERENCES admin.users(reef_user_id),
  auth_provider TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  CHECK (length(session_hash) = 64),
  CHECK (auth_provider IN ('github')),
  CHECK (expires_at > created_at)
);

CREATE TABLE IF NOT EXISTS admin.service_tokens (
  token_id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  token_family TEXT NOT NULL,
  subject_actor_id TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ,
  last_used_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  CHECK (length(token_hash) = 64),
  CHECK (token_family IN ('ci', 'sim', 'run-plane', 'admin'))
);

CREATE INDEX IF NOT EXISTS idx_admin_oauth_states_expiry ON admin.oauth_states(expires_at);
CREATE INDEX IF NOT EXISTS idx_admin_sessions_user ON admin.sessions(reef_user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_admin_service_tokens_family ON admin.service_tokens(token_family, expires_at);
