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
const retryDelaysMs = parseRetryDelays(env("GITHUB_STATUS_RETRY_DELAYS_MS", "1000,3000,7000"));

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

await postStatusWithRetry();

console.log(`bot-submission-commit-status: ${context}=${state} sha=${sha}`);

async function postStatusWithRetry() {
  const url = `${apiUrl}/repos/${repo}/statuses/${encodeURIComponent(sha)}`;
  let lastError = null;

  for (let attempt = 0; attempt <= retryDelaysMs.length; attempt += 1) {
    try {
      const response = await fetch(url, {
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
      if (response.status >= 200 && response.status < 300) return;
      lastError = new Error(`GitHub commit status update failed (${response.status}): ${truncate(text)}`);
      if (!isTransientStatus(response.status) || attempt === retryDelaysMs.length) break;
      await sleep(retryDelayMs(response, attempt));
    } catch (error) {
      lastError = error;
      if (attempt === retryDelaysMs.length) break;
      await sleep(retryDelaysMs[attempt]);
    }
  }

  throw lastError;
}

function isTransientStatus(status) {
  return status === 408 || status === 409 || status === 425 || status === 429 || status >= 500;
}

function retryDelayMs(response, attempt) {
  const retryAfter = response.headers.get("retry-after");
  if (retryAfter) {
    const seconds = Number(retryAfter);
    if (Number.isFinite(seconds) && seconds >= 0) return seconds * 1000;
    const dateMs = Date.parse(retryAfter);
    if (Number.isFinite(dateMs)) return Math.max(0, dateMs - Date.now());
  }
  return retryDelaysMs[attempt];
}

function parseRetryDelays(value) {
  return value
    .split(",")
    .map((part) => Number(part.trim()))
    .filter((delay) => Number.isInteger(delay) && delay >= 0);
}

function truncate(value, maxLength = 1000) {
  return value.length <= maxLength ? value : `${value.slice(0, maxLength)}...`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function requiredEnv(name) {
  const value = env(name, "");
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}
