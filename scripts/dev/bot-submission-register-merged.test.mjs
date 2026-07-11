import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawn } from "node:child_process";
import http from "node:http";

const repoRoot = new URL("../../", import.meta.url).pathname;
const tempRoot = mkdtempSync(join(tmpdir(), "reef-register-merged-test-"));
const botDir = join(tempRoot, "bots", "sample-bot");
await import("node:fs/promises").then((fs) => fs.mkdir(botDir, { recursive: true }));
const manifestPath = join(botDir, "bot.json");
const sourcePath = join(botDir, "index.ts");

writeFileSync(sourcePath, "import { ReefBotV1 } from '@reef/bot-sdk';\nexport default class SampleBot extends ReefBotV1 {}\n");
writeFileSync(
  manifestPath,
  JSON.stringify(
    {
      botId: "sample-bot",
      fileName: "index.ts",
      metadata: {
        name: "Sample Bot",
        publisher: "octocat",
        email: "octocat@example.com",
        version: "1.2.3",
        sdkVersion: "1.5.0",
        botApiVersion: "v1",
        description: "test bot",
      },
    },
    null,
    2,
  ),
);

const requests = [];
const server = await fakeAdminApi(async (req, body, res) => {
  if (req.method === "GET" && req.url === "/users/octocat") {
    json(res, 200, { id: 583231, login: "octocat", name: "The Octocat" });
    return;
  }

  requests.push({ method: req.method, url: req.url, headers: req.headers, body });
  assert.equal(req.headers.authorization, "Bearer scoped-admin-token");
  assert.equal(req.headers["x-reef-actor-id"], "bot-submission-ci");

  if (req.method === "GET" && req.url === "/admin/v1/arena/bots?botId=sample-bot") {
    json(res, 404, { error: "not found" });
    return;
  }
  if (req.method === "POST" && req.url === "/admin/v1/arena/bots") {
    const parsed = JSON.parse(body);
    assert.equal(parsed.botId, "sample-bot");
    assert.equal(parsed.fileName.endsWith("bots/sample-bot/index.ts"), true);
    assert.equal(parsed.name, "Sample Bot");
    assert.equal(parsed.version, "1.2.3");
    json(res, 200, { status: "ok" });
    return;
  }
  if (req.method === "POST" && req.url === "/admin/v1/arena/bots/ownership") {
    const parsed = JSON.parse(body);
    assert.equal(parsed.botId, "sample-bot");
    assert.equal(parsed.githubUserId, 583231);
    assert.equal(parsed.githubLogin, "octocat");
    assert.equal(parsed.displayName, "The Octocat");
    json(res, 200, { status: "ok" });
    return;
  }
  if (req.method === "GET" && req.url === "/admin/v1/arena/bot-versions?botId=sample-bot&versionId=1.2.3") {
    json(res, 404, { error: "not found" });
    return;
  }
  if (req.method === "POST" && req.url === "/admin/v1/arena/bot-versions") {
    const parsed = JSON.parse(body);
    assert.equal(parsed.botId, "sample-bot");
    assert.equal(parsed.versionId, "1.2.3");
    assert.match(parsed.sourceHash, /^sha256:[a-f0-9]{64}$/);
    assert.equal(parsed.artifactHash, parsed.sourceHash);
    assert.equal(parsed.sdkVersion, "1.5.0");
    assert.equal(parsed.apiVersion, "v1");
    assert.match(parsed.dependencyManifestHash, /^sha256:[a-f0-9]{64}$/);
    json(res, 200, { status: "ok" });
    return;
  }

  json(res, 500, { error: `unexpected ${req.method} ${req.url}` });
});

try {
  const result = await runRegister([manifestPath], {
    ARENA_ADMIN_API_URL: server.url,
    ARENA_ADMIN_API_TOKEN: "scoped-admin-token",
    GITHUB_API_URL: server.url,
    BOT_SUBMISSION_REGISTER_SKIP_ARTIFACT_BUILD: "1",
  });
  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /registered bot sample-bot/);
  assert.match(result.stdout, /registered version sample-bot\/1\.2\.3/);
  assert.deepEqual(
    requests.map((request) => `${request.method} ${request.url}`),
    [
      "GET /admin/v1/arena/bots?botId=sample-bot",
      "POST /admin/v1/arena/bots",
      "POST /admin/v1/arena/bots/ownership",
      "GET /admin/v1/arena/bot-versions?botId=sample-bot&versionId=1.2.3",
      "POST /admin/v1/arena/bot-versions",
    ],
  );
  console.log("bot submission merged registry sync checks passed");
} finally {
  await server.close();
}

function runRegister(args, env) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, ["scripts/dev/bot-submission-register-merged.mjs", ...args], {
      cwd: repoRoot,
      env: { ...process.env, ...env },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => {
      stdout += chunk;
    });
    child.stderr.on("data", (chunk) => {
      stderr += chunk;
    });
    child.on("close", (status) => resolve({ status, stdout, stderr }));
  });
}

function fakeAdminApi(handler) {
  const server = http.createServer((req, res) => {
    let body = "";
    req.setEncoding("utf8");
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", () => {
      Promise.resolve(handler(req, body, res)).catch((error) => {
        json(res, 500, { error: error.message });
      });
    });
  });
  return new Promise((resolve) => {
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
