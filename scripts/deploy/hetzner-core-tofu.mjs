#!/usr/bin/env node
import { readFileSync, existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const tofuDir = resolve(repoRoot, "infra/hetzner-core/tofu");

function parseDotEnv(content) {
  const values = {};
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const match = /^([A-Za-z_][A-Za-z0-9_]*)=(.*)$/.exec(line);
    if (!match) continue;
    const [, key, rawValue] = match;
    values[key] = stripQuotes(rawValue.trim());
  }
  return values;
}

function stripQuotes(value) {
  if (
    (value.startsWith('"') && value.endsWith('"')) ||
    (value.startsWith("'") && value.endsWith("'"))
  ) {
    return value.slice(1, -1);
  }
  return value;
}

function loadEnvFile(path) {
  if (!existsSync(path)) return;
  const values = parseDotEnv(readFileSync(path, "utf8"));
  for (const [key, value] of Object.entries(values)) {
    if (!(key in process.env)) {
      process.env[key] = value;
    }
  }
}

loadEnvFile(resolve(repoRoot, ".env"));
loadEnvFile(resolve(tofuDir, ".env"));

if (!process.env.HCLOUD_TOKEN && process.env.HETZNER_TOKEN) {
  process.env.HCLOUD_TOKEN = process.env.HETZNER_TOKEN;
}

const args = process.argv.slice(2);
if (args.length === 0 || args[0] === "help" || args[0] === "--help" || args[0] === "-h") {
  console.log(`Usage: scripts/deploy/hetzner-core-tofu.mjs <tofu args...>

Loads .env from the repository root and infra/hetzner-core/tofu, then maps
HETZNER_TOKEN to HCLOUD_TOKEN for the Hetzner provider.

Examples:
  make hetzner-core-tofu ARGS=init
  make hetzner-core-tofu ARGS="plan -out=tfplan"
  make hetzner-core-tofu ARGS="apply tfplan"
  make hetzner-core-tofu ARGS="output core_ipv4"
`);
  process.exit(0);
}

const result = spawnSync("tofu", args, {
  cwd: tofuDir,
  stdio: "inherit",
  env: process.env,
});

if (result.error) {
  console.error(`Failed to run "tofu": ${result.error.message}`);
  console.error('Make sure OpenTofu ("tofu") is installed and on PATH.');
  process.exit(1);
}

process.exit(result.status ?? 1);

