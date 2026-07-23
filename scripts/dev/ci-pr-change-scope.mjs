import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const BOT_BRANCH_PATTERN = /^bots\/(add|update|remove)\/([a-z0-9][a-z0-9._-]{2,63})$/;

export function classifyPullRequestScope({ headRef, pages, expectedChangedFiles }) {
  const branch = String(headRef ?? "").trim();
  const match = BOT_BRANCH_PATTERN.exec(branch);
  if (!match) {
    return fullCi("branch_not_bot_submission");
  }

  const [, flow, botName] = match;
  const records = flattenFilePages(pages);
  if (records.length === 0) {
    return fullCi("no_changed_files");
  }
  if (!Number.isSafeInteger(expectedChangedFiles) || expectedChangedFiles <= 0) {
    return fullCi("invalid_changed_file_count");
  }
  if (records.length !== expectedChangedFiles) {
    return fullCi("incomplete_changed_file_list");
  }

  const allowedPrefix = `bots/${botName}/`;
  const currentPaths = new Set();
  for (const record of records) {
    if (!record || typeof record !== "object" || typeof record.filename !== "string") {
      return fullCi("invalid_changed_file_metadata");
    }
    if (currentPaths.has(record.filename)) {
      return fullCi("duplicate_changed_file_metadata");
    }
    currentPaths.add(record.filename);
    const paths = [record.filename];
    if (record.previous_filename !== undefined && record.previous_filename !== null) {
      if (typeof record.previous_filename !== "string") {
        return fullCi("invalid_changed_file_metadata");
      }
      paths.push(record.previous_filename);
    }
    if (paths.some((path) => !safeRepositoryPath(path) || !path.startsWith(allowedPrefix))) {
      return fullCi("path_outside_submitted_bot");
    }
  }

  return {
    botOnly: true,
    runFullCi: false,
    flow,
    botName,
    reason: "exact_bot_directory_only",
  };
}

function flattenFilePages(pages) {
  if (!Array.isArray(pages)) return [];
  return pages.flatMap((page) => (Array.isArray(page) ? page : [page]));
}

function safeRepositoryPath(path) {
  if (!path || path.includes("\0") || path.includes("\r") || path.includes("\n")) return false;
  return path.split("/").every((segment) => segment !== "" && segment !== "." && segment !== "..");
}

function fullCi(reason) {
  return {
    botOnly: false,
    runFullCi: true,
    flow: "",
    botName: "",
    reason,
  };
}

async function main() {
  const [, , filesPath] = process.argv;
  if (!filesPath) {
    throw new Error("usage: node scripts/dev/ci-pr-change-scope.mjs <github-pr-files.json>");
  }
  const pages = JSON.parse(await readFile(filesPath, "utf8"));
  const expectedChangedFiles = Number(process.env.EXPECTED_CHANGED_FILES);
  const result = classifyPullRequestScope({
    headRef: process.env.HEAD_REF,
    pages,
    expectedChangedFiles,
  });
  process.stdout.write([
    `bot-only=${result.botOnly}`,
    `run-full-ci=${result.runFullCi}`,
    `flow=${result.flow}`,
    `bot-name=${result.botName}`,
    `reason=${result.reason}`,
    "",
  ].join("\n"));
}

const isMain = process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  main().catch((error) => {
    console.error(`ci-pr-change-scope: ${error.message}`);
    process.exitCode = 1;
  });
}
