CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE IF NOT EXISTS admin.users (
  reef_user_id TEXT PRIMARY KEY,
  github_user_id BIGINT NOT NULL UNIQUE,
  github_login TEXT NOT NULL,
  display_name TEXT NOT NULL DEFAULT '',
  trust_state TEXT NOT NULL DEFAULT 'new',
  created_at TIMESTAMPTZ NOT NULL,
  last_seen_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (github_user_id > 0),
  CHECK (github_login ~ '^[A-Za-z0-9]([A-Za-z0-9-]{0,37}[A-Za-z0-9])?$'),
  CHECK (trust_state IN ('new', 'trusted', 'limited', 'banned'))
);

CREATE TABLE IF NOT EXISTS admin.roles (
  role_id TEXT PRIMARY KEY,
  description TEXT NOT NULL DEFAULT '',
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS admin.user_roles (
  reef_user_id TEXT NOT NULL REFERENCES admin.users(reef_user_id),
  role_id TEXT NOT NULL REFERENCES admin.roles(role_id),
  assigned_by TEXT NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reef_user_id, role_id)
);

CREATE TABLE IF NOT EXISTS admin.user_bot_limits (
  reef_user_id TEXT PRIMARY KEY REFERENCES admin.users(reef_user_id),
  max_bots INTEGER NOT NULL,
  max_active_bots INTEGER NOT NULL,
  max_version_submissions_per_day INTEGER NOT NULL,
  updated_by TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (max_bots >= 0),
  CHECK (max_active_bots >= 0),
  CHECK (max_active_bots <= max_bots),
  CHECK (max_version_submissions_per_day >= 0)
);

CREATE TABLE IF NOT EXISTS admin.user_bot_ownerships (
  reef_user_id TEXT NOT NULL REFERENCES admin.users(reef_user_id),
  bot_id TEXT NOT NULL,
  ownership_state TEXT NOT NULL DEFAULT 'owner',
  assigned_by TEXT NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reef_user_id, bot_id),
  CHECK (bot_id ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
  CHECK (ownership_state IN ('owner', 'maintainer', 'revoked'))
);

CREATE TABLE IF NOT EXISTS admin.audit_events (
  event_id TEXT PRIMARY KEY,
  actor_id TEXT NOT NULL,
  event_type TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  detail TEXT NOT NULL DEFAULT '',
  occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_users_github_login ON admin.users(github_login);
CREATE INDEX IF NOT EXISTS idx_admin_user_roles_role ON admin.user_roles(role_id);
CREATE INDEX IF NOT EXISTS idx_admin_bot_ownerships_bot ON admin.user_bot_ownerships(bot_id);
CREATE INDEX IF NOT EXISTS idx_admin_audit_target ON admin.audit_events(target_type, target_id, occurred_at DESC);
