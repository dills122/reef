import { readFileSync } from "node:fs";
import {
  assertManifestPathMatchesBotId,
  assertSafeBotSourceFileName,
  assertValidBotId,
} from "./lib/bot-submission-contract.mjs";

const BASIC_EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const REQUIRED_FIELDS = ["botId", "fileName", "metadata"];
const REQUIRED_METADATA_FIELDS = ["name", "publisher", "email"];

const [, , manifestPath] = process.argv;
if (!manifestPath) {
  console.error("usage: node scripts/dev/bot-submission-validate.mjs <bots/*/bot.json>");
  process.exit(1);
}

function fail(message) {
  console.error(`bot-submission-validate: ${message}`);
  process.exit(1);
}

let manifest;
try {
  manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
} catch (error) {
  fail(`cannot read/parse manifest at ${manifestPath}: ${error.message}`);
}

for (const field of REQUIRED_FIELDS) {
  if (!(field in manifest)) {
    fail(`manifest missing required field "${field}"`);
  }
}

if (!manifest.metadata || typeof manifest.metadata !== "object" || Array.isArray(manifest.metadata)) {
  fail("manifest.metadata must be an object");
}

for (const field of REQUIRED_METADATA_FIELDS) {
  if (!(field in manifest.metadata)) {
    fail(`manifest.metadata missing required field "${field}"`);
  }
}

if (!BASIC_EMAIL_REGEX.test(String(manifest.metadata.email))) {
  fail(`manifest.metadata.email is not a valid email: ${manifest.metadata.email}`);
}

try {
  assertValidBotId(manifest.botId, "manifest.botId");
  assertManifestPathMatchesBotId(manifestPath, manifest.botId);
  assertSafeBotSourceFileName(manifest.fileName);
} catch (error) {
  fail(error.message);
}

console.log(`bot-submission-validate: ok (botId=${manifest.botId})`);
