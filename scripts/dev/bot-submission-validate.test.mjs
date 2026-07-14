import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const repoRoot = new URL("../../", import.meta.url).pathname;
const tempRoot = mkdtempSync(join(tmpdir(), "reef-bot-submission-validate-"));

const validManifest = manifest("sample-bot");
const validPath = writeManifest("sample-bot", validManifest);
const valid = await runValidator(validPath);
assert.equal(valid.status, 0, valid.stderr);
assert.match(valid.stdout, /ok \(botId=sample-bot\)/);

const mismatchPath = writeManifest("branch-bot", manifest("manifest-bot"));
const mismatch = await runValidator(mismatchPath);
assert.equal(mismatch.status, 1);
assert.match(mismatch.stderr, /must match manifest directory "branch-bot"/);

const escapedSourcePath = writeManifest("escape-bot", manifest("escape-bot", "../index.ts"));
const escapedSource = await runValidator(escapedSourcePath);
assert.equal(escapedSource.status, 1);
assert.match(escapedSource.stderr, /manifest\.fileName must name a TypeScript file alongside bot\.json/);

console.log("bot submission manifest validation checks passed");

function manifest(botId, fileName = "index.ts") {
  return {
    botId,
    fileName,
    metadata: {
      name: "Sample Bot",
      publisher: "octocat",
      email: "octocat@example.com",
    },
  };
}

function writeManifest(directoryBotId, value) {
  const botDir = join(tempRoot, "bots", directoryBotId);
  mkdirSync(botDir, { recursive: true });
  const manifestPath = join(botDir, "bot.json");
  writeFileSync(manifestPath, `${JSON.stringify(value, null, 2)}\n`);
  return manifestPath;
}

function runValidator(path) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/dev/bot-submission-validate.mjs", path], {
      cwd: repoRoot,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk.toString();
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk.toString();
    });
    child.on("error", reject);
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}
