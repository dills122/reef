import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile, mkdir } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  deployArchive,
  installRelease,
  safeRelativePath,
  validateArchiveEntryNames,
  validateClaims,
} from "../../infra/hetzner-core/server/deploy-receiver/server.mjs";

const now = 1_800_000_000;
const config = {
  issuer: "https://token.actions.githubusercontent.com",
  expectedAudience: "reef-backbone-admin-deploy",
  expectedRepository: "dills122/reef",
  expectedRef: "refs/heads/master",
  expectedEnvironment: "backbone-admin",
  expectedWorkflow: "Admin UI Deploy",
  clockSkewSeconds: 60,
};

validateClaims(
  {
    iss: config.issuer,
    aud: config.expectedAudience,
    repository: config.expectedRepository,
    ref: config.expectedRef,
    environment: config.expectedEnvironment,
    workflow: config.expectedWorkflow,
    exp: now + 300,
    nbf: now - 10,
    iat: now - 10,
  },
  config,
  now,
);

assert.throws(
  () =>
    validateClaims(
      {
        iss: config.issuer,
        aud: config.expectedAudience,
        repository: "someone/else",
        ref: config.expectedRef,
        environment: config.expectedEnvironment,
        workflow: config.expectedWorkflow,
        exp: now + 300,
      },
      config,
      now,
    ),
  /repository mismatch/,
);

assert.equal(safeRelativePath("./_app/version.json"), "_app/version.json");
assert.throws(() => safeRelativePath("../outside"), /unsafe archive path/);
assert.throws(() => safeRelativePath("/absolute"), /unsafe archive path/);
assert.deepEqual(validateArchiveEntryNames("./index.html\n./_app/app.js\n"), ["index.html", "_app/app.js"]);
assert.throws(() => validateArchiveEntryNames("./_app/app.js\n"), /missing index.html/);

const root = await mkdtemp(join(tmpdir(), "reef-deploy-receiver-test-"));
try {
  const release = join(root, "release");
  const live = join(root, "live");
  await mkdir(join(release, "_app"), { recursive: true });
  await mkdir(join(live, "_app"), { recursive: true });
  await writeFile(join(release, "_app", "new.js"), "new asset\n");
  await writeFile(join(release, "index.html"), "<script src=\"/_app/new.js\"></script>\n");
  await writeFile(join(live, "_app", "old.js"), "old asset\n");
  await writeFile(join(live, "old.html"), "old html\n");

  await installRelease(release, live);

  assert.equal(await readFile(join(live, "_app", "new.js"), "utf8"), "new asset\n");
  assert.equal(await readFile(join(live, "index.html"), "utf8"), "<script src=\"/_app/new.js\"></script>\n");
  await assert.rejects(() => readFile(join(live, "_app", "old.js"), "utf8"), /ENOENT/);
  await assert.rejects(() => readFile(join(live, "old.html"), "utf8"), /ENOENT/);
} finally {
  await rm(root, { recursive: true, force: true });
}

const deployRoot = await mkdtemp(join(tmpdir(), "reef-deploy-archive-test-"));
try {
  const source = join(deployRoot, "source");
  const archive = join(deployRoot, "arena-admin.tar.gz");
  const live = join(deployRoot, "live");
  const releases = join(deployRoot, "releases");
  await mkdir(join(source, "_app"), { recursive: true });
  await writeFile(join(source, "index.html"), "archive index\n");
  await writeFile(join(source, "_app", "bundle.js"), "archive bundle\n");
  const tar = spawnSync("tar", ["-czf", archive, "-C", source, "."], { encoding: "utf8" });
  assert.equal(tar.status, 0, tar.stderr);

  const result = await deployArchive(
    archive,
    {
      artifactSha256: "a".repeat(64),
      bytes: 12,
      gitSha: "b".repeat(40),
      runId: "123",
      runAttempt: "1",
      repository: "dills122/reef",
      ref: "refs/heads/master",
      workflow: "Admin UI Deploy",
    },
    {
      liveDir: live,
      releasesDir: releases,
    },
  );

  assert.match(result.releaseId, /^b{40}-a{12}$/);
  assert.equal(await readFile(join(live, "index.html"), "utf8"), "archive index\n");
  assert.equal(await readFile(join(live, "_app", "bundle.js"), "utf8"), "archive bundle\n");
  const log = await readFile(join(releases, "deploy-log.jsonl"), "utf8");
  assert.match(log, /"runId":"123"/);
} finally {
  await rm(deployRoot, { recursive: true, force: true });
}

console.log("deploy receiver tests passed");
