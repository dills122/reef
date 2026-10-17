import assert from "node:assert/strict";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const botsRoot = join(repoRoot, "bots");

// `bots/*/bot.json` metadata and the `static metadata` in the bot source are
// maintained by hand in two places; this test fails when they drift.
const botDirs = readdirSync(botsRoot, { withFileTypes: true })
  .filter((entry) => entry.isDirectory() && existsSync(join(botsRoot, entry.name, "bot.json")))
  .map((entry) => entry.name)
  .sort();
assert.ok(botDirs.length > 0, "expected at least one bots/*/bot.json manifest");

for (const dir of botDirs) {
  const manifest = JSON.parse(readFileSync(join(botsRoot, dir, "bot.json"), "utf8"));
  const source = readFileSync(join(botsRoot, dir, manifest.fileName), "utf8");
  // `@reef/bot-sdk` does not resolve outside the hosted bundler, so read the
  // literal rather than importing the class.
  const match = source.match(/static\s+(?:override\s+)?metadata\s*=\s*(\{[\s\S]*?\n  \})\s*as const;/);
  assert.ok(match, `${dir}: could not find static metadata literal in ${manifest.fileName}`);
  const classMetadata = new Function(`return (${match[1]});`)();
  assert.deepEqual(
    classMetadata,
    manifest.metadata,
    `${dir}: bot.json metadata differs from class static metadata`,
  );
}

console.log(`bot manifest metadata sync checks passed (${botDirs.length} bots)`);
