import { env, loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();

const repo = requiredEnv("GITHUB_REPOSITORY");
const prNumber = requiredEnv("PR_NUMBER");
const token = requiredEnv("GITHUB_TOKEN");
const botId = requiredEnv("BOT_ID");
const flow = requiredEnv("PROVISION_FLOW");
const submitterIdentity = requiredEnv("SUBMITTER_IDENTITY");
const result = requiredEnv("PROVISION_RESULT");
const slicePath = env("OPENBAO_SLICE_PATH", `secret/bots/${submitterIdentity}/${botId}`);
const runUrl = env("GITHUB_SERVER_URL", "https://github.com") + `/${repo}/actions/runs/${env("GITHUB_RUN_ID", "")}`;
const provisionMessage = sanitizeMessage(env("PROVISION_MESSAGE", ""));
const apiUrl = env("GITHUB_API_URL", "https://api.github.com").replace(/\/+$/, "");
const marker = `<!-- reef-bot-submission-openbao:${botId} -->`;

const body = buildCommentBody();

if (env("BOT_SUBMISSION_PR_COMMENT_DRY_RUN", "") === "1") {
  console.log(body);
  process.exit(0);
}

try {
  const existing = await findExistingComment();
  if (existing) {
    await githubRequest("PATCH", `/repos/${repo}/issues/comments/${existing.id}`, { body });
    console.log(`bot-submission-pr-comment: updated PR #${prNumber} OpenBao comment`);
  } else {
    await githubRequest("POST", `/repos/${repo}/issues/${prNumber}/comments`, { body });
    console.log(`bot-submission-pr-comment: created PR #${prNumber} OpenBao comment`);
  }
} catch (error) {
  await writeStepSummary(body);
  console.warn(`bot-submission-pr-comment: PR comment unavailable, wrote step summary: ${error.message}`);
  if (env("BOT_SUBMISSION_PR_COMMENT_REQUIRED", "") === "1") {
    throw error;
  }
}

function buildCommentBody() {
  const statusLine =
    result === "success"
      ? "OpenBao provisioning completed."
      : "OpenBao provisioning did not complete.";
  const actionLine = actionSummary(flow);
  const nextSteps = nextStepsFor(flow, result);
  const messageLine = provisionMessage ? `\n\nLast provisioning message: \`${provisionMessage}\`` : "";

  return `${marker}
### Reef bot OpenBao provisioning

${statusPrefix(result)} ${statusLine}

- Bot: \`${botId}\`
- Submitter identity: \`${submitterIdentity}\`
- Flow: \`${flow}\` - ${actionLine}
- Secret slice: \`${slicePath}\`
- Actions run: ${runUrl}

${nextSteps}

No OpenBao token, GitHub OIDC token, admin API token, or secret value is exposed in this comment. Bot files and PR comments are public review surfaces, so bot-specific credentials or private config must be managed through the Reef admin/secret workflow for the slice above.${messageLine}
`;
}

function actionSummary(value) {
  switch (value) {
    case "add":
      return "provision a dedicated bot secret slice";
    case "update":
      return "verify and reuse the existing bot secret slice";
    case "remove":
      return "revoke/delete the bot secret slice";
    default:
      return "unknown provisioning action";
  }
}

function nextStepsFor(value, status) {
  if (status !== "success") {
    return "Next step: a maintainer should inspect the Actions run and fix the platform-side provisioning issue before this PR is merged.";
  }
  if (value === "remove") {
    return "Next step: reviewers can confirm the bot removal. The OpenBao slice has been revoked/deleted by the hosted provisioning gate.";
  }
  if (value === "update") {
    return "Next step: reviewers can continue the bot update review. Existing bot secrets stay in the same slice; do not commit replacements in this PR.";
  }
  return "Next step: reviewers can continue the new bot review. If the bot needs private config, the submitter or a `secret-admin` should add it through the Reef admin/secret workflow for this slice after review approval.";
}

function statusPrefix(value) {
  return value === "success" ? "[ok]" : "[attention]";
}

async function findExistingComment() {
  const comments = await githubRequest("GET", `/repos/${repo}/issues/${prNumber}/comments?per_page=100`);
  return comments.find((comment) => typeof comment.body === "string" && comment.body.includes(marker));
}

async function githubRequest(method, path, body) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      accept: "application/vnd.github+json",
      authorization: `Bearer ${token}`,
      "content-type": "application/json",
      "x-github-api-version": "2022-11-28",
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  const payload = text ? JSON.parse(text) : null;
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`GitHub API ${method} ${path} failed (${response.status}): ${payload?.message ?? text}`);
  }
  return payload;
}

function requiredEnv(name) {
  const value = env(name, "");
  if (!value) {
    throw new Error(`${name} is required`);
  }
  return value;
}

function sanitizeMessage(value) {
  return value
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/g, "Bearer [redacted]")
    .replace(/token[=:]\s*[A-Za-z0-9._~+/=-]+/gi, "token=[redacted]")
    .slice(0, 500);
}

async function writeStepSummary(summaryBody) {
  const summaryPath = env("GITHUB_STEP_SUMMARY", "");
  if (!summaryPath) {
    return;
  }
  const { appendFile } = await import("node:fs/promises");
  await appendFile(summaryPath, `${summaryBody}\n`);
}
