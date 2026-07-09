#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const infraDir = resolve(repoRoot, "infra/hetzner-core");
const serverDir = resolve(infraDir, "server");
const migrationsDir = resolve(repoRoot, "scripts/dev/db/migrations");
const tofuDir = resolve(infraDir, "tofu");
const arenaAdminDir = resolve(repoRoot, "apps/arena-admin");
const serviceContexts = [
  ["platform-runtime", resolve(repoRoot, "services/platform-runtime")],
  ["matching-engine", resolve(repoRoot, "services/matching-engine")],
  ["simulator", resolve(repoRoot, "services/simulator")],
];

function env(name, fallback = "") {
  return process.env[name] || fallback;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    stdio: "inherit",
    env: options.env || process.env,
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function capture(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    env: process.env,
  });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || "");
    process.exit(result.status ?? 1);
  }
  return result.stdout.trim();
}

const command = process.argv[2] || "help";

if (command === "help" || command === "--help" || command === "-h") {
  console.log(`Usage: scripts/deploy/hetzner-core.mjs <command>

Commands:
  sync       rsync server files to the Hetzner host
  migrations rsync database migrations to the Hetzner host
  arena-admin
             build the arena-admin static site locally and rsync it to the
             Hetzner host, served same-origin by Caddy at /srv/arena-admin
  build-local-images
             build service images on the Hetzner host and use local tags
  restart    docker compose pull and restart runtime services
  stream-ack start JetStream-backed stream-ack runtime roles
  backup-timer
             install or update host-side encrypted DB backup timer
  verify     run host-side runtime verification checks
  soak       run host-side simulator soak; pass RATE/DURATION/WORKERS via env
  status     show docker compose service status
  deploy     sync, generate missing local secrets, migrate, pull, and restart

Environment:
  REEF_HETZNER_HOST       server IPv4 or DNS name; defaults to tofu output core_ipv4
  REEF_HETZNER_OPS_USER   SSH user; default ops
  REEF_HETZNER_DEPLOY_DIR server deploy directory; default /opt/reef
`);
  process.exit(0);
}

const opsUser = env("REEF_HETZNER_OPS_USER", "ops");
const host = env("REEF_HETZNER_HOST") || (existsSync(tofuDir) ? capture("tofu", ["output", "-raw", "core_ipv4"], { cwd: tofuDir }) : "");
const deployDir = env("REEF_HETZNER_DEPLOY_DIR", "/opt/reef");

if (!host) {
  console.error("Missing Hetzner host. Set REEF_HETZNER_HOST or run tofu apply first.");
  process.exit(1);
}

const target = `${opsUser}@${host}`;
const remoteCompose = `cd ${deployDir} && docker compose`;

function syncServerBundle() {
  run("rsync", ["-av", "--exclude", "secrets/", `${serverDir}/`, `${target}:${deployDir}/`]);
}

function syncMigrations() {
  run("rsync", ["-av", `${migrationsDir}/`, `${target}:${deployDir}/postgres/migrations/`]);
}

function buildAndSyncArenaAdmin() {
  // Empty base URL bakes relative fetch paths (e.g. "/api/v1/...") into the
  // static build, matching the same-origin Caddy reverse-proxy setup — the
  // deployed site must not point back at localhost.
  run("bun", ["install", "--frozen-lockfile"], { cwd: arenaAdminDir });
  run("bun", ["run", "build"], {
    cwd: arenaAdminDir,
    env: { ...process.env, PUBLIC_ARENA_API_BASE_URL: "" },
  });
  run("ssh", [target, `mkdir -p ${deployDir}/arena-admin`]);
  run("rsync", ["-av", "--delete", `${arenaAdminDir}/build/`, `${target}:${deployDir}/arena-admin/`]);
}

function syncServiceContext(name, path) {
  run("ssh", [target, `mkdir -p /opt/reef-build/services/${name}`]);
  run("rsync", [
    "-az",
    "--delete",
    "--exclude",
    ".git",
    "--exclude",
    "build",
    "--exclude",
    ".gradle",
    `${path}/`,
    `${target}:/opt/reef-build/services/${name}/`,
  ]);
}

switch (command) {
  case "sync":
    syncServerBundle();
    break;
  case "migrations":
    syncMigrations();
    break;
  case "arena-admin":
    buildAndSyncArenaAdmin();
    break;
  case "build-local-images":
    for (const [name, path] of serviceContexts) {
      syncServiceContext(name, path);
    }
    run("ssh", [
      target,
      [
        "set -euo pipefail",
        "cd /opt/reef-build/services/platform-runtime && docker build -t reef-platform-runtime:local .",
        "cd /opt/reef-build/services/matching-engine && docker build --network=host -t reef-matching-engine:local .",
        "cd /opt/reef-build/services/simulator && docker build --network=host -t reef-simulator:local .",
        `cd ${deployDir}`,
        "printf '%s\\n' REEF_PLATFORM_RUNTIME_IMAGE=reef-platform-runtime:local REEF_MATCHING_ENGINE_IMAGE=reef-matching-engine:local REEF_SIMULATOR_IMAGE=reef-simulator:local > .env",
      ].join(" && "),
    ]);
    break;
  case "restart":
    run("ssh", [target, `${remoteCompose} pull --ignore-pull-failures && ${remoteCompose} up -d --remove-orphans`]);
    break;
  case "stream-ack":
    run("ssh", [target, `cd ${deployDir} && ./scripts/start-stream-ack.sh`]);
    break;
  case "backup-timer":
    run("ssh", [target, `cd ${deployDir} && ./scripts/install-backup-timer.sh`]);
    break;
  case "verify":
    run("ssh", [target, `cd ${deployDir} && ./scripts/verify-runtime.sh`]);
    break;
  case "soak":
    run("ssh", [
      target,
      `cd ${deployDir} && RATE="${env("RATE", "1000")}" DURATION="${env("DURATION", "1m")}" WORKERS="${env("WORKERS", "128")}" MODE="${env("MODE", "strict-lifecycle")}" TRACE_CHECK_LIMIT="${env("TRACE_CHECK_LIMIT", "100")}" ./scripts/run-soak.sh`,
    ]);
    break;
  case "status":
    run("ssh", [target, `${remoteCompose} ps`]);
    break;
  case "deploy":
    syncServerBundle();
    syncMigrations();
    buildAndSyncArenaAdmin();
    run("ssh", [
      target,
      [
        `chmod +x ${deployDir}/scripts/*.sh ${deployDir}/postgres/init/*.sh ${deployDir}/postgres-admin/init/*.sh ${deployDir}/postgres-analytics/init/*.sh`,
        `cd ${deployDir}`,
        "./scripts/generate-local-secrets.sh",
        "docker compose pull --ignore-pull-failures",
        "docker compose up -d postgres postgres-admin postgres-analytics openbao matching-engine",
        "./scripts/apply-migrations.sh",
        "REEF_MIGRATION_DOMAINS='admin arena' REEF_APP_USER=admin_app REEF_POSTGRES_SERVICE=postgres-admin REEF_POSTGRES_DB=admin ./scripts/apply-migrations.sh",
        "REEF_MIGRATION_DOMAINS=analytics REEF_APP_USER=analytics_app REEF_POSTGRES_SERVICE=postgres-analytics REEF_POSTGRES_DB=analytics ./scripts/apply-migrations.sh",
        "docker compose up -d --remove-orphans",
        "./scripts/verify-runtime.sh",
      ].join(" && "),
    ]);
    break;
  default:
    console.error(`Unknown command: ${command}`);
    process.exit(1);
}
