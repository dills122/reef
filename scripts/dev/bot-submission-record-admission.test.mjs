import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;
const sha = "a".repeat(40);

const dryRun = await run([], baseEnv());
assert.equal(dryRun.status, 0, dryRun.stderr);
assert.equal(JSON.parse(dryRun.stdout).admission.state, "pending_invite_review");

const requests = [];
const server = await fakeServer((req, body, res) => {
  requests.push({ req, body });
  assert.equal(req.method, "POST");
  assert.equal(req.url, "/admin/v1/arena/submission-admissions");
  assert.equal(req.headers.authorization, "Bearer scoped-token");
  assert.equal(req.headers["x-reef-actor-id"], "bot-submission-ci");
  assert.deepEqual(JSON.parse(body), basePayload());
  json(res, 200, { admission: { ...basePayload(), state: "invite_approved" } });
});
try {
  const real = await run([], { ...baseEnv(), BOT_SUBMISSION_ADMISSION_MODE: "real", ARENA_ADMIN_API_URL: server.url, ARENA_ADMIN_API_TOKEN: "scoped-token" });
  assert.equal(real.status, 0, real.stderr);
  assert.equal(JSON.parse(real.stdout).admission.state, "invite_approved");
  assert.equal(requests.length, 1);
} finally { await server.close(); }

const invalid = await run([], { ...baseEnv(), HEAD_SHA: "invalid" });
assert.equal(invalid.status, 1);
assert.match(invalid.stderr, /HEAD_SHA must be a Git commit SHA/);

console.log("bot submission admission recording checks passed");

function basePayload() {
  return { repository: "dills122/reef", pullRequestNumber: 42, botId: "sample-bot", headRepository: "octo/reef", headOwnerLogin: "octo", githubUserId: 123, githubLogin: "octo", headSha: sha };
}
function baseEnv() {
  return { GITHUB_REPOSITORY: "dills122/reef", PR_NUMBER: "42", BOT_NAME: "sample-bot", HEAD_REPOSITORY: "octo/reef", HEAD_OWNER_LOGIN: "octo", SUBMITTER_GITHUB_ID: "123", SUBMITTER_GITHUB_LOGIN: "octo", HEAD_SHA: sha, BOT_SUBMISSION_ADMISSION_MODE: "dry-run" };
}
function run(args, values) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["scripts/dev/bot-submission-record-admission.mjs", ...args], { cwd: repoRoot, env: { ...process.env, ...values }, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = ""; let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}
function fakeServer(handler) {
  const server = http.createServer((req, res) => { let body = ""; req.on("data", (chunk) => { body += chunk; }); req.on("end", () => handler(req, body, res)); });
  return new Promise((resolve, reject) => {
    server.on("error", reject);
    server.listen(0, "127.0.0.1", () => resolve({ url: `http://127.0.0.1:${server.address().port}`, close: () => new Promise((done, failClose) => server.close((error) => error ? failClose(error) : done())) }));
  });
}
function json(res, status, payload) { res.writeHead(status, { "content-type": "application/json" }); res.end(JSON.stringify(payload)); }
