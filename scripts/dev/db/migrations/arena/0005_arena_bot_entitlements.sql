CREATE TABLE IF NOT EXISTS arena.user_bot_limits (
  reef_user_id TEXT PRIMARY KEY,
  max_bots INTEGER NOT NULL CHECK (max_bots >= 0),
  max_active_bots INTEGER NOT NULL CHECK (max_active_bots >= 0 AND max_active_bots <= max_bots),
  max_version_submissions_per_day INTEGER NOT NULL CHECK (max_version_submissions_per_day >= 0),
  updated_by TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS arena.user_bot_ownerships (
  reef_user_id TEXT NOT NULL,
  bot_id TEXT NOT NULL,
  ownership_state TEXT NOT NULL DEFAULT 'owner' CHECK (ownership_state IN ('owner', 'maintainer', 'revoked')),
  assigned_by TEXT NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (reef_user_id, bot_id)
);

CREATE INDEX IF NOT EXISTS idx_arena_bot_ownerships_bot
  ON arena.user_bot_ownerships(bot_id);
