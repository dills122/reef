import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import { assertValidBotId } from "./lib/bot-submission-contract.mjs";

loadDotEnv();

const input = {
  repository: env("GITHUB_REPOSITORY", ""),
  pullRequestNumber: Number(env("PR_NUMBER", "")),
  botId: env("BOT_NAME", ""),
  headRepository: env("HEAD_REPOSITORY", ""),
  headOwnerLogin: env("HEAD_OWNER_LOGIN", ""),
  githubUserId: Number(env("SUBMITTER_GITHUB_ID", "")),
  githubLogin: env("SUBMITTER_GITHUB_LOGIN", ""),
  headSha: env("HEAD_SHA", ""),
};

try {
  validateInput(input);
} catch (error) {
  fail("user-fixable", error.message);
}

if (mode() === "dry-run") {
  emit({
    status: "ok",
    mode: "dry-run",
    admission: { ...input, state: "pending_invite_review" },
  });
  process.exit(0);
}

try {
  const admission = await recordAdmission(input);
  emit({ status: "ok", mode: "real", admission });
} catch (error) {
  fail(error.failureClass ?? "platform-fixable", error.message);
}

function mode() {
  const explicit = env("BOT_SUBMISSION_ADMISSION_MODE", "").toLowerCase();
  if (explicit === "real" || explicit === "dry-run") return explicit;
  return "dry-run";
}

async function recordAdmission(payload) {
  const adminApiUrl = env("ARENA_ADMIN_API_URL", "").replace(/\/+$/, "");
  const adminApiToken = env("ARENA_ADMIN_API_TOKEN", "");
  if (!adminApiUrl) throw classified("platform-fixable", "ARENA_ADMIN_API_URL is required for real admission recording");
  if (!adminApiToken) throw classified("platform-fixable", "ARENA_ADMIN_API_TOKEN is required for scoped Admin API auth");
  const response = await fetch(`${adminApiUrl}/admin/v1/arena/submission-admissions`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${adminApiToken}`,
      "X-Reef-Actor-Id": env("ADMIN_ACTOR_ID", "bot-submission-ci"),
    },
    body: JSON.stringify(payload),
  });
  const body = await responsePayload(response);
  if (response.ok && body?.admission?.state) return body.admission;
  const message = body?.error ?? body?.message ?? "empty response";
  throw classified(response.status === 400 || response.status === 409 ? "user-fixable" : "platform-fixable", `submission admission request failed (${response.status}): ${message}`);
}

function validateInput(value) {
  assertValidBotId(value.botId);
  if (!repositoryPattern().test(value.repository)) throw new Error("GITHUB_REPOSITORY must be owner/repository");
  if (!repositoryPattern().test(value.headRepository)) throw new Error("HEAD_REPOSITORY must be owner/repository");
  if (!Number.isSafeInteger(value.pullRequestNumber) || value.pullRequestNumber <= 0) throw new Error("PR_NUMBER must be a positive integer");
  if (!Number.isSafeInteger(value.githubUserId) || value.githubUserId <= 0) throw new Error("SUBMITTER_GITHUB_ID must be a positive integer");
  if (!githubLoginPattern().test(value.headOwnerLogin) || !githubLoginPattern().test(value.githubLogin)) throw new Error("GitHub login is invalid");
  if (!/^[0-9a-f]{40,64}$/i.test(value.headSha)) throw new Error("HEAD_SHA must be a Git commit SHA");
}

function repositoryPattern() { return /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/; }
function githubLoginPattern() { return /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/; }

async function responsePayload(response) {
  const raw = await response.text();
  if (!raw) return null;
  try { return JSON.parse(raw); } catch { return { error: raw }; }
}

function emit(value) { console.log(JSON.stringify(value)); }
function classified(failureClass, message) { const error = new Error(message); error.failureClass = failureClass; return error; }
function fail(failureClass, message) { console.error(`bot-submission-record-admission: ${failureClass} failure: ${message}`); process.exit(1); }
