import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadDotEnv, run } from "./lib/dev-utils.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const serverDir = resolve(repoRoot, "infra/hetzner-core/server");
const migrationsRoot = resolve(repoRoot, "scripts/dev/db/migrations");

loadDotEnv();

const command = process.argv[2] || "help";

setDefaultEnv("REEF_DEPLOY_DIR", serverDir);
setDefaultEnv("COMPOSE_PROJECT_NAME", "reef-backbone-local");
setDefaultEnv("REEF_BACKBONE_PLATFORM_HOST_PORT", "18180");
setDefaultEnv("REEF_BACKBONE_OPENBAO_HOST_PORT", "18200");
setDefaultEnv("REEF_BACKBONE_HTTP_HOST_PORT", "18000");
setDefaultEnv("REEF_BACKBONE_HTTPS_HOST_PORT", "18443");
setDefaultEnv("REEF_PLATFORM_RUNTIME_IMAGE", "reef-platform-runtime:backbone-local");
setDefaultEnv("REEF_MATCHING_ENGINE_IMAGE", "reef-matching-engine:backbone-local");
setDefaultEnv("REEF_SIMULATOR_IMAGE", "reef-simulator:backbone-local");
setDefaultEnv("REEF_MIGRATIONS_ROOT", migrationsRoot);

const composeFiles = ["-f", "docker-compose.yml", "-f", "docker-compose.local.yml"];

switch (command) {
  case "help":
  case "--help":
  case "-h":
    usage();
    break;
  case "config":
    await compose(["config", ...process.argv.slice(3)]);
    break;
  case "up-infra":
    await generateSecrets();
    await compose(["up", "-d", "postgres", "postgres-admin", "postgres-analytics", "openbao"]);
    await initOpenBao();
    break;
  case "migrate":
    await migrateAll();
    break;
  case "init-openbao":
    await generateSecrets();
    await compose(["up", "-d", "postgres", "openbao"]);
    await initOpenBao();
    break;
  case "up":
    await generateSecrets();
    await compose(["up", "-d", "--build", "postgres", "postgres-admin", "postgres-analytics", "openbao", "matching-engine"]);
    await initOpenBao();
    await migrateAll();
    await compose(["up", "-d", "--build", "platform-runtime"]);
    await verify();
    break;
  case "verify":
    await verify();
    break;
  case "status":
    await compose(["ps"]);
    break;
  case "logs":
    await compose(["logs", "--tail=160", ...process.argv.slice(3)]);
    break;
  case "down":
    await compose(["down", "--remove-orphans"]);
    break;
  default:
    console.error(`Unknown command: ${command}`);
    usage();
    process.exit(1);
}

function usage() {
  console.log(`Usage: node scripts/dev/backbone-local.mjs <command>

Commands:
  up        start local backbone stack, migrate DBs, and verify runtime
  up-infra  start only backbone Postgres/Admin/Analytics/OpenBao
  init-openbao initialize/unseal local OpenBao and apply Reef policies
  migrate   apply migrations to backbone local DBs
  verify    run backbone runtime checks
  status    show backbone compose status
  logs      show backbone compose logs; pass service names after logs
  config    render backbone compose config
  down      stop backbone local stack

Defaults:
  COMPOSE_PROJECT_NAME=reef-backbone-local
  REEF_BACKBONE_PLATFORM_HOST_PORT=18180
  REEF_BACKBONE_OPENBAO_HOST_PORT=18200
`);
}

async function generateSecrets() {
  await run("./scripts/generate-local-secrets.sh", [], { cwd: serverDir });
}

async function initOpenBao() {
  await run("./scripts/init-openbao-local.sh", [], { cwd: serverDir });
}

async function migrateAll() {
  await withEnv({}, async () => {
    await run("./scripts/apply-migrations.sh", [], { cwd: serverDir });
  });
  await withEnv(
    {
      REEF_MIGRATION_DOMAINS: "admin arena",
      REEF_APP_USER: "admin_app",
      REEF_POSTGRES_SERVICE: "postgres-admin",
      REEF_POSTGRES_DB: "admin",
    },
    async () => {
      await run("./scripts/apply-migrations.sh", [], { cwd: serverDir });
    },
  );
  await withEnv(
    {
      REEF_MIGRATION_DOMAINS: "analytics",
      REEF_APP_USER: "analytics_app",
      REEF_POSTGRES_SERVICE: "postgres-analytics",
      REEF_POSTGRES_DB: "analytics",
    },
    async () => {
      await run("./scripts/apply-migrations.sh", [], { cwd: serverDir });
    },
  );
}

async function verify() {
  await run("./scripts/verify-runtime.sh", [], { cwd: serverDir });
}

async function compose(args) {
  await run("docker", ["compose", ...composeFiles, ...args], { cwd: serverDir });
}

function setDefaultEnv(name, value) {
  if (!process.env[name]) {
    process.env[name] = value;
  }
}

async function withEnv(values, callback) {
  const prior = new Map();
  for (const [key, value] of Object.entries(values)) {
    prior.set(key, process.env[key]);
    process.env[key] = value;
  }
  try {
    await callback();
  } finally {
    for (const [key, value] of prior.entries()) {
      if (value == null) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
    }
  }
}
