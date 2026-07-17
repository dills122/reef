#!/usr/bin/env node
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const buildDir = new URL("../build", import.meta.url).pathname;
const forbidden = [
  "PUBLIC_ARENA_LOCAL_DEV_FAKE_ADMIN",
  "PUBLIC_ARENA_LOCAL_DEV_FIXTURES",
  "local-dev-admin",
  "arena-admin.local",
  "local fixture mode",
  "dsteele-spread-maker",
  "reef-reviewer",
  "latency-arb-fixture",
];

if (!existsSync(buildDir)) {
  throw new Error(`missing build output: ${buildDir}; run npm run build first`);
}

const hits = [];
for (const file of walk(buildDir)) {
  if (!isTextLike(file)) continue;
  const text = readFileSync(file, "utf8");
  for (const needle of forbidden) {
    if (text.includes(needle)) {
      hits.push(`${file}: ${needle}`);
    }
  }
}

if (hits.length > 0) {
  console.error("production build contains local-only fixture markers:");
  for (const hit of hits) console.error(`  ${hit}`);
  process.exit(1);
}

console.log("production build fixture guard passed");

function* walk(dir) {
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    const stat = statSync(path);
    if (stat.isDirectory()) {
      yield* walk(path);
    } else if (stat.isFile()) {
      yield path;
    }
  }
}

function isTextLike(file) {
  return /\.(html|js|css|json|txt|svg|map)$/i.test(file);
}
