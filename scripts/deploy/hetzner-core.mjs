#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import { resolve4 } from "node:dns/promises";
import { createConnection } from "node:net";
import { homedir, tmpdir } from "node:os";
import { basename, dirname, resolve } from "node:path";
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

function loadEnvFile(path) {
  if (!existsSync(path)) return;
  const text = readFileSync(path, "utf8");
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = line.match(/^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/);
    if (!match) continue;
    const [, key, rawValue] = match;
    if (process.env[key] !== undefined) continue;
    let value = rawValue.trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    process.env[key] = value;
  }
}

loadEnvFile(resolve(repoRoot, ".env"));
loadEnvFile(resolve(tofuDir, ".env"));

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

function runStatus(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    stdio: "inherit",
    env: options.env || process.env,
  });
  return result.status ?? 1;
}

function runWithInput(command, args, input, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    input,
    stdio: ["pipe", "inherit", "inherit"],
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

function captureOptional(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    encoding: "utf8",
    env: process.env,
  });
  return {
    status: result.status ?? 1,
    stdout: (result.stdout || "").trim(),
    stderr: (result.stderr || "").trim(),
  };
}

function requireCommand(command, installHint) {
  const result = spawnSync(command, ["--version"], {
    cwd: repoRoot,
    encoding: "utf8",
    env: process.env,
  });
  if (result.error?.code === "ENOENT") {
    console.error(`Missing required local command: ${command}`);
    console.error(installHint);
    process.exit(1);
  }
}

function commandOrFallback(command, fallbackPath) {
  const result = spawnSync(command, ["--version"], {
    cwd: repoRoot,
    encoding: "utf8",
    env: process.env,
  });
  if (!result.error && result.status === 0) {
    return command;
  }
  if (fallbackPath && existsSync(fallbackPath)) {
    return fallbackPath;
  }
  return command;
}

function sha256(path) {
  const hash = createHash("sha256");
  hash.update(readFileSync(path));
  return hash.digest("hex");
}

function tcpReachable(hostname, port, timeoutMs = 3000) {
  return new Promise((resolveCheck) => {
    const socket = createConnection({ host: hostname, port });
    let settled = false;
    const finish = (reachable) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolveCheck(reachable);
    };
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => finish(true));
    socket.once("timeout", () => finish(false));
    socket.once("error", () => finish(false));
  });
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
  backup-bootstrap
             create/use local age identity, configure host backup.env, run one
             encrypted backup, copy it locally, and install the timer
  backup-timer
             install or update host-side encrypted DB backup timer
  backup-restore-check
             copy the latest encrypted backup locally, decrypt with local age
             identity, and run pg_restore --list for each dump
  hosted-smoke
             run the default hosted simulator smoke and fail on system/trace
             failures
  ops-check  run quick operator checks for DNS, public exposure, host status,
             platform health, Bao seal state, backups, and backup timer
  public-up  sync Caddy/admin assets, set API_DOMAIN, open host UFW 80/443,
             and start the Compose public profile
  public-down
             stop Caddy public profile and close host UFW 80/443
  admin-auth-up
             persist GitHub OAuth config to host secrets and restart runtime
  admin-auth-down
             disable hosted GitHub OAuth config and restart runtime
  admin-role-grant
             one-time grant arena.admin to ADMIN_GITHUB_USER_ID or gh user
  admin-auth-smoke
             verify hosted admin shell, OAuth start, Caddy fallback, and
             legacy mutation-route cleanup
  verify     run host-side runtime verification checks
  soak       run host-side simulator soak; pass RATE/DURATION/WORKERS via env
  status     show docker compose service status
  deploy     sync, generate missing local secrets, migrate, pull, and restart

Environment:
  REEF_HETZNER_HOST       server IPv4 or DNS name; defaults to tofu output core_ipv4
  REEF_HETZNER_OPS_USER   SSH user; default ops
  REEF_HETZNER_DEPLOY_DIR server deploy directory; default /opt/reef
  REEF_BACKUP_AGE_IDENTITY_PATH local age identity path; default ~/Documents/reef-backups-age-identity.txt
  REEF_BACKUP_ARCHIVE_DIR local encrypted archive copy dir; default ~/Documents
  PUBLIC_INGRESS_EXPECTED set to 1 when ops-check should expect 80/443 open
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
  run("rsync", ["-av", "--exclude", "secrets/", "--exclude", "openbao/logs/", `${serverDir}/`, `${target}:${deployDir}/`]);
}

function syncMigrations() {
  run("rsync", ["-av", `${migrationsDir}/`, `${target}:${deployDir}/postgres/migrations/`]);
}

function buildAndSyncArenaAdmin() {
  // Empty base URL bakes relative fetch paths (e.g. "/api/v1/...") into the
  // static build, matching the same-origin Caddy reverse-proxy setup — the
  // deployed site must not point back at localhost.
  run("fnm", ["exec", "--using=22.22.1", "--", "bun", "install", "--frozen-lockfile"], { cwd: arenaAdminDir });
  run("fnm", ["exec", "--using=22.22.1", "--", "bun", "run", "build"], {
    cwd: arenaAdminDir,
    env: { ...process.env, PUBLIC_ARENA_API_BASE_URL: "" },
  });
  run("ssh", [target, `mkdir -p ${deployDir}/arena-admin`]);
  run("rsync", ["-av", "--delete", `${arenaAdminDir}/build/`, `${target}:${deployDir}/arena-admin/`]);
}

function publicUp() {
  syncServerBundle();
  buildAndSyncArenaAdmin();
  const apiDomain =
    env("API_DOMAIN") ||
    captureOptional("tofu", ["output", "-raw", "api_domain"], { cwd: tofuDir }).stdout ||
    "reef-arena-admin.shrimpworks.dev";
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${deployDir}`,
      "tmp=\"$(mktemp)\"",
      "if [ -f .env ]; then grep -v '^API_DOMAIN=' .env > \"$tmp\"; else : > \"$tmp\"; fi",
      `printf '%s\\n' 'API_DOMAIN=${apiDomain}' >> "$tmp"`,
      "mv \"$tmp\" .env",
      "sudo ufw allow 80/tcp",
      "sudo ufw allow 443/tcp",
      "sudo ufw reload",
      "docker compose --profile public up -d --force-recreate caddy",
      "docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile",
    ].join(" && "),
  ]);
}

function publicDown() {
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${deployDir}`,
      "docker compose --profile public stop caddy || true",
      "docker compose --profile public rm -f caddy || true",
      "sudo ufw delete allow 80/tcp || true",
      "sudo ufw delete allow 443/tcp || true",
      "sudo ufw reload",
    ].join(" && "),
  ]);
}

function requireLocalEnv(keys) {
  const missing = keys.filter((key) => env(key).trim() === "");
  if (missing.length > 0) {
    console.error(`Missing required local environment values: ${missing.join(", ")}`);
    process.exit(1);
  }
}

function adminAuthEnvContent(enabled, options = {}) {
  const legacyMutationRoutesEnabled = options.legacyMutationRoutesEnabled || false;
  if (!enabled) {
    return "PLATFORM_ADMIN_AUTH_ENABLED=false\n";
  }
  requireLocalEnv(["GITHUB_OAUTH_CLIENT_ID", "GITHUB_OAUTH_CLIENT_SECRET", "GITHUB_OAUTH_REDIRECT_URI"]);
  return [
    "PLATFORM_ADMIN_AUTH_ENABLED=true",
    `PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=${legacyMutationRoutesEnabled ? "true" : "false"}`,
    `GITHUB_OAUTH_CLIENT_ID=${env("GITHUB_OAUTH_CLIENT_ID")}`,
    `GITHUB_OAUTH_CLIENT_SECRET=${env("GITHUB_OAUTH_CLIENT_SECRET")}`,
    `GITHUB_OAUTH_REDIRECT_URI=${env("GITHUB_OAUTH_REDIRECT_URI")}`,
    "",
  ].join("\n");
}

function configureAdminAuth(enabled, options = {}) {
  syncServerBundle();
  runWithInput(
    "ssh",
    [
      target,
      [
        "set -euo pipefail",
        `mkdir -p ${deployDir}/secrets`,
        "umask 077",
        `cat > ${deployDir}/secrets/admin-auth.env`,
        `chmod 600 ${deployDir}/secrets/admin-auth.env`,
        `cd ${deployDir}`,
        "chmod +x ./scripts/*.sh",
        "./scripts/generate-local-secrets.sh",
        "docker compose up -d --force-recreate platform-runtime",
        "docker compose up -d --force-recreate caddy",
        "docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile",
      ].join(" && "),
    ],
    adminAuthEnvContent(enabled, options)
  );
}

function setLegacyMutationRoutes(enabled) {
  const value = enabled ? "true" : "false";
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `mkdir -p ${deployDir}/secrets`,
      `touch ${deployDir}/secrets/admin-auth.env`,
      `tmp="$(mktemp)"`,
      `grep -v '^PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=' ${deployDir}/secrets/admin-auth.env > "$tmp" || true`,
      `printf '%s\\n' 'PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=${value}' >> "$tmp"`,
      `install -m 600 "$tmp" ${deployDir}/secrets/admin-auth.env`,
      `rm -f "$tmp"`,
      `cd ${deployDir}`,
      "chmod +x ./scripts/*.sh",
      "./scripts/generate-local-secrets.sh",
      "docker compose up -d --force-recreate platform-runtime",
      "for i in $(seq 1 30); do curl -fsS http://127.0.0.1:8080/health >/dev/null && exit 0; sleep 1; done; curl -fsS http://127.0.0.1:8080/health >/dev/null",
    ].join(" && "),
  ]);
}

function runHostedSmoke() {
  setLegacyMutationRoutes(true);
  let status = 1;
  try {
    status = runStatus("ssh", [
      target,
      `chmod +x ${deployDir}/scripts/*.sh && cd ${deployDir} && RATE="${env("RATE", "50")}" DURATION="${env("DURATION", "30s")}" WORKERS="${env("WORKERS", "16")}" TRACE_CHECK_LIMIT="${env("TRACE_CHECK_LIMIT", "20")}" ./scripts/hosted-smoke.sh`,
    ]);
  } finally {
    setLegacyMutationRoutes(false);
  }
  if (status !== 0) {
    process.exit(status);
  }
}

function githubUserId() {
  const configured = env("ADMIN_GITHUB_USER_ID");
  if (configured.trim() !== "") return configured.trim();
  const result = captureOptional("gh", ["api", "user", "--jq", ".id"]);
  if (result.status !== 0 || result.stdout.trim() === "") {
    console.error("Missing ADMIN_GITHUB_USER_ID and could not read authenticated GitHub CLI user id.");
    process.exit(1);
  }
  return result.stdout.trim();
}

function grantAdminRole() {
  const userId = githubUserId();
  if (!/^[0-9]+$/.test(userId)) {
    console.error(`Invalid GitHub numeric user id: ${userId}`);
    process.exit(1);
  }

  configureAdminAuth(true, { legacyMutationRoutesEnabled: true });
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      "curl -fsS -X POST http://127.0.0.1:8080/auth/roles -H 'X-Reef-Internal-Route: true' -H 'content-type: application/json' -d '{\"roleId\":\"arena-operator\",\"permissions\":\"arena.admin\"}'",
      `curl -fsS -X POST http://127.0.0.1:8080/auth/actor-roles -H 'X-Reef-Internal-Route: true' -H 'content-type: application/json' -d '{"actorId":"user-gh-${userId}","roleId":"arena-operator"}'`,
    ].join(" && "),
  ]);
  configureAdminAuth(true, { legacyMutationRoutesEnabled: false });
  console.log(`granted arena.admin to user-gh-${userId}`);
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

function backupEnvContent(ageRecipient, existingR2Config = "") {
  const lines = [`AGE_RECIPIENT=${ageRecipient}`];
  const r2Endpoint =
    env("R2_ENDPOINT") || captureOptional("tofu", ["output", "-raw", "r2_backup_endpoint"], { cwd: tofuDir }).stdout;
  const r2Bucket =
    env("R2_BUCKET") || captureOptional("tofu", ["output", "-raw", "r2_backup_bucket"], { cwd: tofuDir }).stdout;
  const r2Config = {
    R2_ENDPOINT: r2Endpoint,
    R2_BUCKET: r2Bucket,
    AWS_ACCESS_KEY_ID: env("AWS_ACCESS_KEY_ID"),
    AWS_SECRET_ACCESS_KEY: env("AWS_SECRET_ACCESS_KEY"),
  };
  const hasR2 = Object.values(r2Config).every((value) => value.trim() !== "");
  if (hasR2) {
    for (const [key, value] of Object.entries(r2Config)) {
      lines.push(`${key}=${value}`);
    }
    lines.push(`AWS_DEFAULT_REGION=${env("AWS_DEFAULT_REGION", "auto")}`);
  } else if (existingR2Config.trim() !== "") {
    lines.push(existingR2Config.trim());
  }
  return `${lines.join("\n")}\n`;
}

function backupBootstrap() {
  syncServerBundle();
  run("ssh", [target, `chmod +x ${deployDir}/scripts/*.sh`]);

  const identityPath = resolve(env("REEF_BACKUP_AGE_IDENTITY_PATH", `${homedir()}/Documents/reef-backups-age-identity.txt`));
  mkdirSync(dirname(identityPath), { recursive: true });

  if (!existsSync(identityPath)) {
    const identity = capture("ssh", [target, "age-keygen"]);
    writeFileSync(identityPath, `${identity.trim()}\n`, { mode: 0o600 });
    chmodSync(identityPath, 0o600);
    console.log(`created local age identity: ${identityPath}`);
  } else {
    chmodSync(identityPath, 0o600);
    console.log(`using existing local age identity: ${identityPath}`);
  }

  const identityText = readFileSync(identityPath, "utf8");
  const recipient = identityText.match(/^# public key: (age1[0-9a-z]+)/m)?.[1];
  if (!recipient) {
    console.error(`Missing age recipient comment in ${identityPath}`);
    process.exit(1);
  }

  const existingR2Config = capture("ssh", [
    target,
    `if [ -f ${deployDir}/secrets/backup.env ]; then sed -n '/^\\(R2_ENDPOINT\\|R2_BUCKET\\|AWS_ACCESS_KEY_ID\\|AWS_SECRET_ACCESS_KEY\\|AWS_DEFAULT_REGION\\)=/p' ${deployDir}/secrets/backup.env; fi`,
  ]);

  runWithInput(
    "ssh",
    [
      target,
      [
        "set -euo pipefail",
        `mkdir -p ${deployDir}/secrets`,
        "umask 077",
        `cat > ${deployDir}/secrets/backup.env`,
        `chmod 600 ${deployDir}/secrets/backup.env`,
      ].join(" && "),
    ],
    backupEnvContent(recipient, existingR2Config)
  );

  run("ssh", [target, `cd ${deployDir} && ./scripts/backup-dbs.sh`]);
  const remoteArchive = capture("ssh", [
    target,
    `find ${deployDir}/backups \\( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \\) -type f | sort | tail -1`,
  ]);
  if (!remoteArchive) {
    console.error("Backup completed but no encrypted archive was found on the host.");
    process.exit(1);
  }

  const archiveDir = resolve(env("REEF_BACKUP_ARCHIVE_DIR", `${homedir()}/Documents`));
  mkdirSync(archiveDir, { recursive: true });
  const localArchive = resolve(archiveDir, basename(remoteArchive));
  run("scp", [`${target}:${remoteArchive}`, localArchive]);
  chmodSync(localArchive, 0o600);

  run("ssh", [target, `cd ${deployDir} && ./scripts/install-backup-timer.sh`]);

  console.log(`backup archive: ${localArchive}`);
  console.log(`sha256: ${sha256(localArchive)}`);
}

function backupRestoreCheck() {
  const ageBin = env("AGE_BIN", "age");
  const pgRestoreBin = env("PG_RESTORE_BIN", commandOrFallback("pg_restore", "/opt/homebrew/opt/libpq/bin/pg_restore"));
  requireCommand(ageBin, "Install age locally before restore checks, for example: brew install age");
  requireCommand(pgRestoreBin, "Install PostgreSQL client tools locally before restore checks, for example: brew install libpq");

  const identityPath = resolve(env("REEF_BACKUP_AGE_IDENTITY_PATH", `${homedir()}/Documents/reef-backups-age-identity.txt`));
  if (!existsSync(identityPath)) {
    console.error(`Missing local age identity: ${identityPath}`);
    process.exit(1);
  }

  const remoteArchive =
    env("REEF_BACKUP_REMOTE_ARCHIVE") ||
    capture("ssh", [
      target,
      `find ${deployDir}/backups \\( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \\) -type f | sort | tail -1`,
    ]);
  if (!remoteArchive) {
    console.error("No remote encrypted backup archive found.");
    process.exit(1);
  }

  const workdir = mkdtempSync(resolve(tmpdir(), "reef-restore-check-"));
  try {
    const localEncrypted = resolve(workdir, basename(remoteArchive));
    const compressedArchive = remoteArchive.endsWith(".tar.gz.age");
    const localArchive = resolve(workdir, compressedArchive ? "reef-db.tar.gz" : "reef-db.tar");
    run("scp", [`${target}:${remoteArchive}`, localEncrypted]);
    run(ageBin, ["-d", "-i", identityPath, "-o", localArchive, localEncrypted]);
    run("tar", ["-C", workdir, compressedArchive ? "-xzf" : "-xf", localArchive]);

    for (const dump of ["openbao", "reef", "admin", "analytics"]) {
      const dumpPath = resolve(workdir, `${dump}.dump`);
      if (!existsSync(dumpPath)) {
        console.error(`missing dump in archive: ${dump}.dump`);
        process.exit(1);
      }
      const result = spawnSync(pgRestoreBin, ["--list", dumpPath], {
        cwd: repoRoot,
        stdio: ["ignore", "ignore", "inherit"],
        env: process.env,
      });
      if (result.status !== 0) {
        process.exit(result.status ?? 1);
      }
      console.log(`restore-list ok: ${dump}.dump`);
    }

    console.log(`restore check complete: ${remoteArchive}`);
  } finally {
    rmSync(workdir, { recursive: true, force: true });
  }
}

async function opsCheck() {
  let ok = true;
  const coreIp = capture("tofu", ["output", "-raw", "core_ipv4"], { cwd: tofuDir });
  const apiDomainResult = captureOptional("tofu", ["output", "-raw", "api_domain"], { cwd: tofuDir });
  const apiDomain = apiDomainResult.status === 0 ? apiDomainResult.stdout : "";
  const publicIngressExpected = ["1", "true", "yes"].includes(env("PUBLIC_INGRESS_EXPECTED").toLowerCase());

  console.log(`core_ipv4=${coreIp}`);
  if (apiDomain) {
    const answers = await resolve4(apiDomain);
    console.log(`dns ${apiDomain} -> ${answers.join(",")}`);
    if (!answers.includes(coreIp)) {
      console.error(`dns mismatch: expected ${coreIp}`);
      ok = false;
    }
  } else {
    console.log("dns skipped: api_domain output is empty");
  }

  const expectedPorts = [
    [22, true],
    [80, publicIngressExpected],
    [443, publicIngressExpected],
    [8080, false],
    [8200, false],
  ];
  for (const [port, expectedReachable] of expectedPorts) {
    const reachable = await tcpReachable(coreIp, port);
    console.log(`public tcp ${port}: ${reachable ? "open" : "closed"}`);
    if (reachable !== expectedReachable) {
      console.error(`unexpected public tcp ${port}: expected ${expectedReachable ? "open" : "closed"}`);
      ok = false;
    }
  }

  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${deployDir}`,
      "docker compose ps",
      "curl -fsS http://127.0.0.1:8080/health",
      "curl -fsS http://127.0.0.1:8200/v1/sys/seal-status | jq '{initialized,sealed,type,t,n,version,storage_type}'",
      "systemctl is-enabled reef-backup.timer",
      "systemctl is-active reef-backup.timer",
      "systemctl list-timers reef-backup.timer --no-pager",
      "find /opt/reef/backups -maxdepth 1 \\( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \\) -type f -printf '%f %s bytes\\n' | sort | tail -5",
    ].join(" && "),
  ]);

  if (!ok) {
    process.exit(1);
  }
}

function adminAuthSmoke() {
  const apiDomain =
    env("API_DOMAIN") ||
    captureOptional("tofu", ["output", "-raw", "api_domain"], { cwd: tofuDir }).stdout ||
    "reef-arena-admin.shrimpworks.dev";
  const baseUrl = `https://${apiDomain}`;
  const failures = [];

  const adminHtml = captureOptional("curl", ["-fsS", `${baseUrl}/admin`]);
  if (adminHtml.status !== 0) {
    failures.push(`GET /admin failed: ${adminHtml.stderr || `exit ${adminHtml.status}`}`);
  } else {
    if (!adminHtml.stdout.includes("checking session")) {
      failures.push("GET /admin did not include the admin session shell");
    }
    if (adminHtml.stdout.includes("trading bots. real market rules. one leaderboard.")) {
      failures.push("GET /admin served the landing page shell");
    }
  }

  const sessionStatus = captureOptional("curl", [
    "-sS",
    "-o",
    "/dev/null",
    "-w",
    "%{http_code}",
    `${baseUrl}/admin/auth/session`,
  ]);
  if (sessionStatus.status !== 0 || sessionStatus.stdout.trim() !== "401") {
    failures.push(`unauthenticated /admin/auth/session expected 401, got ${sessionStatus.stdout || sessionStatus.stderr || `exit ${sessionStatus.status}`}`);
  }

  const oauthStart = captureOptional("curl", [
    "-sS",
    "-o",
    "/dev/null",
    "-w",
    "%{http_code} %{redirect_url}",
    `${baseUrl}/admin/auth/github/start?redirectPath=/admin`,
  ]);
  const oauthOutput = oauthStart.stdout.trim();
  if (oauthStart.status !== 0 || !oauthOutput.startsWith("302 https://github.com/login/oauth/authorize")) {
    failures.push(`OAuth start expected 302 to GitHub, got ${oauthOutput || oauthStart.stderr || `exit ${oauthStart.status}`}`);
  }

  const caddyFallback = captureOptional("ssh", [
    target,
    `cd ${deployDir} && docker compose exec -T caddy sed -n '60,68p' /etc/caddy/Caddyfile`,
  ]);
  if (caddyFallback.status !== 0) {
    failures.push(`could not inspect Caddyfile inside container: ${caddyFallback.stderr || `exit ${caddyFallback.status}`}`);
  } else if (!caddyFallback.stdout.includes("try_files {path} {path}/index.html {path}.html /index.html")) {
    failures.push("Caddy container does not see the clean-url admin fallback with {path}.html");
  }

  const legacyRoute = captureOptional("ssh", [
    target,
    [
      `cd ${deployDir}`,
      "docker compose exec -T platform-runtime env | grep '^PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=false$'",
      "curl -sS -o /dev/null -w '%{http_code}' -X POST http://127.0.0.1:8080/auth/roles -H 'X-Reef-Internal-Route: true' -H 'content-type: application/json' -d '{\"roleId\":\"check-disabled\",\"permissions\":\"arena.admin\"}'",
    ].join(" && "),
  ]);
  if (legacyRoute.status !== 0) {
    failures.push(`legacy route env/status check failed: ${legacyRoute.stderr || legacyRoute.stdout || `exit ${legacyRoute.status}`}`);
  } else if (!legacyRoute.stdout.trim().endsWith("403")) {
    failures.push(`legacy mutation route expected 403, got ${legacyRoute.stdout.trim()}`);
  }

  if (failures.length > 0) {
    for (const failure of failures) {
      console.error(`admin-auth-smoke failed: ${failure}`);
    }
    process.exit(1);
  }

  console.log(`admin auth smoke passed: ${baseUrl}`);
  console.log("admin shell ok");
  console.log("unauthenticated session 401 ok");
  console.log("OAuth start 302 to GitHub ok");
  console.log("Caddy clean-url fallback ok");
  console.log("legacy mutation route disabled ok");
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
        "tmp=\"$(mktemp)\"",
        "if [ -f .env ]; then grep -v '^REEF_\\(PLATFORM_RUNTIME\\|MATCHING_ENGINE\\|SIMULATOR\\)_IMAGE=' .env > \"$tmp\"; else : > \"$tmp\"; fi",
        "printf '%s\\n' REEF_PLATFORM_RUNTIME_IMAGE=reef-platform-runtime:local REEF_MATCHING_ENGINE_IMAGE=reef-matching-engine:local REEF_SIMULATOR_IMAGE=reef-simulator:local >> \"$tmp\"",
        "mv \"$tmp\" .env",
      ].join(" && "),
    ]);
    break;
  case "restart":
    run("ssh", [target, `${remoteCompose} pull --ignore-pull-failures && ${remoteCompose} up -d --remove-orphans`]);
    break;
  case "stream-ack":
    run("ssh", [target, `chmod +x ${deployDir}/scripts/*.sh && cd ${deployDir} && ./scripts/start-stream-ack.sh`]);
    break;
  case "backup-bootstrap":
    backupBootstrap();
    break;
  case "backup-timer":
    run("ssh", [target, `chmod +x ${deployDir}/scripts/*.sh && cd ${deployDir} && ./scripts/install-backup-timer.sh`]);
    break;
  case "backup-restore-check":
    backupRestoreCheck();
    break;
  case "hosted-smoke":
    runHostedSmoke();
    break;
  case "ops-check":
    await opsCheck();
    break;
  case "public-up":
    publicUp();
    break;
  case "public-down":
    publicDown();
    break;
  case "admin-auth-up":
    configureAdminAuth(true);
    break;
  case "admin-auth-down":
    configureAdminAuth(false);
    break;
  case "admin-role-grant":
    grantAdminRole();
    break;
  case "admin-auth-smoke":
    adminAuthSmoke();
    break;
  case "verify":
    run("ssh", [target, `cd ${deployDir} && ./scripts/verify-runtime.sh`]);
    break;
  case "soak":
    run("ssh", [
      target,
      `chmod +x ${deployDir}/scripts/*.sh && cd ${deployDir} && RATE="${env("RATE", "1000")}" DURATION="${env("DURATION", "1m")}" WORKERS="${env("WORKERS", "128")}" MODE="${env("MODE", "strict-lifecycle")}" TRACE_CHECK_LIMIT="${env("TRACE_CHECK_LIMIT", "100")}" ./scripts/run-soak.sh`,
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
