import { createHash, createPublicKey, createVerify, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { spawnSync } from "node:child_process";
import { createWriteStream } from "node:fs";
import { appendFile, copyFile, lstat, mkdir, readdir, rm, rename } from "node:fs/promises";
import { dirname, join, normalize, relative, sep } from "node:path";
import { once } from "node:events";
import { fileURLToPath } from "node:url";

const defaultConfig = {
  port: intEnv("DEPLOY_RECEIVER_PORT", 8090),
  path: process.env.DEPLOY_RECEIVER_PATH || "/admin/deploy/arena-admin",
  issuer: process.env.DEPLOY_RECEIVER_ISSUER || "https://token.actions.githubusercontent.com",
  jwksUrl:
    process.env.DEPLOY_RECEIVER_JWKS_URL ||
    "https://token.actions.githubusercontent.com/.well-known/jwks",
  expectedAudience: process.env.DEPLOY_RECEIVER_EXPECTED_AUDIENCE || "reef-backbone-admin-deploy",
  expectedRepository: process.env.DEPLOY_RECEIVER_EXPECTED_REPOSITORY || "dills122/reef",
  expectedRef: process.env.DEPLOY_RECEIVER_EXPECTED_REF || "refs/heads/master",
  expectedEnvironment: process.env.DEPLOY_RECEIVER_EXPECTED_ENVIRONMENT || "backbone-admin",
  expectedWorkflow: process.env.DEPLOY_RECEIVER_EXPECTED_WORKFLOW || "Admin UI Deploy",
  liveDir: process.env.DEPLOY_RECEIVER_LIVE_DIR || "/srv/arena-admin",
  releasesDir: process.env.DEPLOY_RECEIVER_RELEASES_DIR || "/srv/arena-admin-releases",
  maxBytes: intEnv("DEPLOY_RECEIVER_MAX_BYTES", 25 * 1024 * 1024),
  clockSkewSeconds: intEnv("DEPLOY_RECEIVER_CLOCK_SKEW_SECONDS", 60),
  releaseRetentionCount: intEnv("DEPLOY_RECEIVER_RELEASE_RETENTION_COUNT", 10),
};

const jwksCache = {
  expiresAt: 0,
  keys: [],
};

function intEnv(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
  });
  res.end(body);
}

function base64urlDecode(value) {
  const padded = `${value}${"=".repeat((4 - (value.length % 4)) % 4)}`;
  return Buffer.from(padded.replace(/-/g, "+").replace(/_/g, "/"), "base64");
}

function parseJwt(token) {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("OIDC token is not a compact JWS");
  }
  const [encodedHeader, encodedPayload, encodedSignature] = parts;
  const header = JSON.parse(base64urlDecode(encodedHeader).toString("utf8"));
  const payload = JSON.parse(base64urlDecode(encodedPayload).toString("utf8"));
  return { encodedHeader, encodedPayload, encodedSignature, header, payload };
}

async function fetchJwks(config = defaultConfig) {
  const now = Date.now();
  if (jwksCache.keys.length > 0 && jwksCache.expiresAt > now) {
    return jwksCache.keys;
  }
  const response = await fetch(config.jwksUrl, {
    headers: { accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`failed to fetch OIDC JWKS: ${response.status}`);
  }
  const json = await response.json();
  if (!Array.isArray(json.keys)) {
    throw new Error("OIDC JWKS response did not include keys");
  }
  jwksCache.keys = json.keys;
  jwksCache.expiresAt = now + 10 * 60 * 1000;
  return jwksCache.keys;
}

async function verifyJwt(token, config = defaultConfig, jwks = null) {
  const parsed = parseJwt(token);
  if (parsed.header.alg !== "RS256") {
    throw new Error(`unsupported OIDC token alg: ${parsed.header.alg || ""}`);
  }
  if (!parsed.header.kid) {
    throw new Error("OIDC token is missing kid");
  }
  const keys = jwks || (await fetchJwks(config));
  const jwk = keys.find((key) => key.kid === parsed.header.kid);
  if (!jwk) {
    throw new Error("OIDC signing key not found");
  }
  const verifier = createVerify("RSA-SHA256");
  verifier.update(`${parsed.encodedHeader}.${parsed.encodedPayload}`);
  verifier.end();
  const signature = base64urlDecode(parsed.encodedSignature);
  const ok = verifier.verify(createPublicKey({ key: jwk, format: "jwk" }), signature);
  if (!ok) {
    throw new Error("OIDC token signature is invalid");
  }
  validateClaims(parsed.payload, config);
  return parsed.payload;
}

function validateClaims(payload, config = defaultConfig, nowSeconds = Math.floor(Date.now() / 1000)) {
  const skew = config.clockSkewSeconds;
  const checks = [
    ["iss", config.issuer],
    ["repository", config.expectedRepository],
    ["ref", config.expectedRef],
  ];
  if (config.expectedEnvironment) checks.push(["environment", config.expectedEnvironment]);
  if (config.expectedWorkflow) checks.push(["workflow", config.expectedWorkflow]);

  for (const [key, expected] of checks) {
    if (payload[key] !== expected) {
      throw new Error(`OIDC claim ${key} mismatch`);
    }
  }

  const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
  if (!audiences.includes(config.expectedAudience)) {
    throw new Error("OIDC audience mismatch");
  }
  if (typeof payload.exp !== "number" || payload.exp + skew < nowSeconds) {
    throw new Error("OIDC token is expired");
  }
  if (typeof payload.nbf === "number" && payload.nbf - skew > nowSeconds) {
    throw new Error("OIDC token is not valid yet");
  }
  if (typeof payload.iat === "number" && payload.iat - skew > nowSeconds) {
    throw new Error("OIDC token issued-at is in the future");
  }
}

function bearerToken(req) {
  const value = req.headers.authorization || "";
  const match = value.match(/^Bearer\s+(.+)$/i);
  return match ? match[1] : "";
}

function requireHeader(req, name) {
  const value = req.headers[name.toLowerCase()];
  if (Array.isArray(value)) return value[0] || "";
  return value || "";
}

function assertHexSha256(value) {
  if (!/^[a-fA-F0-9]{64}$/.test(value)) {
    throw new Error("invalid sha256 header");
  }
}

function safeRelativePath(name) {
  const clean = name.replace(/\\/g, "/").replace(/^\.\//, "");
  if (
    clean === "" ||
    clean.includes("\0") ||
    clean.startsWith("/") ||
    clean.split("/").some((part) => part === ".." || part === "")
  ) {
    throw new Error(`unsafe archive path: ${name}`);
  }
  return clean;
}

function validateArchiveEntryNames(output) {
  const files = [];
  for (const raw of output.split(/\r?\n/)) {
    if (!raw.trim()) continue;
    const isDir = raw.endsWith("/");
    const clean = safeRelativePath(isDir ? raw.slice(0, -1) : raw);
    if (!isDir) files.push(clean);
  }
  if (!files.includes("index.html")) {
    throw new Error("archive is missing index.html");
  }
  return files;
}

function validateArchiveEntryMetadata(output) {
  for (const raw of output.split(/\r?\n/)) {
    if (!raw.trim()) continue;
    const permissions = raw.trimStart().split(/\s+/, 1)[0] || "";
    if (!permissions) {
      throw new Error("archive entry metadata is invalid");
    }
    const type = permissions[0];
    if (type !== "-" && type !== "d") {
      throw new Error("archive contains unsupported entry type");
    }
  }
}

async function assertExtractedTreeSafe(root) {
  const entries = await walk(root);
  for (const entry of entries) {
    const details = await lstat(entry);
    if (details.isSymbolicLink()) {
      throw new Error(`archive extracted unsupported symlink: ${relative(root, entry)}`);
    }
    if (!details.isDirectory() && !details.isFile()) {
      throw new Error(`archive extracted unsupported file type: ${relative(root, entry)}`);
    }
  }
}

async function readRequestToFile(req, path, maxBytes) {
  const hash = createHash("sha256");
  const out = createWriteStream(path, { flags: "wx", mode: 0o600 });
  let total = 0;
  try {
    for await (const chunk of req) {
      total += chunk.length;
      if (total > maxBytes) {
        throw new Error(`upload exceeds ${maxBytes} bytes`);
      }
      hash.update(chunk);
      if (!out.write(chunk)) {
        await once(out, "drain");
      }
    }
    out.end();
    await once(out, "finish");
    return { sha256: hash.digest("hex"), bytes: total };
  } catch (err) {
    out.destroy();
    await rm(path, { force: true }).catch(() => {});
    throw err;
  }
}

function timingSafeHexEqual(left, right) {
  const leftBuffer = Buffer.from(left.toLowerCase(), "hex");
  const rightBuffer = Buffer.from(right.toLowerCase(), "hex");
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
}

function requiredClaimString(payload, name, pattern) {
  const value = payload[name];
  if (typeof value !== "string" || !pattern.test(value)) {
    throw new Error(`OIDC claim ${name} is missing or invalid`);
  }
  return value;
}

function deployMetadataFromClaims(claims) {
  return {
    gitSha: requiredClaimString(claims, "sha", /^[a-fA-F0-9]{40}$/).toLowerCase(),
    runId: requiredClaimString(claims, "run_id", /^[0-9]+$/),
    runAttempt: requiredClaimString(claims, "run_attempt", /^[0-9]+$/),
    repository: claims.repository,
    ref: claims.ref,
    workflow: claims.workflow,
  };
}

function assertOptionalHeaderMatches(value, expected, name, options = {}) {
  if (!value) return;
  const actual = options.caseInsensitive ? value.toLowerCase() : value;
  const wanted = options.caseInsensitive ? expected.toLowerCase() : expected;
  if (actual !== wanted) {
    throw new Error(`${name} header does not match verified OIDC claim`);
  }
}

async function deployArchive(archivePath, metadata, config = defaultConfig) {
  await mkdir(config.releasesDir, { recursive: true });
  await mkdir(config.liveDir, { recursive: true });

  const list = spawnSync("tar", ["-tzf", archivePath], { encoding: "utf8" });
  if (list.status !== 0) {
    throw new Error(`tar list failed: ${(list.stderr || "").trim()}`);
  }
  validateArchiveEntryNames(list.stdout);
  const metadataList = spawnSync("tar", ["-tvzf", archivePath], { encoding: "utf8" });
  if (metadataList.status !== 0) {
    throw new Error(`tar metadata list failed: ${(metadataList.stderr || "").trim()}`);
  }
  validateArchiveEntryMetadata(metadataList.stdout);

  const releaseId = `${metadata.gitSha}-${metadata.artifactSha256.slice(0, 12)}`.replace(/[^a-zA-Z0-9_.-]/g, "-");
  const staging = join(config.releasesDir, `${releaseId}.staging-${process.pid}-${Date.now()}`);
  const release = join(config.releasesDir, releaseId);

  await rm(staging, { recursive: true, force: true });
  await mkdir(staging, { recursive: true });
  try {
    const extract = spawnSync("tar", ["-xzf", archivePath, "-C", staging], { encoding: "utf8" });
    if (extract.status !== 0) {
      throw new Error(`tar extract failed: ${(extract.stderr || "").trim()}`);
    }
    await assertExtractedTreeSafe(staging);
    await rm(release, { recursive: true, force: true });
    await rename(staging, release);
    await installRelease(release, config.liveDir);
    await appendDeployLog(config.releasesDir, {
      deployedAt: new Date().toISOString(),
      releaseId,
      ...metadata,
    });
    await pruneOldReleases(config.releasesDir, config.releaseRetentionCount ?? defaultConfig.releaseRetentionCount);
    return { releaseId };
  } catch (err) {
    await rm(staging, { recursive: true, force: true }).catch(() => {});
    throw err;
  }
}

async function pruneOldReleases(releasesDir, keepCount) {
  if (!Number.isFinite(keepCount) || keepCount <= 0) return;
  const entries = await readdir(releasesDir, { withFileTypes: true });
  const releases = [];
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    if (entry.name.startsWith(".") || entry.name.includes(".staging-")) continue;
    const path = join(releasesDir, entry.name);
    const details = await lstat(path);
    releases.push({ path, mtimeMs: details.mtimeMs });
  }
  releases.sort((left, right) => right.mtimeMs - left.mtimeMs || left.path.localeCompare(right.path));
  for (const release of releases.slice(keepCount)) {
    await rm(release.path, { recursive: true, force: true });
  }
}

async function installRelease(releaseDir, liveDir) {
  const normalized = [];
  for (const file of await walk(releaseDir)) {
    const details = await lstat(file);
    if (details.isFile()) {
      normalized.push(normalizeRelative(releaseDir, file));
    }
  }
  const nonHtml = normalized.filter((path) => !path.endsWith(".html")).sort();
  const html = normalized.filter((path) => path.endsWith(".html")).sort();
  const releaseSet = new Set(normalized);

  for (const rel of [...nonHtml, ...html]) {
    const from = join(releaseDir, rel);
    const to = join(liveDir, rel);
    await mkdir(dirname(to), { recursive: true });
    await copyFile(from, to);
  }

  for (const livePath of (await walk(liveDir)).sort((a, b) => b.length - a.length)) {
    const rel = normalizeRelative(liveDir, livePath);
    if (releaseSet.has(rel)) continue;
    const details = await lstat(livePath);
    if (details.isDirectory()) {
      const remaining = await readdir(livePath);
      if (remaining.length === 0) await rm(livePath, { recursive: true, force: true });
    } else {
      await rm(livePath, { force: true });
    }
  }
}

function normalizeRelative(root, path) {
  const rel = relative(root, path);
  if (!rel || rel.startsWith("..") || rel.includes(`..${sep}`)) {
    throw new Error(`path escaped root: ${path}`);
  }
  return rel.split(sep).join("/");
}

async function walk(root) {
  const out = [];
  async function visit(path) {
    let entries;
    try {
      entries = await readdir(path, { withFileTypes: true });
    } catch (err) {
      if (err.code === "ENOENT") return;
      throw err;
    }
    for (const entry of entries) {
      const full = join(path, entry.name);
      out.push(full);
      if (entry.isDirectory()) {
        await visit(full);
      }
    }
  }
  await visit(root);
  return out;
}

async function appendDeployLog(releasesDir, entry) {
  await appendFile(join(releasesDir, "deploy-log.jsonl"), `${JSON.stringify(entry)}\n`, { mode: 0o600 });
}

async function handleDeploy(req, res, config = defaultConfig) {
  if (req.method !== "POST") {
    sendJson(res, 405, { ok: false, error: "method_not_allowed" });
    return;
  }

  const token = bearerToken(req);
  if (!token) {
    sendJson(res, 401, { ok: false, error: "missing_bearer_token" });
    return;
  }

  const expectedSha = requireHeader(req, "x-reef-artifact-sha256");
  const gitSha = requireHeader(req, "x-reef-git-sha");
  const runId = requireHeader(req, "x-reef-github-run-id");
  const runAttempt = requireHeader(req, "x-reef-github-run-attempt");
  let archivePath = "";
  try {
    assertHexSha256(expectedSha);

    const claims = await verifyJwt(token, config);
    const metadata = deployMetadataFromClaims(claims);
    assertOptionalHeaderMatches(gitSha, metadata.gitSha, "git sha", { caseInsensitive: true });
    assertOptionalHeaderMatches(runId, metadata.runId, "run id");
    assertOptionalHeaderMatches(runAttempt, metadata.runAttempt, "run attempt");

    const tempDir = join(config.releasesDir, ".uploads");
    await mkdir(tempDir, { recursive: true });
    archivePath = join(tempDir, `${metadata.gitSha}-${process.pid}-${Date.now()}.tar.gz`);
    const upload = await readRequestToFile(req, archivePath, config.maxBytes);
    if (!timingSafeHexEqual(upload.sha256, expectedSha)) {
      throw new Error("artifact sha256 mismatch");
    }

    const result = await deployArchive(
      archivePath,
      {
        ...metadata,
        artifactSha256: upload.sha256,
        bytes: upload.bytes,
      },
      config,
    );
    await rm(archivePath, { force: true });
    sendJson(res, 200, { ok: true, ...result, artifactSha256: upload.sha256 });
  } catch (err) {
    if (archivePath) {
      await rm(archivePath, { force: true }).catch(() => {});
    }
    console.error(`deploy failed: ${err instanceof Error ? err.message : String(err)}`);
    sendJson(res, 400, { ok: false, error: "deploy_failed" });
  }
}

function createDeployReceiver(config = defaultConfig) {
  return createServer(async (req, res) => {
    const url = new URL(req.url || "/", "http://localhost");
    if (url.pathname !== config.path) {
      sendJson(res, 404, { ok: false, error: "not_found" });
      return;
    }
    await handleDeploy(req, res, config);
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const server = createDeployReceiver();
  server.listen(defaultConfig.port, "0.0.0.0", () => {
    console.log(`reef deploy receiver listening on :${defaultConfig.port}${defaultConfig.path}`);
  });
}

export {
  createDeployReceiver,
  deployArchive,
  deployMetadataFromClaims,
  installRelease,
  pruneOldReleases,
  parseJwt,
  safeRelativePath,
  validateArchiveEntryNames,
  validateArchiveEntryMetadata,
  validateClaims,
  verifyJwt,
};
