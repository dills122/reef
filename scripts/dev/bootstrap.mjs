import { copyFileSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { run } from "./lib/dev-utils.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const dryRun = process.argv.slice(2).includes("--dry-run");

await ensureLocalEnv();
await install("root Bun dependencies", "bun", ["install", "--frozen-lockfile"], repoRoot);
await install(
  "Arena admin dependencies",
  "bun",
  ["install", "--frozen-lockfile"],
  resolve(repoRoot, "apps/arena-admin"),
);
await install(
  "docs-site dependencies",
  "npm",
  ["ci"],
  resolve(repoRoot, "apps/docs-site"),
);

console.log(dryRun ? "bootstrap plan ok" : "developer dependencies installed");
console.log("next: make dev-doctor ARGS=--full");

async function ensureLocalEnv() {
  const target = resolve(repoRoot, ".env");
  if (existsSync(target)) {
    console.log("keep existing .env");
    return;
  }

  console.log("create .env from .env.example");
  if (!dryRun) {
    copyFileSync(resolve(repoRoot, ".env.example"), target);
  }
}

async function install(label, command, args, cwd) {
  console.log(`${label}: ${command} ${args.join(" ")}`);
  if (!dryRun) {
    await run(command, args, { cwd });
  }
}
