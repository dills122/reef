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

function captureWithInput(command, args, input, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repoRoot,
    input,
    encoding: "utf8",
    env: options.env || process.env,
  });
  if (result.status !== 0) {
    process.stderr.write(result.stderr || "");
    process.exit(result.status ?? 1);
  }
  return (result.stdout || "").trim();
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
  let result;
  try {
    result = spawnSync(command, args, {
      cwd: options.cwd || repoRoot,
      encoding: "utf8",
      env: process.env,
    });
  } catch (err) {
    return {
      status: 1,
      stdout: "",
      stderr: err instanceof Error ? err.message : String(err),
    };
  }
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

function runBun(args, options = {}) {
  const fnm = captureOptional("fnm", ["--version"]);
  if (fnm.status === 0) {
    run("fnm", ["exec", "--using=22.22.1", "--", "bun", ...args], options);
    return;
  }
  run("bun", args, options);
}

function shellQuote(value) {
  return `'${String(value).replace(/'/g, "'\\''")}'`;
}

function githubRepositoryFullName() {
  const configured = env("REEF_GITHUB_REPOSITORY").trim();
  if (configured !== "") return configured;

  const origin = captureOptional("git", ["remote", "get-url", "origin"]).stdout;
  const match = origin.match(/github\.com[:/](.+?)(?:\.git)?$/);
  if (match?.[1]) return match[1];

  console.error("Could not derive the GitHub owner/repository from REEF_GITHUB_REPOSITORY or the origin remote.");
  console.error("Set REEF_GITHUB_REPOSITORY explicitly (for example: owner/reef).");
  process.exit(1);
}

function configuredApiDomain() {
  const configured = env("API_DOMAIN").trim();
  if (configured !== "") return configured;

  const output = captureOptional("tofu", ["output", "-raw", "api_domain"], { cwd: tofuDir });
  if (output.status === 0 && output.stdout.trim() !== "") return output.stdout.trim();

  console.error("Missing public API domain. Set API_DOMAIN or configure the OpenTofu api_domain output.");
  process.exit(1);
}

function resolveUserPath(path) {
  if (path === "~") return homedir();
  if (path.startsWith("~/")) return resolve(homedir(), path.slice(2));
  return resolve(path);
}

function openBaoTokenForBootstrap() {
  const token = env("BAO_TOKEN").trim();
  if (token !== "") return token;

  const initJsonPath = env("REEF_OPENBAO_INIT_JSON").trim();
  if (initJsonPath === "") return "";
  const parsed = JSON.parse(readFileSync(resolveUserPath(initJsonPath), "utf8"));
  return String(parsed.root_token || "").trim();
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
  tailscale-bootstrap
             install Tailscale, permit SSH on tailscale0, and authenticate the
             host interactively without storing an auth key in Reef
  tailscale-status
             show host Tailscale service, peer, IP, and UFW status
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
  deploy-receiver-up
             sync server bundle, generate receiver env, and rebuild/restart
             deploy-receiver plus Caddy public route
  deploy-automation-up
             sync the host deploy scripts and install/update the restricted
             GitHub Actions SSH public key
  admin-auth-up
             persist GitHub OAuth config to host secrets and restart runtime
  admin-auth-down
             disable hosted GitHub OAuth config and restart runtime
  admin-role-grant
             one-time grant arena.admin to ADMIN_GITHUB_USER_ID or gh user
  admin-actor-role-grant
             one-time grant ADMIN_ACTOR_ID (default bot-submission-ci) arena.admin
  admin-auth-smoke
             verify hosted admin shell, OAuth start, Caddy fallback, and
             legacy mutation-route cleanup
  bot-config-upgrade
             one-time post-merge setup for Admin-managed OpenBao bot config:
             sync server/admin assets, configure the dedicated OpenBao AppRole
             when BAO_TOKEN is provided, persist AppRole credentials, restart
             platform-runtime, and print any remaining manual steps
  verify     run host-side runtime verification checks
  soak       run host-side simulator soak; pass RATE/DURATION/WORKERS via env
  status     show docker compose service status
  deploy     sync, generate missing local secrets, migrate, pull, and restart

Environment:
  REEF_HETZNER_HOST       Tailscale name/IP, public IPv4, or DNS name; defaults to tofu operator_ssh_host, then core_ipv4
  REEF_HETZNER_OPS_USER   SSH user; default ops
  REEF_HETZNER_DEPLOY_DIR server deploy directory; default /opt/reef
  REEF_TAILSCALE_HOSTNAME optional host name override; default remote OS hostname
  REEF_GITHUB_DEPLOY_PUBLIC_KEY_PATH local ed25519 public key installed by deploy-automation-up
  REEF_OPENBAO_INIT_JSON  optional local OpenBao init JSON used by bot-config-upgrade when BAO_TOKEN is not set
  REEF_BACKUP_AGE_IDENTITY_PATH local age identity path; default ~/Documents/reef-backups-age-identity.txt
  REEF_BACKUP_ARCHIVE_DIR local encrypted archive copy dir; default ~/Documents
  PUBLIC_INGRESS_EXPECTED set to 1 when ops-check should expect 80/443 open
`);
  process.exit(0);
}

const opsUser = env("REEF_HETZNER_OPS_USER", "ops");
const discoveredOperatorHost = existsSync(tofuDir)
  ? captureOptional("tofu", ["output", "-raw", "operator_ssh_host"], { cwd: tofuDir }).stdout
  : "";
const discoveredPublicHost =
  existsSync(tofuDir) && discoveredOperatorHost === ""
    ? captureOptional("tofu", ["output", "-raw", "core_ipv4"], { cwd: tofuDir }).stdout
    : "";
const host = env("REEF_HETZNER_HOST") || discoveredOperatorHost || discoveredPublicHost;
const deployDir = env("REEF_HETZNER_DEPLOY_DIR", "/opt/reef");

if (!host) {
  console.error("Missing Hetzner host. Set REEF_HETZNER_HOST or run tofu apply first.");
  process.exit(1);
}

const target = `${opsUser}@${host}`;
const remoteDeployDir = shellQuote(deployDir);
const remoteCompose = `cd ${remoteDeployDir} && docker compose`;

function remoteDeployPath(relativePath = "") {
  return shellQuote(relativePath ? `${deployDir}/${relativePath}` : deployDir);
}

function remoteGenerateLocalSecretsCommand() {
  return `DEPLOY_RECEIVER_EXPECTED_REPOSITORY=${shellQuote(githubRepositoryFullName())} ./scripts/generate-local-secrets.sh`;
}

function syncServerBundle() {
  run("rsync", ["-av", "--exclude", "secrets/", "--exclude", "openbao/logs/", `${serverDir}/`, `${target}:${deployDir}/`]);
}

function tailscaleBootstrap() {
  syncServerBundle();
  const tailscaleHostname = env("REEF_TAILSCALE_HOSTNAME").trim();
  const bootstrapCommand =
    tailscaleHostname === ""
      ? `sudo ${remoteDeployPath("scripts/configure-tailscale.sh")} bootstrap`
      : `sudo env TAILSCALE_HOSTNAME=${shellQuote(tailscaleHostname)} ${remoteDeployPath("scripts/configure-tailscale.sh")} bootstrap`;
  run("ssh", [
    "-tt",
    target,
    `chmod +x ${remoteDeployPath("scripts/configure-tailscale.sh")} && ${bootstrapCommand}`,
  ]);
}

function tailscaleStatus() {
  run("ssh", [target, `sudo ${remoteDeployPath("scripts/configure-tailscale.sh")} status`]);
}

function syncMigrations() {
  run("rsync", ["-av", `${migrationsDir}/`, `${target}:${deployDir}/postgres/migrations/`]);
}

function buildAndSyncArenaAdmin() {
  // Empty base URL bakes relative fetch paths (e.g. "/api/v1/...") into the
  // static build, matching the same-origin Caddy reverse-proxy setup — the
  // deployed site must not point back at localhost.
  runBun(["install", "--frozen-lockfile"], { cwd: arenaAdminDir });
  runBun(["run", "build"], {
    cwd: arenaAdminDir,
    env: { ...process.env, PUBLIC_ARENA_API_BASE_URL: "" },
  });
  run("ssh", [target, `mkdir -p ${remoteDeployPath("arena-admin")}`]);
  run("rsync", ["-av", "--delete", `${arenaAdminDir}/build/`, `${target}:${deployDir}/arena-admin/`]);
}

function publicUp() {
  syncServerBundle();
  buildAndSyncArenaAdmin();
  const apiDomain = configuredApiDomain();
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${remoteDeployDir}`,
      "tmp=\"$(mktemp)\"",
      "if [ -f .env ]; then grep -v '^API_DOMAIN=' .env > \"$tmp\"; else : > \"$tmp\"; fi",
      `printf '%s\\n' ${shellQuote(`API_DOMAIN=${apiDomain}`)} >> "$tmp"`,
      "mv \"$tmp\" .env",
      "sudo ufw allow 80/tcp",
      "sudo ufw allow 443/tcp",
      "sudo ufw reload",
      "docker compose --profile public up -d --build --force-recreate caddy",
      "docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile",
    ].join(" && "),
  ]);
}

function publicDown() {
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${remoteDeployDir}`,
      "docker compose --profile public stop caddy || true",
      "docker compose --profile public rm -f caddy || true",
      "sudo ufw delete allow 80/tcp || true",
      "sudo ufw delete allow 443/tcp || true",
      "sudo ufw reload",
    ].join(" && "),
  ]);
}

function deployReceiverUp() {
  syncServerBundle();
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${remoteDeployDir}`,
      remoteGenerateLocalSecretsCommand(),
      "docker compose --profile public up -d --build --force-recreate deploy-receiver caddy",
    ].join(" && "),
  ]);
}

function deployAutomationUp() {
  const configuredPath = env("REEF_GITHUB_DEPLOY_PUBLIC_KEY_PATH").trim();
  if (configuredPath === "") {
    console.error("Missing REEF_GITHUB_DEPLOY_PUBLIC_KEY_PATH.");
    console.error("Point it at the dedicated GitHub Actions deploy public key.");
    process.exit(1);
  }
  const publicKeyPath = resolveUserPath(configuredPath);
  if (!existsSync(publicKeyPath)) {
    console.error(`GitHub deploy public key does not exist: ${publicKeyPath}`);
    process.exit(1);
  }
  const publicKey = readFileSync(publicKeyPath, "utf8").trim();
  if (!/^ssh-ed25519\s+[A-Za-z0-9+/]+={0,3}(?:\s+.*)?$/.test(publicKey)) {
    console.error("REEF_GITHUB_DEPLOY_PUBLIC_KEY_PATH must contain one ssh-ed25519 public key.");
    process.exit(1);
  }

  syncServerBundle();
  runWithInput(
    "ssh",
    [
      target,
      [
        "set -euo pipefail",
        `chmod +x ${remoteDeployPath("scripts")}/*.sh`,
        `cd ${remoteDeployDir}`,
        `REEF_DEPLOY_DIR=${remoteDeployDir} ./scripts/install-github-deploy-key.sh`,
      ].join(" && "),
    ],
    `${publicKey}\n`,
  );
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
        `mkdir -p ${remoteDeployPath("secrets")}`,
        "umask 077",
        `cat > ${remoteDeployPath("secrets/admin-auth.env")}`,
        `chmod 600 ${remoteDeployPath("secrets/admin-auth.env")}`,
        `cd ${remoteDeployDir}`,
        "chmod +x ./scripts/*.sh",
        remoteGenerateLocalSecretsCommand(),
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
      `mkdir -p ${remoteDeployPath("secrets")}`,
      `touch ${remoteDeployPath("secrets/admin-auth.env")}`,
      `tmp="$(mktemp)"`,
      `grep -v '^PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=' ${remoteDeployPath("secrets/admin-auth.env")} > "$tmp" || true`,
      `printf '%s\\n' ${shellQuote(`PLATFORM_LEGACY_MUTATION_ROUTES_ENABLED=${value}`)} >> "$tmp"`,
      `install -m 600 "$tmp" ${remoteDeployPath("secrets/admin-auth.env")}`,
      `rm -f "$tmp"`,
      `cd ${remoteDeployDir}`,
      "chmod +x ./scripts/*.sh",
      remoteGenerateLocalSecretsCommand(),
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
      `chmod +x ${remoteDeployPath("scripts")}/*.sh && cd ${remoteDeployDir} && RATE=${shellQuote(env("RATE", "50"))} DURATION=${shellQuote(env("DURATION", "30s"))} WORKERS=${shellQuote(env("WORKERS", "16"))} TRACE_CHECK_LIMIT=${shellQuote(env("TRACE_CHECK_LIMIT", "20"))} ./scripts/hosted-smoke.sh`,
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

function grantAdminActorRole() {
  const actorId = env("ADMIN_ACTOR_ID", "bot-submission-ci").trim();
  const roleId = env("ADMIN_ROLE_ID", "arena-operator").trim();
  const permissions = env("ADMIN_PERMISSION_CSV", "arena.admin").trim();
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/.test(actorId)) {
    console.error(`Invalid ADMIN_ACTOR_ID: ${actorId}`);
    process.exit(1);
  }
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,127}$/.test(roleId)) {
    console.error(`Invalid ADMIN_ROLE_ID: ${roleId}`);
    process.exit(1);
  }
  if (permissions === "") {
    console.error("ADMIN_PERMISSION_CSV must not be blank");
    process.exit(1);
  }

  configureAdminAuth(true, { legacyMutationRoutesEnabled: true });
  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `curl -fsS -X POST http://127.0.0.1:8080/auth/roles -H 'X-Reef-Internal-Route: true' -H 'content-type: application/json' -d '${JSON.stringify({ roleId, permissions })}'`,
      `curl -fsS -X POST http://127.0.0.1:8080/auth/actor-roles -H 'X-Reef-Internal-Route: true' -H 'content-type: application/json' -d '${JSON.stringify({ actorId, roleId })}'`,
    ].join(" && "),
  ]);
  configureAdminAuth(true, { legacyMutationRoutesEnabled: false });
  console.log(`granted ${permissions} to ${actorId} via role ${roleId}`);
}

function botConfigUpgrade() {
  console.log("Syncing server bundle and arena-admin assets...");
  syncServerBundle();
  buildAndSyncArenaAdmin();

  run("ssh", [
    target,
    [
      "set -euo pipefail",
      `cd ${shellQuote(deployDir)}`,
      "chmod +x ./scripts/*.sh",
      remoteGenerateLocalSecretsCommand(),
      "docker compose pull --ignore-pull-failures platform-runtime || true",
    ].join(" && "),
  ]);

  const baoToken = openBaoTokenForBootstrap();
  if (baoToken === "") {
    console.log("");
    console.log("Host assets are synced and platform-runtime secrets were regenerated/preserved.");
    console.log("BAO_TOKEN/REEF_OPENBAO_INIT_JSON was not provided, so OpenBao AppRole setup was not automated.");
    printBotConfigManualSteps();
    return;
  }

  const githubRepository = githubRepositoryFullName();
  console.log(`Using REEF_GITHUB_REPOSITORY=${githubRepository}`);
  console.log("Configuring OpenBao policy/AppRole and generating dedicated bot-config credentials...");
  runWithInput(
    "ssh",
    [target, "bash -s"],
    [
      "set -euo pipefail",
      `cd ${shellQuote(deployDir)}`,
      "chmod +x ./scripts/*.sh",
      `export REEF_GITHUB_REPOSITORY=${shellQuote(githubRepository)}`,
      `export BAO_TOKEN=${shellQuote(baoToken)}`,
      "./scripts/configure-openbao.sh >/tmp/reef-openbao-configure.log",
    ].join("\n"),
    ""
  );

  const approleOutput = captureWithInput(
    "ssh",
    [target, "bash -s"],
    [
      "set -euo pipefail",
      `cd ${shellQuote(deployDir)}`,
      `export BAO_TOKEN=${shellQuote(baoToken)}`,
      "./scripts/print-openbao-approle.sh reef-platform-admin-bot-config",
    ].join("\n")
  );
  run("ssh", [target, "rm -f /tmp/reef-openbao-configure.log"]);

  const roleId = approleOutput.match(/^BAO_ROLE_ID=(.+)$/m)?.[1]?.trim() || "";
  const secretId = approleOutput.match(/^BAO_SECRET_ID=(.+)$/m)?.[1]?.trim() || "";
  if (!roleId || !secretId) {
    console.error("Could not parse BAO_ROLE_ID/BAO_SECRET_ID from print-openbao-approle.sh output.");
    console.error("Output keys seen:");
    for (const line of approleOutput.split(/\r?\n/)) {
      const key = line.split("=")[0];
      if (key) console.error(`- ${key}`);
    }
    process.exit(1);
  }

  console.log("Persisting bot-config AppRole env vars and restarting platform-runtime...");
  runWithInput(
    "ssh",
    [target, "bash -s"],
    [
      "set -euo pipefail",
      `cd ${shellQuote(deployDir)}`,
      "test -f secrets/platform-runtime.env",
      "tmp=\"$(mktemp)\"",
      "grep -v '^BAO_BOT_CONFIG_\\(ROLE_ID\\|SECRET_ID\\)=' secrets/platform-runtime.env > \"$tmp\" || true",
      `printf '%s\\n' ${shellQuote(`BAO_BOT_CONFIG_ROLE_ID=${roleId}`)} ${shellQuote(`BAO_BOT_CONFIG_SECRET_ID=${secretId}`)} >> "$tmp"`,
      "install -m 600 \"$tmp\" secrets/platform-runtime.env",
      "rm -f \"$tmp\"",
      "docker compose up -d --force-recreate platform-runtime",
      "for i in $(seq 1 45); do curl -fsS http://127.0.0.1:8080/health >/dev/null && break; sleep 2; done",
      "curl -fsS http://127.0.0.1:8080/health >/dev/null",
      "docker compose exec -T platform-runtime env | grep '^BAO_BOT_CONFIG_ROLE_ID=' >/dev/null",
    ].join("\n"),
    ""
  );

  console.log("Admin OpenBao bot-config runtime wiring is installed.");
  console.log("");
  console.log("Remaining manual/verification steps:");
  console.log("- Keep the OpenBao root/admin token in the offline vault; do not leave it on the host.");
  console.log("- If public ingress is enabled, run: PUBLIC_INGRESS_EXPECTED=1 make hetzner-core ARGS=ops-check");
  console.log("- In Reef Admin, open a registered bot row, click config, save a JSON object, refresh, then run the bot preflight/run path.");
  console.log("- Confirm post-merge bot registry import/registration for newly accepted bots; this helper does not create registry records.");
}

function printBotConfigManualSteps() {
  console.log("");
  console.log("To finish the OpenBao bot-config setup, rerun with BAO_TOKEN or REEF_OPENBAO_INIT_JSON:");
  console.log("");
  console.log("  BAO_TOKEN=\"...\" make hetzner-core ARGS=bot-config-upgrade");
  console.log("  REEF_OPENBAO_INIT_JSON=/path/to/openbao-init.json make hetzner-core ARGS=bot-config-upgrade");
  console.log("");
  console.log("Or run these on the host manually:");
  console.log("");
  console.log("  cd /opt/reef");
  console.log("  REEF_GITHUB_REPOSITORY=<owner>/<repository> BAO_TOKEN=\"...\" ./scripts/configure-openbao.sh");
  console.log("  BAO_TOKEN=\"...\" ./scripts/print-openbao-approle.sh reef-platform-admin-bot-config");
  console.log("");
  console.log("Then append the printed values as BAO_BOT_CONFIG_ROLE_ID and");
  console.log("BAO_BOT_CONFIG_SECRET_ID in /opt/reef/secrets/platform-runtime.env and restart:");
  console.log("");
  console.log("  docker compose up -d --force-recreate platform-runtime");
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
  run("ssh", [target, `chmod +x ${remoteDeployPath("scripts")}/*.sh`]);

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
    `if [ -f ${remoteDeployPath("secrets/backup.env")} ]; then sed -n '/^\\(R2_ENDPOINT\\|R2_BUCKET\\|AWS_ACCESS_KEY_ID\\|AWS_SECRET_ACCESS_KEY\\|AWS_DEFAULT_REGION\\)=/p' ${remoteDeployPath("secrets/backup.env")}; fi`,
  ]);

  runWithInput(
    "ssh",
    [
      target,
      [
        "set -euo pipefail",
        `mkdir -p ${remoteDeployPath("secrets")}`,
        "umask 077",
        `cat > ${remoteDeployPath("secrets/backup.env")}`,
        `chmod 600 ${remoteDeployPath("secrets/backup.env")}`,
      ].join(" && "),
    ],
    backupEnvContent(recipient, existingR2Config)
  );

  run("ssh", [target, `cd ${remoteDeployDir} && ./scripts/backup-dbs.sh`]);
  const remoteArchive = capture("ssh", [
    target,
    `find ${remoteDeployPath("backups")} \\( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \\) -type f | sort | tail -1`,
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

  run("ssh", [target, `cd ${remoteDeployDir} && ./scripts/install-backup-timer.sh`]);

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
      `find ${remoteDeployPath("backups")} \\( -name 'reef-db-*.tar.age' -o -name 'reef-db-*.tar.gz.age' \\) -type f | sort | tail -1`,
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
  const publicSshResult = captureOptional("tofu", ["output", "-raw", "public_ssh_enabled"], { cwd: tofuDir });
  const publicSshExpected = publicSshResult.status === 0 ? publicSshResult.stdout.trim() === "true" : true;
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
    [22, publicSshExpected],
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
      `cd ${remoteDeployDir}`,
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
  const apiDomain = configuredApiDomain();
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

  const oauthInvalidCallback = captureOptional("curl", [
    "-sS",
    "-o",
    "/dev/null",
    "-w",
    "%{http_code}",
    `${baseUrl}/admin/auth/github/callback?code=invalid-smoke&state=invalid-smoke`,
  ]);
  if (oauthInvalidCallback.status !== 0 || oauthInvalidCallback.stdout.trim() !== "401") {
    failures.push(`invalid OAuth callback expected app 401, got ${oauthInvalidCallback.stdout || oauthInvalidCallback.stderr || `exit ${oauthInvalidCallback.status}`}`);
  }

  const publicLeaderboard = captureOptional("curl", [
    "-sS",
    "-o",
    "/dev/null",
    "-w",
    "%{http_code}",
    `${baseUrl}/api/v1/arena/leaderboard`,
  ]);
  if (publicLeaderboard.status !== 0 || publicLeaderboard.stdout.trim() !== "400") {
    failures.push(`public leaderboard expected app 400 for missing params, got ${publicLeaderboard.stdout || publicLeaderboard.stderr || `exit ${publicLeaderboard.status}`}`);
  }

  const caddyFallback = captureOptional("ssh", [
    target,
    `cd ${remoteDeployDir} && docker compose exec -T caddy cat /etc/caddy/Caddyfile`,
  ]);
  if (caddyFallback.status !== 0) {
    failures.push(`could not inspect Caddyfile inside container: ${caddyFallback.stderr || `exit ${caddyFallback.status}`}`);
  } else if (!caddyFallback.stdout.includes("try_files {path} {path}/index.html {path}.html /index.html")) {
    failures.push("Caddy container does not see the clean-url admin fallback with {path}.html");
  }

  const legacyRoute = captureOptional("ssh", [
    target,
    [
      `cd ${remoteDeployDir}`,
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
  console.log("invalid OAuth callback app 401 ok");
  console.log("public leaderboard reaches app ok");
  console.log("Caddy clean-url fallback ok");
  console.log("legacy mutation route disabled ok");
}

switch (command) {
  case "sync":
    syncServerBundle();
    break;
  case "tailscale-bootstrap":
    tailscaleBootstrap();
    break;
  case "tailscale-status":
    tailscaleStatus();
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
        `cd ${remoteDeployDir}`,
        "tmp=\"$(mktemp)\"",
        "if [ -f .env ]; then grep -v '^REEF_\\(PLATFORM_RUNTIME\\|MATCHING_ENGINE\\|SIMULATOR\\)_IMAGE=' .env > \"$tmp\"; else : > \"$tmp\"; fi",
        "printf '%s\\n' REEF_PLATFORM_RUNTIME_IMAGE=reef-platform-runtime:local REEF_MATCHING_ENGINE_IMAGE=reef-matching-engine:local REEF_SIMULATOR_IMAGE=reef-simulator:local >> \"$tmp\"",
        "mv \"$tmp\" .env",
      ].join(" && "),
    ]);
    break;
  case "restart":
    run("ssh", [target, `${remoteCompose} pull --ignore-pull-failures && ${remoteCompose} up -d --build --remove-orphans`]);
    break;
  case "stream-ack":
    run("ssh", [target, `chmod +x ${remoteDeployPath("scripts")}/*.sh && cd ${remoteDeployDir} && ./scripts/start-stream-ack.sh`]);
    break;
  case "backup-bootstrap":
    backupBootstrap();
    break;
  case "backup-timer":
    run("ssh", [target, `chmod +x ${remoteDeployPath("scripts")}/*.sh && cd ${remoteDeployDir} && ./scripts/install-backup-timer.sh`]);
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
  case "deploy-receiver-up":
    deployReceiverUp();
    break;
  case "deploy-automation-up":
    deployAutomationUp();
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
  case "admin-actor-role-grant":
    grantAdminActorRole();
    break;
  case "admin-auth-smoke":
    adminAuthSmoke();
    break;
  case "bot-config-upgrade":
    botConfigUpgrade();
    break;
  case "verify":
    run("ssh", [target, `cd ${remoteDeployDir} && ./scripts/verify-runtime.sh`]);
    break;
  case "soak":
    run("ssh", [
      target,
      `chmod +x ${remoteDeployPath("scripts")}/*.sh && cd ${remoteDeployDir} && RATE=${shellQuote(env("RATE", "1000"))} DURATION=${shellQuote(env("DURATION", "1m"))} WORKERS=${shellQuote(env("WORKERS", "128"))} MODE=${shellQuote(env("MODE", "strict-lifecycle"))} TRACE_CHECK_LIMIT=${shellQuote(env("TRACE_CHECK_LIMIT", "100"))} ./scripts/run-soak.sh`,
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
        `chmod +x ${remoteDeployPath("scripts")}/*.sh ${remoteDeployPath("postgres/init")}/*.sh ${remoteDeployPath("postgres-admin/init")}/*.sh ${remoteDeployPath("postgres-analytics/init")}/*.sh`,
        `cd ${remoteDeployDir}`,
        remoteGenerateLocalSecretsCommand(),
        "docker compose pull --ignore-pull-failures",
        "docker compose up -d postgres postgres-admin postgres-analytics openbao matching-engine",
        "./scripts/apply-migrations.sh",
        "REEF_MIGRATION_DOMAINS='admin arena' REEF_APP_USER=admin_app REEF_POSTGRES_SERVICE=postgres-admin REEF_POSTGRES_DB=admin ./scripts/apply-migrations.sh",
        "REEF_MIGRATION_DOMAINS=analytics REEF_APP_USER=analytics_app REEF_POSTGRES_SERVICE=postgres-analytics REEF_POSTGRES_DB=analytics ./scripts/apply-migrations.sh",
        "docker compose up -d --build --remove-orphans",
        "./scripts/verify-runtime.sh",
      ].join(" && "),
    ]);
    break;
  default:
    console.error(`Unknown command: ${command}`);
    process.exit(1);
}
