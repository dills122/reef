import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;

const dryRun = await runProvisioner(["bot-1", "add", "octocat"], {
  BOT_SUBMISSION_OPENBAO_MODE: "dry-run",
  ARENA_ADMIN_API_URL: "",
  ARENA_ADMIN_API_TOKEN: "",
  GITHUB_OIDC_TOKEN: "",
});
assert.equal(dryRun.status, 0, dryRun.stderr);
assert.match(dryRun.stdout, /dry-run ok, would provision secret\/bots\/octocat\/bot-1/);

const invalidBotId = await runProvisioner(["bot/../other", "add", "octocat"], {
  BOT_SUBMISSION_OPENBAO_MODE: "dry-run",
});
assert.equal(invalidBotId.status, 1);
assert.match(invalidBotId.stderr, /botId must be lowercase alphanumeric\/hyphen/);

const invalidSubmitter = await runProvisioner(["bot-1", "add", "../octocat"], {
  BOT_SUBMISSION_OPENBAO_MODE: "dry-run",
});
assert.equal(invalidSubmitter.status, 1);
assert.match(invalidSubmitter.stderr, /submitterIdentity must match/);

const successRequests = [];
const successServer = await fakeAdminApi((req, body, res) => {
  successRequests.push({ req, body });
  assert.equal(req.method, "POST");
  assert.equal(req.url, "/admin/v1/arena/bots/openbao-provision");
  assert.equal(req.headers.authorization, "Bearer scoped-admin-token");
  assert.equal(req.headers["x-reef-actor-id"], "bot-submission-ci");
  assert.deepEqual(JSON.parse(body), {
    githubOidcToken: "github-oidc-token",
    submitterIdentity: "octocat",
    botId: "bot-1",
    flow: "add",
    repository: "reef/reef",
    pullRequestNumber: 42,
    headSha: "a".repeat(40),
  });
  json(res, 200, { status: "ok", botId: "bot-1", flow: "add" });
});
try {
  const real = await runProvisioner(["bot-1", "add", "octocat"], realEnv(successServer.url));
  assert.equal(real.status, 0, real.stderr);
  assert.match(real.stdout, /real ok, provision secret\/bots\/octocat\/bot-1/);
  assert.equal(successRequests.length, 1);
} finally {
  await successServer.close();
}

const directRequests = [];
const directServer = await fakeAdminApi((_req, body, res) => {
  directRequests.push(JSON.parse(body));
  json(res, 200, { status: "ok", botId: "bot-1", flow: "add" });
});
try {
  const direct = await runProvisioner(["bot-1", "add", "octocat"], {
    BOT_SUBMISSION_OPENBAO_MODE: "real",
    ARENA_ADMIN_API_URL: directServer.url,
    ARENA_ADMIN_API_TOKEN: "scoped-admin-token",
    GITHUB_OIDC_TOKEN: "github-oidc-token",
    GITHUB_REPOSITORY: "reef/reef",
    PR_NUMBER: "42",
    SUBMISSION_REPOSITORY: "",
    SUBMISSION_PR_NUMBER: "",
    SUBMISSION_HEAD_SHA: "",
  });
  assert.equal(direct.status, 0, direct.stderr);
  assert.deepEqual(directRequests, [
    {
      githubOidcToken: "github-oidc-token",
      submitterIdentity: "octocat",
      botId: "bot-1",
      flow: "add",
    },
  ]);
} finally {
  await directServer.close();
}

const oidcRequests = [];
const oidcServer = await fakeOidcApi((req, res) => {
  oidcRequests.push(req.url);
  assert.equal(req.headers.authorization, "Bearer github-actions-request-token");
  const requestedAudience = new URL(req.url, oidcServer.url).searchParams.get("audience");
  assert.equal(requestedAudience, "reef-bot-submission-ci");
  json(res, 200, { value: "github-oidc-token-from-actions" });
});
const oidcAdminServer = await fakeAdminApi((_req, body, res) => {
  assert.equal(JSON.parse(body).githubOidcToken, "github-oidc-token-from-actions");
  json(res, 200, { status: "ok", botId: "bot-1", flow: "add" });
});
try {
  const real = await runProvisioner(["bot-1", "add", "octocat"], {
    BOT_SUBMISSION_OPENBAO_MODE: "real",
    ARENA_ADMIN_API_URL: oidcAdminServer.url,
    ARENA_ADMIN_API_TOKEN: "scoped-admin-token",
    GITHUB_OIDC_TOKEN: "",
    ACTIONS_ID_TOKEN_REQUEST_URL: `${oidcServer.url}/oidc/token`,
    ACTIONS_ID_TOKEN_REQUEST_TOKEN: "github-actions-request-token",
    ...submissionEnv(),
  });
  assert.equal(real.status, 0, real.stderr);
  assert.equal(oidcRequests.length, 1);
} finally {
  await oidcAdminServer.close();
  await oidcServer.close();
}

const userFailureServer = await fakeAdminApi((_req, _body, res) => {
  json(res, 400, { error: "bot ownership limit exceeded" });
});
try {
  const failed = await runProvisioner(["bot-1", "add", "octocat"], realEnv(userFailureServer.url));
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /user-fixable failure/);
  assert.match(failed.stderr, /bot ownership limit exceeded/);
} finally {
  await userFailureServer.close();
}

const platformFailureServer = await fakeAdminApi((_req, _body, res) => {
  json(res, 503, { error: "arena admin service unavailable" });
});
try {
  const failed = await runProvisioner(["bot-1", "add", "octocat"], realEnv(platformFailureServer.url));
  assert.equal(failed.status, 1);
  assert.match(failed.stderr, /platform-fixable failure/);
  assert.match(failed.stderr, /arena admin service unavailable/);
} finally {
  await platformFailureServer.close();
}

const authFailure = await runProvisioner(["bot-1", "add", "octocat"], {
  BOT_SUBMISSION_OPENBAO_MODE: "real",
  ARENA_ADMIN_API_URL: "http://127.0.0.1:1",
  ARENA_ADMIN_API_TOKEN: "",
  GITHUB_OIDC_TOKEN: "github-oidc-token",
});
assert.equal(authFailure.status, 1);
assert.match(authFailure.stderr, /platform-fixable failure/);
assert.match(authFailure.stderr, /ARENA_ADMIN_API_TOKEN is required/);

const incompleteSubmissionContext = await runProvisioner(["bot-1", "add", "octocat"], {
  BOT_SUBMISSION_OPENBAO_MODE: "real",
  ARENA_ADMIN_API_URL: "http://127.0.0.1:1",
  ARENA_ADMIN_API_TOKEN: "scoped-admin-token",
  GITHUB_OIDC_TOKEN: "github-oidc-token",
  SUBMISSION_REPOSITORY: "reef/reef",
});
assert.equal(incompleteSubmissionContext.status, 1);
assert.match(incompleteSubmissionContext.stderr, /platform-fixable failure/);
assert.match(incompleteSubmissionContext.stderr, /complete trusted submission repository, PR number, and head SHA/);

console.log("bot submission OpenBao provisioning checks passed");

function realEnv(adminApiUrl) {
  return {
    BOT_SUBMISSION_OPENBAO_MODE: "real",
    ARENA_ADMIN_API_URL: adminApiUrl,
    ARENA_ADMIN_API_TOKEN: "scoped-admin-token",
    GITHUB_OIDC_TOKEN: "github-oidc-token",
    ...submissionEnv(),
  };
}

function submissionEnv() {
  return {
    SUBMISSION_REPOSITORY: "reef/reef",
    SUBMISSION_PR_NUMBER: "42",
    SUBMISSION_HEAD_SHA: "a".repeat(40),
  };
}

function runProvisioner(args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/dev/bot-submission-provision-openbao.mjs", ...args], {
      cwd: repoRoot,
      env: { ...process.env, ...env },
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
    child.on("close", (status) => {
      resolve({ status, stdout, stderr });
    });
  });
}

function fakeAdminApi(handler) {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (chunk) => {
      body += chunk.toString();
    });
    req.on("end", () => {
      handler(req, body, res);
    });
  });
  return new Promise((resolve, reject) => {
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      resolve({
        url: `http://127.0.0.1:${port}`,
        close: () =>
          new Promise((closeResolve, closeReject) => {
            server.close((error) => (error ? closeReject(error) : closeResolve()));
          }),
      });
    });
  });
}

function fakeOidcApi(handler) {
  return fakeAdminApi((req, _body, res) => handler(req, res));
}

function json(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(payload));
}
