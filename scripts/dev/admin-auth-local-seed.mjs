#!/usr/bin/env node
import { spawn } from "node:child_process";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const config = parseArgs(process.argv.slice(2));
const profile = localProfile();

if (!["local", "dev", "development", "test", "ci"].includes(profile)) {
  throw new Error(
    "refusing to seed admin auth without an explicit local profile; set REEF_ENV=local or pass --profile=local",
  );
}
if (!config.target) {
  throw new Error("usage: bun scripts/dev/admin-auth-local-seed.mjs --github-login=<login>|--reef-user-id=<id> [--role=operator|platform-admin]");
}
if (!["operator", "platform-admin"].includes(config.role)) {
  throw new Error("--role must be operator or platform-admin");
}
if (!["trusted", "limited", "new"].includes(config.trustState)) {
  throw new Error("--trust-state must be trusted, limited, or new");
}

const dbService = env("ADMIN_LOCAL_SEED_DB_SERVICE", "postgres");
const dbUser = env("ADMIN_LOCAL_SEED_DB_USER", "reef");
const dbName = env("ADMIN_LOCAL_SEED_DB_NAME", "reef");
const actorId = env("ADMIN_LOCAL_SEED_ACTOR_ID", "local-admin-seed");
const reason = config.reason || "local admin auth bootstrap";

const reefUserId = await findReefUserId(config.target);
if (!reefUserId) {
  throw new Error(
    `no Admin DB user found for ${config.target}; sign into /admin once with GitHub first, then rerun this seed`,
  );
}

if (config.dryRun) {
  console.log(`would set ${reefUserId} to trust=${config.trustState}, role=${config.role}`);
  process.exit(0);
}

await runPsql(
  [
    "-v",
    `reef_user_id=${reefUserId}`,
    "-v",
    `role_id=${config.role}`,
    "-v",
    `trust_state=${config.trustState}`,
    "-v",
    `actor_id=${actorId}`,
    "-v",
    `reason=${reason}`,
  ],
  `
BEGIN;

INSERT INTO admin.roles(role_id, description, created_at) VALUES
  ('participant', 'Can own accepted bots and manage own bot config', now()),
  ('reviewer', 'Can review bot submissions', now()),
  ('operator', 'Can operate arena runs and game settings', now()),
  ('secret-admin', 'Can perform explicit secret repair and rotation actions', now()),
  ('platform-admin', 'Can administer the Reef control plane', now())
ON CONFLICT (role_id) DO NOTHING;

UPDATE admin.users
SET trust_state = :'trust_state',
    updated_at = now()
WHERE reef_user_id = :'reef_user_id';

INSERT INTO admin.user_roles(reef_user_id, role_id, assigned_by, assigned_at)
VALUES (:'reef_user_id', 'participant', :'actor_id', now())
ON CONFLICT (reef_user_id, role_id) DO NOTHING;

INSERT INTO admin.user_roles(reef_user_id, role_id, assigned_by, assigned_at)
VALUES (:'reef_user_id', :'role_id', :'actor_id', now())
ON CONFLICT (reef_user_id, role_id) DO UPDATE SET
  assigned_by = EXCLUDED.assigned_by,
  assigned_at = EXCLUDED.assigned_at;

INSERT INTO admin.audit_events(event_id, actor_id, event_type, target_type, target_id, detail, occurred_at)
VALUES
  ('local-admin-seed-trust-' || extract(epoch from clock_timestamp())::text, :'actor_id', 'AdminAccessTrustStateChanged', 'admin-user', :'reef_user_id', 'trustState=' || :'trust_state' || ',reason=' || :'reason', now()),
  ('local-admin-seed-role-' || extract(epoch from clock_timestamp())::text, :'actor_id', 'AdminAccessRoleAssigned', 'admin-user', :'reef_user_id', 'roleId=' || :'role_id' || ',reason=' || :'reason', now());

COMMIT;
`,
);

console.log(`seeded ${reefUserId}: trust=${config.trustState}, role=${config.role}`);
console.log("restart platform-api if it was already running with stale auth configuration");

async function findReefUserId(target) {
  const out = await runPsql(
    ["-v", `target=${target}`, "-t", "-A"],
    "SELECT reef_user_id FROM admin.users WHERE reef_user_id = :'target' OR github_login = :'target' ORDER BY last_seen_at DESC LIMIT 1;",
    { passthrough: false },
  );
  return out.trim();
}

async function runPsql(psqlArgs, sql, options = {}) {
  const passthrough = options.passthrough ?? true;
  const args = [
    "compose",
    "exec",
    "-T",
    dbService,
    "psql",
    "-U",
    dbUser,
    "-d",
    dbName,
    "-X",
    "-v",
    "ON_ERROR_STOP=1",
    ...psqlArgs,
  ];
  return await new Promise((resolve, reject) => {
    const child = spawn("docker", args, {
      stdio: ["pipe", passthrough ? "inherit" : "pipe", passthrough ? "inherit" : "pipe"],
      env: process.env,
    });
    let stdout = "";
    let stderr = "";
    child.stdout?.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr?.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve(stdout);
        return;
      }
      reject(new Error(`docker ${args.join(" ")} failed with code ${code}${stderr ? `\n${stderr}` : ""}`));
    });
    child.stdin.end(sql);
  });
}

function parseArgs(args) {
  const parsed = {
    target: "",
    role: env("ADMIN_LOCAL_SEED_ROLE", "operator"),
    trustState: env("ADMIN_LOCAL_SEED_TRUST_STATE", "trusted"),
    reason: env("ADMIN_LOCAL_SEED_REASON", ""),
    dryRun: false,
    profile: "",
  };
  for (const arg of args) {
    if (arg.startsWith("--github-login=")) parsed.target = arg.slice("--github-login=".length);
    else if (arg.startsWith("--reef-user-id=")) parsed.target = arg.slice("--reef-user-id=".length);
    else if (arg.startsWith("--role=")) parsed.role = arg.slice("--role=".length);
    else if (arg.startsWith("--trust-state=")) parsed.trustState = arg.slice("--trust-state=".length);
    else if (arg.startsWith("--reason=")) parsed.reason = arg.slice("--reason=".length);
    else if (arg.startsWith("--profile=")) parsed.profile = arg.slice("--profile=".length);
    else if (arg === "--dry-run") parsed.dryRun = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  parsed.target = parsed.target.trim();
  parsed.role = parsed.role.trim();
  parsed.trustState = parsed.trustState.trim();
  parsed.reason = parsed.reason.trim();
  parsed.profile = parsed.profile.trim().toLowerCase();
  return parsed;
}

function localProfile() {
  if (config.profile) return config.profile;
  return [
    "PLATFORM_RUNTIME_PROFILE",
    "REEF_ENV",
    "REEF_DEPLOYMENT_ENV",
    "DEPLOYMENT_ENV",
    "ENVIRONMENT",
    "APP_ENV",
    "PROFILE",
  ]
    .map((key) => env(key, "").trim().toLowerCase())
    .find(Boolean) ?? "";
}
