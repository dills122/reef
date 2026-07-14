import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;

const requests = [];
const server = await fakeGitHubApi((req, body, res) => {
  requests.push({ req, body });
  assert.equal(req.method, "POST");
  assert.equal(req.url, "/repos/dills122/reef/statuses/abc123");
  assert.equal(req.headers.authorization, "Bearer github-token");
  assert.deepEqual(JSON.parse(body), {
    state: "success",
    context: "registry-diff-and-provision",
    description: "OpenBao provisioning passed",
    target_url: "https://github.com/dills122/reef/actions/runs/1",
  });
  json(res, 201, { state: "success" });
});

try {
  const result = await runStatus({
    GITHUB_API_URL: server.url,
    GITHUB_REPOSITORY: "dills122/reef",
    GITHUB_TOKEN: "github-token",
    STATUS_SHA: "abc123",
    STATUS_STATE: "success",
    STATUS_DESCRIPTION: "OpenBao provisioning passed",
    STATUS_TARGET_URL: "https://github.com/dills122/reef/actions/runs/1",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /registry-diff-and-provision=success sha=abc123/);
  assert.equal(requests.length, 1);
} finally {
  await server.close();
}

const invalidState = await runStatus({
  GITHUB_REPOSITORY: "dills122/reef",
  GITHUB_TOKEN: "github-token",
  STATUS_SHA: "abc123",
  STATUS_STATE: "done",
  BOT_SUBMISSION_COMMIT_STATUS_DRY_RUN: "1",
});
assert.equal(invalidState.status, 1);
assert.match(invalidState.stderr, /STATUS_STATE must be one of/);

console.log("bot submission commit status checks passed");

function runStatus(env) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/dev/bot-submission-commit-status.mjs"], {
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
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

function fakeGitHubApi(handler) {
  const server = http.createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => {
      try {
        handler(req, body, res);
      } catch (error) {
        json(res, 500, { error: error.message });
      }
    });
  });
  return new Promise((resolve, reject) => {
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      resolve({
        url: `http://127.0.0.1:${address.port}`,
        close: () => new Promise((closeResolve) => server.close(closeResolve)),
      });
    });
  });
}

function json(res, status, payload) {
  res.writeHead(status, { "content-type": "application/json" });
  res.end(JSON.stringify(payload));
}
