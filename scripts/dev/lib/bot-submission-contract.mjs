import { basename } from "node:path";

const BOT_ID_REGEX = /^[a-z0-9][a-z0-9-]{1,63}$/;
const OPENBAO_PATH_SEGMENT_REGEX = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export function assertValidBotId(value, label = "botId") {
  if (typeof value !== "string" || !BOT_ID_REGEX.test(value)) {
    throw new Error(`${label} must be lowercase alphanumeric/hyphen, 2-64 chars, starting with a letter or digit`);
  }
  return value;
}

export function assertValidOpenBaoPathSegment(value, label = "path segment") {
  if (typeof value !== "string" || !OPENBAO_PATH_SEGMENT_REGEX.test(value)) {
    throw new Error(`${label} must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}`);
  }
  return value;
}

export function assertManifestPathMatchesBotId(manifestPath, botId) {
  assertValidBotId(botId);
  const parts = normalizedPathParts(manifestPath);
  const fileName = parts.at(-1);
  const directoryBotId = parts.at(-2);
  const botsDirectory = parts.at(-3);
  if (fileName !== "bot.json" || botsDirectory !== "bots" || !directoryBotId) {
    throw new Error(`manifest path must match bots/<bot-id>/bot.json: ${manifestPath}`);
  }
  if (directoryBotId !== botId) {
    throw new Error(`manifest botId "${botId}" must match manifest directory "${directoryBotId}"`);
  }
}

export function assertSafeBotSourceFileName(fileName) {
  if (typeof fileName !== "string" || fileName.trim() === "") {
    throw new Error("manifest.fileName is required");
  }
  if (fileName !== basename(fileName) || fileName.includes("/") || fileName.includes("\\")) {
    throw new Error(`manifest.fileName must name a TypeScript file alongside bot.json, not a path: ${fileName}`);
  }
  if (!fileName.endsWith(".ts")) {
    throw new Error(`manifest.fileName must be a TypeScript entry file: ${fileName}`);
  }
}

function normalizedPathParts(path) {
  return String(path)
    .replaceAll("\\", "/")
    .split("/")
    .filter(Boolean);
}
