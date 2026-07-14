import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const repo = requiredEnv("GITHUB_REPOSITORY");
const token = requiredEnv("GITHUB_TOKEN");
const sha = requiredEnv("STATUS_SHA");
const state = requiredEnv("STATUS_STATE");
const context = env("STATUS_CONTEXT", "registry-diff-and-provision");
const description = env("STATUS_DESCRIPTION", "");
const targetUrl = env("STATUS_TARGET_URL", "");
const apiUrl = env("GITHUB_API_URL", "https://api.github.com").replace(/\/+$/, "");

if (!["error", "failure", "pending", "success"].includes(state)) {
  throw new Error(`STATUS_STATE must be one of error, failure, pending, success: ${state}`);
}
if (!/^[A-Za-z0-9_.:/ -]{1,100}$/.test(context)) {
  throw new Error(`STATUS_CONTEXT contains unsupported characters: ${context}`);
}

const body = {
  state,
  context,
  ...(description ? { description: description.slice(0, 140) } : {}),
  ...(targetUrl ? { target_url: targetUrl } : {}),
};

if (env("BOT_SUBMISSION_COMMIT_STATUS_DRY_RUN", "") === "1") {
  console.log(JSON.stringify({ repo, sha, ...body }, null, 2));
  process.exit(0);
}

const response = await fetch(`${apiUrl}/repos/${repo}/statuses/${encodeURIComponent(sha)}`, {
  method: "POST",
  headers: {
    accept: "application/vnd.github+json",
    authorization: `Bearer ${token}`,
    "content-type": "application/json",
    "x-github-api-version": "2022-11-28",
  },
  body: JSON.stringify(body),
});
const text = await response.text();
if (response.status < 200 || response.status >= 300) {
  throw new Error(`GitHub commit status update failed (${response.status}): ${text}`);
}

console.log(`bot-submission-commit-status: ${context}=${state} sha=${sha}`);

function requiredEnv(name) {
  const value = env(name, "");
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}
