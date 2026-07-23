import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import { assertValidBotId, assertValidOpenBaoPathSegment } from "./lib/bot-submission-contract.mjs";

loadDotEnv();

const [, , botId, flow, submitterIdentity] = process.argv;
const validFlows = ["add", "update", "remove"];
if (!botId || !validFlows.includes(flow) || !submitterIdentity) {
  console.error(
    "usage: node scripts/dev/bot-submission-provision-openbao.mjs <botId> <add|update|remove> <submitterIdentity>",
  );
  process.exit(1);
}
try {
  assertValidBotId(botId);
  assertValidOpenBaoPathSegment(submitterIdentity, "submitterIdentity");
} catch (error) {
  console.error(`bot-submission-provision-openbao: user-fixable failure: ${error.message}`);
  process.exit(1);
}

const mode = provisionMode();
const slicePath = `secret/bots/${submitterIdentity}/${botId}`;
const action = flow === "remove" ? "revoke/delete" : flow === "update" ? "verify/reuse" : "provision";

if (mode === "dry-run") {
  console.log(`bot-submission-provision-openbao: dry-run ok, would ${action} ${slicePath} (flow=${flow})`);
  process.exit(0);
}

try {
  const result = await provisionViaAdminApi({ botId, flow, submitterIdentity });
  console.log(
    `bot-submission-provision-openbao: real ok, ${action} ${slicePath} (flow=${flow}, status=${result.status ?? "ok"})`,
  );
} catch (error) {
  const classified = classifyError(error);
  console.error(`bot-submission-provision-openbao: ${classified.failureClass} failure: ${classified.message}`);
  process.exit(1);
}

function provisionMode() {
  const explicitMode = env("BOT_SUBMISSION_OPENBAO_MODE", "").toLowerCase();
  if (explicitMode === "real" || explicitMode === "dry-run") {
    return explicitMode;
  }
  const dryRun = env("BOT_SUBMISSION_OPENBAO_DRY_RUN", "");
  if (dryRun === "0" || dryRun.toLowerCase() === "false") {
    return "real";
  }
  return "dry-run";
}

async function provisionViaAdminApi(request) {
  const adminApiUrl = env("ARENA_ADMIN_API_URL", "").replace(/\/+$/, "");
  if (!adminApiUrl) {
    throw platformFailure("ARENA_ADMIN_API_URL is required for real OpenBao provisioning");
  }
  const adminApiToken = env("ARENA_ADMIN_API_TOKEN", "");
  if (!adminApiToken) {
    throw platformFailure("ARENA_ADMIN_API_TOKEN is required for scoped Admin API auth");
  }
  const githubOidcToken = await githubOidcTokenForProvisioning();
  const submissionContext = trustedSubmissionContext();
  const actorId = env("ADMIN_ACTOR_ID", "bot-submission-ci");
  const response = await fetch(`${adminApiUrl}/admin/v1/arena/bots/openbao-provision`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "X-Reef-Actor-Id": actorId,
      authorization: `Bearer ${adminApiToken}`,
    },
    body: JSON.stringify({
      githubOidcToken,
      submitterIdentity: request.submitterIdentity,
      botId: request.botId,
      flow: request.flow,
      ...submissionContext,
    }),
  });
  const payload = await responsePayload(response);
  if (response.status >= 200 && response.status < 300) {
    return payload ?? {};
  }
  throw httpFailure(response.status, payload);
}

function trustedSubmissionContext() {
  const repository = env("SUBMISSION_REPOSITORY", "");
  const pullRequestNumberText = env("SUBMISSION_PR_NUMBER", "");
  const headSha = env("SUBMISSION_HEAD_SHA", "").toLowerCase();
  if (!repository && !pullRequestNumberText && !headSha) {
    return {};
  }
  const pullRequestNumber = Number(pullRequestNumberText);
  if (
    !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository) ||
    !Number.isSafeInteger(pullRequestNumber) ||
    pullRequestNumber <= 0 ||
    !/^[0-9a-f]{40,64}$/.test(headSha)
  ) {
    throw platformFailure("complete trusted submission repository, PR number, and head SHA are required");
  }
  return { repository, pullRequestNumber, headSha };
}

async function githubOidcTokenForProvisioning() {
  const directToken = env("GITHUB_OIDC_TOKEN", "");
  if (directToken) {
    return directToken;
  }
  const requestUrl = env("ACTIONS_ID_TOKEN_REQUEST_URL", "");
  const requestToken = env("ACTIONS_ID_TOKEN_REQUEST_TOKEN", "");
  if (!requestUrl || !requestToken) {
    throw platformFailure(
      "GitHub OIDC token is required for real OpenBao provisioning (set GITHUB_OIDC_TOKEN locally or enable id-token: write in GitHub Actions)",
    );
  }
  const audience = encodeURIComponent(env("BOT_SUBMISSION_OPENBAO_OIDC_AUDIENCE", "reef-bot-submission-ci"));
  const separator = requestUrl.includes("?") ? "&" : "?";
  const response = await fetch(`${requestUrl}${separator}audience=${audience}`, {
    headers: {
      authorization: `Bearer ${requestToken}`,
    },
  });
  const payload = await responsePayload(response);
  if (response.status < 200 || response.status >= 300) {
    throw platformFailure(`GitHub OIDC token request failed (${response.status}): ${payloadMessage(payload)}`);
  }
  if (!payload?.value) {
    throw platformFailure("GitHub OIDC token response missing value");
  }
  return payload.value;
}

async function responsePayload(response) {
  const raw = await response.text();
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return { error: raw };
  }
}

function httpFailure(status, payload) {
  const declared = normalizeFailureClass(payload?.failureClass ?? payload?.classification);
  if (declared) {
    return classifiedFailure(declared, `POST /admin/v1/arena/bots/openbao-provision failed (${status}): ${payloadMessage(payload)}`);
  }
  if (status === 400 || status === 409) {
    return userFailure(`POST /admin/v1/arena/bots/openbao-provision failed (${status}): ${payloadMessage(payload)}`);
  }
  return platformFailure(`POST /admin/v1/arena/bots/openbao-provision failed (${status}): ${payloadMessage(payload)}`);
}

function classifyError(error) {
  if (error?.failureClass) {
    return { failureClass: error.failureClass, message: error.message };
  }
  return {
    failureClass: "platform-fixable",
    message: error?.message ?? "unexpected OpenBao provisioning client failure",
  };
}

function payloadMessage(payload) {
  if (!payload) {
    return "empty response";
  }
  if (typeof payload.error === "string") {
    return payload.error;
  }
  if (typeof payload.message === "string") {
    return payload.message;
  }
  return JSON.stringify(payload);
}

function normalizeFailureClass(value) {
  if (value === "user-fixable" || value === "platform-fixable") {
    return value;
  }
  return "";
}

function userFailure(message) {
  return classifiedFailure("user-fixable", message);
}

function platformFailure(message) {
  return classifiedFailure("platform-fixable", message);
}

function classifiedFailure(failureClass, message) {
  const error = new Error(message);
  error.failureClass = failureClass;
  return error;
}
