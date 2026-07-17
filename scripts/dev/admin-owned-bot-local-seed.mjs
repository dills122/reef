#!/usr/bin/env node
import { spawn } from "node:child_process";

import { composeArgs } from "./lib/compose-utils.mjs";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const config = parseArgs(process.argv.slice(2));
const profile = localProfile();

if (!["local", "dev", "development", "test", "ci"].includes(profile)) {
  throw new Error(
    "refusing to seed owned bot without an explicit local profile; set REEF_ENV=local or pass --profile=local",
  );
}
if (!config.target) {
  throw new Error(
    "usage: node scripts/dev/admin-owned-bot-local-seed.mjs --github-login=<login>|--reef-user-id=<id> [--bot-id=<id>]",
  );
}

const adminDbService = env("ADMIN_LOCAL_SEED_DB_SERVICE", "postgres");
const arenaDbService = env("ARENA_LOCAL_SEED_DB_SERVICE", "arena-postgres");
const adminDbUser = env("ADMIN_LOCAL_SEED_DB_USER", env("LOCAL_SEED_DB_USER", "reef"));
const adminDbName = env("ADMIN_LOCAL_SEED_DB_NAME", env("LOCAL_SEED_DB_NAME", "reef"));
const arenaDbUser = env("ARENA_LOCAL_SEED_DB_USER", env("LOCAL_SEED_DB_USER", "reef"));
const arenaDbName = env("ARENA_LOCAL_SEED_DB_NAME", env("LOCAL_SEED_DB_NAME", "reef"));
const actorId = env("ADMIN_LOCAL_SEED_ACTOR_ID", "local-admin-seed");
const reason = config.reason || "local owned bot bootstrap";

const user = await findUser(config.target);
if (!user) {
  throw new Error(
    `no Admin DB user found for ${config.target}; sign into /admin once with GitHub first, then rerun this seed`,
  );
}

const bot = {
  botId: config.botId || "dsteele-spread-maker",
  fileName: config.fileName || "packages/bot-sdk/examples/simple-market-maker.ts",
  name: config.name || config.botId || "dsteele-spread-maker",
  publisher: config.publisher || user.githubLogin,
  email: config.email || `${user.githubUserId}+${user.githubLogin}@users.noreply.github.com`,
  description: config.description || "Local demo bot linked to the signed-in GitHub user.",
  version: config.version || "local-dev",
};

if (config.dryRun) {
  console.log(`would register ${bot.botId} and assign owner ${user.reefUserId} (${user.githubLogin})`);
  process.exit(0);
}

await runPsql(
  arenaDbService,
  arenaDbUser,
  arenaDbName,
  [
    "-v",
    `bot_id=${bot.botId}`,
    "-v",
    `file_name=${bot.fileName}`,
    "-v",
    `name=${bot.name}`,
    "-v",
    `publisher=${bot.publisher}`,
    "-v",
    `email=${bot.email}`,
    "-v",
    `description=${bot.description}`,
    "-v",
    `version=${bot.version}`,
  ],
  `
INSERT INTO arena.bots (bot_id, file_name, name, publisher, email, description, version, created_at)
VALUES (:'bot_id', :'file_name', :'name', :'publisher', :'email', :'description', :'version', now())
ON CONFLICT (bot_id) DO UPDATE SET
  file_name = EXCLUDED.file_name,
  name = EXCLUDED.name,
  publisher = EXCLUDED.publisher,
  email = EXCLUDED.email,
  description = EXCLUDED.description,
  version = EXCLUDED.version;
`,
);

await runPsql(
  adminDbService,
  adminDbUser,
  adminDbName,
  [
    "-v",
    `reef_user_id=${user.reefUserId}`,
    "-v",
    `bot_id=${bot.botId}`,
    "-v",
    `actor_id=${actorId}`,
    "-v",
    `reason=${reason}`,
  ],
  `
BEGIN;

INSERT INTO admin.user_bot_ownerships (reef_user_id, bot_id, ownership_state, assigned_by, assigned_at)
VALUES (:'reef_user_id', :'bot_id', 'owner', :'actor_id', now())
ON CONFLICT (reef_user_id, bot_id) DO UPDATE SET
  ownership_state = 'owner',
  assigned_by = EXCLUDED.assigned_by,
  assigned_at = EXCLUDED.assigned_at;

INSERT INTO admin.audit_events(event_id, actor_id, event_type, target_type, target_id, detail, occurred_at)
VALUES (
  'local-owned-bot-seed-' || extract(epoch from clock_timestamp())::text,
  :'actor_id',
  'AdminBotOwnershipAssigned',
  'arena-bot',
  :'bot_id',
  'reefUserId=' || :'reef_user_id' || ',reason=' || :'reason',
  now()
);

COMMIT;
`,
);

console.log(`seeded owned bot ${bot.botId} for ${user.reefUserId} (${user.githubLogin})`);

async function findUser(target) {
  const out = await runPsql(
    adminDbService,
    adminDbUser,
    adminDbName,
    ["-v", `target=${target}`, "-t", "-A", "-F", "\t"],
    `
SELECT reef_user_id, github_login, github_user_id
FROM admin.users
WHERE reef_user_id = :'target' OR github_login = :'target'
ORDER BY last_seen_at DESC
LIMIT 1;
`,
    { passthrough: false },
  );
  const [reefUserId, githubLogin, githubUserId] = out.trim().split("\t");
  if (!reefUserId || !githubLogin || !githubUserId) return null;
  return { reefUserId, githubLogin, githubUserId };
}

async function runPsql(service, dbUser, dbName, psqlArgs, sql, options = {}) {
  const passthrough = options.passthrough ?? true;
  const args = composeArgs([
    "exec",
    "-T",
    service,
    "psql",
    "-U",
    dbUser,
    "-d",
    dbName,
    "-X",
    "-v",
    "ON_ERROR_STOP=1",
    ...psqlArgs,
  ]);
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
    botId: env("ADMIN_LOCAL_SEED_BOT_ID", "dsteele-spread-maker"),
    fileName: env("ADMIN_LOCAL_SEED_BOT_FILE", ""),
    name: env("ADMIN_LOCAL_SEED_BOT_NAME", ""),
    publisher: env("ADMIN_LOCAL_SEED_BOT_PUBLISHER", ""),
    email: env("ADMIN_LOCAL_SEED_BOT_EMAIL", ""),
    description: env("ADMIN_LOCAL_SEED_BOT_DESCRIPTION", ""),
    version: env("ADMIN_LOCAL_SEED_BOT_VERSION", "local-dev"),
    reason: env("ADMIN_LOCAL_SEED_REASON", ""),
    dryRun: false,
    profile: "",
  };
  for (const arg of args) {
    if (arg.startsWith("--github-login=")) parsed.target = arg.slice("--github-login=".length);
    else if (arg.startsWith("--reef-user-id=")) parsed.target = arg.slice("--reef-user-id=".length);
    else if (arg.startsWith("--bot-id=")) parsed.botId = arg.slice("--bot-id=".length);
    else if (arg.startsWith("--file-name=")) parsed.fileName = arg.slice("--file-name=".length);
    else if (arg.startsWith("--name=")) parsed.name = arg.slice("--name=".length);
    else if (arg.startsWith("--publisher=")) parsed.publisher = arg.slice("--publisher=".length);
    else if (arg.startsWith("--email=")) parsed.email = arg.slice("--email=".length);
    else if (arg.startsWith("--description=")) parsed.description = arg.slice("--description=".length);
    else if (arg.startsWith("--version=")) parsed.version = arg.slice("--version=".length);
    else if (arg.startsWith("--reason=")) parsed.reason = arg.slice("--reason=".length);
    else if (arg.startsWith("--profile=")) parsed.profile = arg.slice("--profile=".length);
    else if (arg === "--dry-run") parsed.dryRun = true;
    else throw new Error(`unknown argument: ${arg}`);
  }
  for (const key of Object.keys(parsed)) {
    if (typeof parsed[key] === "string") parsed[key] = parsed[key].trim();
  }
  return parsed;
}

function localProfile() {
  if (config.profile) return config.profile.toLowerCase();
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
