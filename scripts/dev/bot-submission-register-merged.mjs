import { createHash } from "node:crypto";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, isAbsolute, join, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import {
  assertManifestPathMatchesBotId,
  assertSafeBotSourceFileName,
  assertValidBotId,
} from "./lib/bot-submission-contract.mjs";

loadDotEnv();

const repoRoot = new URL("../../", import.meta.url).pathname;
const manifestPaths = process.argv.slice(2);
if (manifestPaths.length === 0) {
  console.error("usage: node scripts/dev/bot-submission-register-merged.mjs <bots/*/bot.json>...");
  process.exit(2);
}

const adminApiUrl = env("ARENA_ADMIN_API_URL", "").replace(/\/+$/, "");
if (!adminApiUrl) {
  fail("ARENA_ADMIN_API_URL is required for merged bot registry sync");
}
const adminApiToken = env("ARENA_ADMIN_API_TOKEN", "");
if (!adminApiToken) {
  fail("ARENA_ADMIN_API_TOKEN is required for merged bot registry sync");
}
const actorId = env("ADMIN_ACTOR_ID", "bot-submission-ci");
const githubApiUrl = env("GITHUB_API_URL", "https://api.github.com").replace(/\/+$/, "");

for (const manifestPath of manifestPaths) {
  await syncManifest(manifestPath);
}

async function syncManifest(manifestPathArg) {
  const manifestPath = resolveRepoPath(manifestPathArg);
  const manifest = readJson(manifestPath);
  validateManifest(manifest, manifestPathArg);

  const sourcePath = resolve(dirname(manifestPath), manifest.fileName);
  const registryFileName = relativeToRepo(sourcePath);
  const versionId = String(manifest.metadata.version || manifest.version || "").trim();
  if (versionId === "") {
    fail(`${manifestPathArg}: metadata.version is required for registry version sync`);
  }

  const existingBot = await getOptional(`/admin/v1/arena/bots?botId=${encodeURIComponent(manifest.botId)}`);
  if (existingBot === null) {
    await postJson("/admin/v1/arena/bots", {
      botId: manifest.botId,
      fileName: registryFileName,
      name: manifest.metadata.name,
      publisher: manifest.metadata.publisher,
      email: manifest.metadata.email,
      description: manifest.metadata.description ?? "",
      version: versionId,
    });
    console.log(`bot-submission-register-merged: registered bot ${manifest.botId}`);
  } else {
    console.log(`bot-submission-register-merged: bot ${manifest.botId} already registered`);
  }
  await syncBotOwnership(manifest);

  const existingVersion = await getOptional(
    `/admin/v1/arena/bot-versions?botId=${encodeURIComponent(manifest.botId)}&versionId=${encodeURIComponent(versionId)}`,
  );
  if (existingVersion !== null) {
    console.log(`bot-submission-register-merged: version ${manifest.botId}/${versionId} already registered`);
    return;
  }

  const artifact = buildArtifactManifest(sourcePath);
  await postJson("/admin/v1/arena/bot-versions", {
    botId: manifest.botId,
    versionId,
    sourceHash: artifact.sourceHash,
    artifactHash: artifact.artifactHash,
    sdkVersion: String(manifest.metadata.sdkVersion || manifest.sdkVersion || ""),
    apiVersion: String(manifest.metadata.botApiVersion || manifest.botApiVersion || ""),
    dependencyManifestHash: dependencyManifestHash(artifact.approvedPackages ?? []),
  });
  console.log(`bot-submission-register-merged: registered version ${manifest.botId}/${versionId}`);
}

async function syncBotOwnership(manifest) {
  const owner = await resolveGitHubIdentity(manifest.metadata.publisher);
  await postJson("/admin/v1/arena/bots/ownership", {
    botId: manifest.botId,
    githubUserId: owner.id,
    githubLogin: owner.login,
    displayName: owner.name || owner.login,
  });
  console.log(`bot-submission-register-merged: assigned owner ${owner.login} to ${manifest.botId}`);
}

async function resolveGitHubIdentity(login) {
  const response = await fetch(`${githubApiUrl}/users/${encodeURIComponent(login)}`, {
    headers: githubHeaders(),
  });
  const parsed = await responsePayload(response);
  if (response.status < 200 || response.status >= 300) {
    throw new Error(`GitHub user lookup failed for ${login} (${response.status}): ${JSON.stringify(parsed)}`);
  }
  if (!Number.isSafeInteger(parsed.id) || String(parsed.login || "").trim() === "") {
    throw new Error(`GitHub user lookup returned an invalid identity for ${login}`);
  }
  return {
    id: parsed.id,
    login: parsed.login,
    name: String(parsed.name || ""),
  };
}

async function getOptional(path) {
  const response = await fetch(`${adminApiUrl}${path}`, { headers: authHeaders() });
  if (response.status === 404) return null;
  if (response.status >= 200 && response.status < 300) return responsePayload(response);
  throw new Error(`GET ${path} failed (${response.status}): ${await response.text()}`);
}

async function postJson(path, payload) {
  const response = await fetch(`${adminApiUrl}${path}`, {
    method: "POST",
    headers: { ...authHeaders(), "content-type": "application/json" },
    body: JSON.stringify(payload),
  });
  const parsed = await responsePayload(response);
  if (response.status >= 200 && response.status < 300) return parsed;
  throw new Error(`POST ${path} failed (${response.status}): ${JSON.stringify(parsed)}`);
}

function authHeaders() {
  return {
    authorization: `Bearer ${adminApiToken}`,
    "X-Reef-Actor-Id": actorId,
  };
}

function githubHeaders() {
  return {
    accept: "application/vnd.github+json",
    "user-agent": "reef-bot-registry-sync",
    ...(env("GITHUB_TOKEN", "") ? { authorization: `Bearer ${env("GITHUB_TOKEN")}` } : {}),
  };
}

async function responsePayload(response) {
  const raw = await response.text();
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return { error: raw };
  }
}

function buildArtifactManifest(sourcePath) {
  if (env("BOT_SUBMISSION_REGISTER_SKIP_ARTIFACT_BUILD", "") === "1") {
    const source = readFileSync(sourcePath, "utf8");
    const hash = sha256(source);
    return { sourceHash: hash, artifactHash: hash, approvedPackages: [] };
  }

  const outDir = mkdtempSync(join(tmpdir(), "reef-bot-register-"));
  const artifactPath = join(outDir, `${basename(sourcePath, ".ts")}.bundle.js`);
  const manifestPath = join(outDir, `${basename(sourcePath, ".ts")}.bundle.manifest.json`);
  const result = spawnSync(
    "bun",
    [
      "scripts/dev/bot-sdk-build-hosted-artifact.mjs",
      sourcePath,
      `--out=${artifactPath}`,
      `--manifest-out=${manifestPath}`,
    ],
    { cwd: repoRoot, encoding: "utf8" },
  );
  if (result.error || result.status !== 0) {
    throw new Error(result.error?.message || result.stderr?.trim() || result.stdout?.trim() || "hosted artifact build failed");
  }
  return readJson(manifestPath);
}

function validateManifest(manifest, manifestPath) {
  for (const field of ["botId", "fileName", "metadata"]) {
    if (!(field in manifest)) fail(`${manifestPath}: missing required field ${field}`);
  }
  if (!manifest.metadata || typeof manifest.metadata !== "object" || Array.isArray(manifest.metadata)) {
    fail(`${manifestPath}: metadata must be an object`);
  }
  for (const field of ["name", "publisher", "email", "version", "sdkVersion", "botApiVersion"]) {
    if (String(manifest.metadata[field] ?? "").trim() === "") {
      fail(`${manifestPath}: metadata.${field} is required`);
    }
  }
  try {
    assertValidBotId(manifest.botId, "manifest.botId");
    assertManifestPathMatchesBotId(manifestPath, manifest.botId);
    assertSafeBotSourceFileName(manifest.fileName);
  } catch (error) {
    fail(`${manifestPath}: ${error.message}`);
  }
}

function dependencyManifestHash(approvedPackages) {
  return sha256(JSON.stringify({ approvedPackages }));
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function sha256(value) {
  return `sha256:${createHash("sha256").update(value, "utf8").digest("hex")}`;
}

function resolveRepoPath(path) {
  return isAbsolute(path) ? path : resolve(repoRoot, path);
}

function relativeToRepo(path) {
  return path.startsWith(repoRoot) ? path.slice(repoRoot.length) : path;
}

function fail(message) {
  console.error(`bot-submission-register-merged: ${message}`);
  process.exit(1);
}
