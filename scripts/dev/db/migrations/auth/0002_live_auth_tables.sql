-- Live auth tables used by runtime/admin role management.

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.auth_roles (
  role_id TEXT PRIMARY KEY,
  permissions TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS auth.auth_actor_roles (
  actor_id TEXT NOT NULL,
  role_id TEXT NOT NULL,
  PRIMARY KEY (actor_id, role_id)
);
