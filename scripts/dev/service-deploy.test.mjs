import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const deployScript = join(
  repoRoot,
  "infra/hetzner-core/server/scripts/deploy-application-services.sh",
);
const migrationScript = join(
  repoRoot,
  "infra/hetzner-core/server/scripts/apply-migrations.sh",
);
const installKeyScript = join(
  repoRoot,
  "infra/hetzner-core/server/scripts/install-github-deploy-key.sh",
);
const workflowPath = join(repoRoot, ".github/workflows/service-deploy.yml");
const gitSha = "a".repeat(40);
const shortSha = gitSha.slice(0, 7);

function runDeploy(root, archive, bin, extraEnv = {}) {
  return spawnSync(deployScript, ["deploy", gitSha, archive], {
    cwd: root,
    encoding: "utf8",
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      REEF_DEPLOY_DIR: root,
      REEF_DEPLOY_HEALTH_ATTEMPTS: "2",
      REEF_DEPLOY_HEALTH_DELAY_SECONDS: "1",
      FAKE_DOCKER_LOG: join(root, "docker.log"),
      FAKE_CURL_STATE: join(root, "curl.state"),
      ...extraEnv,
    },
  });
}

async function runForcedDeploy(root, archive, bin, extraEnv = {}) {
  return spawnSync(deployScript, ["--ssh-command"], {
    cwd: root,
    encoding: "utf8",
    input: await readFile(archive),
    env: {
      ...process.env,
      PATH: `${bin}:${process.env.PATH}`,
      REEF_DEPLOY_DIR: root,
      REEF_DEPLOY_HEALTH_ATTEMPTS: "2",
      REEF_DEPLOY_HEALTH_DELAY_SECONDS: "1",
      FAKE_DOCKER_LOG: join(root, "docker.log"),
      FAKE_CURL_STATE: join(root, "curl.state"),
      SSH_ORIGINAL_COMMAND: `deploy ${gitSha}`,
      ...extraEnv,
    },
  });
}

async function createFixture() {
  const root = await mkdtemp(join(tmpdir(), "reef-service-deploy-test-"));
  const bin = join(root, "bin");
  const migrations = join(root, "migration-source", "migrations");
  const archive = join(root, "migrations.tar.gz");
  await mkdir(bin, { recursive: true });
  await mkdir(join(root, "scripts"), { recursive: true });
  for (const domain of [
    "runtime",
    "auth",
    "admin",
    "boundary",
    "command_log",
    "orchestration",
    "settlement",
    "arena",
    "analytics",
  ]) {
    await mkdir(join(migrations, domain), { recursive: true });
    await writeFile(join(migrations, domain, "0001_test.sql"), "SELECT 1;\n");
  }
  await writeFile(
    join(root, ".env"),
    [
      "REEF_PLATFORM_RUNTIME_IMAGE=dills122/reef-arena-platform-runtime:sha-old",
      "REEF_MATCHING_ENGINE_IMAGE=dills122/reef-matching-engine:sha-old",
      "REEF_SIMULATOR_IMAGE=dills122/reef-simulator:sha-old",
      "REEF_AUTO_DEPLOY_IMAGE_NAMESPACE=dills122",
      "",
    ].join("\n"),
  );
  await writeFile(
    join(bin, "docker"),
    `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_DOCKER_LOG"
if [[ "\${1:-}" == "compose" && "\${2:-}" == "config" ]]; then
  printf '%s\\n' openbao matching-engine platform-runtime simulator
elif [[ "\${1:-}" == "compose" && "\${2:-}" == "ps" && "\${3:-}" == "-q" ]]; then
  printf '%s-id\\n' "\${4}"
elif [[ "\${1:-}" == "image" && "\${2:-}" == "inspect" ]]; then
  printf '%s\\n' "\${FAKE_IMAGE_REVISION:-${gitSha}}"
elif [[ "\${1:-}" == "inspect" ]]; then
  printf 'true\\n'
fi
cat >/dev/null || true
`,
  );
  await writeFile(
    join(bin, "curl"),
    `#!/usr/bin/env bash
set -euo pipefail
count=0
if [[ -f "$FAKE_CURL_STATE" ]]; then count="$(cat "$FAKE_CURL_STATE")"; fi
count=$((count + 1))
printf '%s\\n' "$count" > "$FAKE_CURL_STATE"
if [[ "\${FAKE_CURL_FAIL_AFTER_FIRST:-0}" == "1" && "$count" -gt 1 ]]; then
  exit 22
fi
if [[ "$*" == *"/v1/sys/health"* ]]; then
  printf '{"sealed":false}\\n'
else
  printf '{"status":"ok"}\\n'
fi
`,
  );
  await writeFile(
    join(bin, "flock"),
    `#!/usr/bin/env bash
exit 0
`,
  );
  await chmod(join(bin, "docker"), 0o755);
  await chmod(join(bin, "curl"), 0o755);
  await chmod(join(bin, "flock"), 0o755);
  await writeFile(join(root, "scripts", "apply-migrations.sh"), await readFile(migrationScript));
  await chmod(join(root, "scripts", "apply-migrations.sh"), 0o755);
  const tar = spawnSync("tar", ["-czf", archive, "-C", join(root, "migration-source"), "migrations"], {
    encoding: "utf8",
  });
  assert.equal(tar.status, 0, tar.stderr);
  return { root, bin, archive };
}

const fixture = await createFixture();
try {
  const result = runDeploy(fixture.root, fixture.archive, fixture.bin);
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);

  const envFile = await readFile(join(fixture.root, ".env"), "utf8");
  assert.match(
    envFile,
    new RegExp(`REEF_PLATFORM_RUNTIME_IMAGE=dills122/reef-arena-platform-runtime:sha-${shortSha}`),
  );
  assert.match(
    envFile,
    new RegExp(`REEF_MATCHING_ENGINE_IMAGE=dills122/reef-matching-engine:sha-${shortSha}`),
  );
  assert.match(
    envFile,
    new RegExp(`REEF_SIMULATOR_IMAGE=dills122/reef-simulator:sha-${shortSha}`),
  );

  const dockerLog = await readFile(join(fixture.root, "docker.log"), "utf8");
  assert.match(
    dockerLog,
    /compose up -d --no-deps --force-recreate matching-engine/,
  );
  assert.match(
    dockerLog,
    /compose up -d --no-deps --force-recreate platform-runtime/,
  );
  assert.doesNotMatch(dockerLog, /compose up .*openbao/);
  assert.doesNotMatch(dockerLog, /compose up .*simulator/);
  assert.match(dockerLog, /compose exec -T postgres psql/);
  assert.match(dockerLog, /compose exec -T postgres-admin psql/);
  assert.match(dockerLog, /compose exec -T postgres-analytics psql/);

  const deployLog = await readFile(
    join(fixture.root, "deployments", "application-services.jsonl"),
    "utf8",
  );
  assert.match(deployLog, new RegExp(`"gitSha":"${gitSha}"`));
  assert.match(deployLog, /"simulatorStarted":false/);
} finally {
  await rm(fixture.root, { recursive: true, force: true });
}

const forcedFixture = await createFixture();
try {
  const result = await runForcedDeploy(
    forcedFixture.root,
    forcedFixture.archive,
    forcedFixture.bin,
  );
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
} finally {
  await rm(forcedFixture.root, { recursive: true, force: true });
}

const rollbackFixture = await createFixture();
try {
  const before = await readFile(join(rollbackFixture.root, ".env"), "utf8");
  const result = runDeploy(
    rollbackFixture.root,
    rollbackFixture.archive,
    rollbackFixture.bin,
    { FAKE_CURL_FAIL_AFTER_FIRST: "1" },
  );
  assert.notEqual(result.status, 0);
  assert.match(
    result.stderr,
    /restoring previous image pins/,
    `${result.stdout}\n${result.stderr}\nstatus=${result.status} signal=${result.signal}`,
  );
  assert.equal(await readFile(join(rollbackFixture.root, ".env"), "utf8"), before);
  const dockerLog = await readFile(join(rollbackFixture.root, "docker.log"), "utf8");
  assert.doesNotMatch(dockerLog, /compose up .*openbao/);
} finally {
  await rm(rollbackFixture.root, { recursive: true, force: true });
}

const revisionFixture = await createFixture();
try {
  const before = await readFile(join(revisionFixture.root, ".env"), "utf8");
  const result = runDeploy(
    revisionFixture.root,
    revisionFixture.archive,
    revisionFixture.bin,
    { FAKE_IMAGE_REVISION: "b".repeat(40) },
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /image revision label does not match requested commit/);
  assert.equal(await readFile(join(revisionFixture.root, ".env"), "utf8"), before);
  const dockerLog = await readFile(join(revisionFixture.root, "docker.log"), "utf8");
  assert.doesNotMatch(dockerLog, /compose exec -T .* psql/);
  assert.doesNotMatch(dockerLog, /compose up /);
} finally {
  await rm(revisionFixture.root, { recursive: true, force: true });
}

const invalid = spawnSync(deployScript, ["deploy", "not-a-sha", "/does/not/exist"], {
  encoding: "utf8",
});
assert.notEqual(invalid.status, 0);
assert.match(invalid.stderr, /40 hexadecimal characters/);

const invalidForced = spawnSync(deployScript, ["--ssh-command"], {
  encoding: "utf8",
  env: { ...process.env, SSH_ORIGINAL_COMMAND: "bash -i" },
});
assert.notEqual(invalidForced.status, 0);
assert.match(invalidForced.stderr, /only accepts/);

const unsafeFixture = await createFixture();
try {
  const unsafeRoot = join(unsafeFixture.root, "unsafe-source", "migrations", "runtime");
  const unsafeArchive = join(unsafeFixture.root, "unsafe-migrations.tar.gz");
  await mkdir(unsafeRoot, { recursive: true });
  await symlink("/etc/passwd", join(unsafeRoot, "0002_link.sql"));
  const tar = spawnSync(
    "tar",
    ["-czf", unsafeArchive, "-C", join(unsafeFixture.root, "unsafe-source"), "migrations"],
    { encoding: "utf8" },
  );
  assert.equal(tar.status, 0, tar.stderr);
  const result = runDeploy(
    unsafeFixture.root,
    unsafeArchive,
    unsafeFixture.bin,
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /link or unsupported file type/);
  await assert.rejects(() => readFile(join(unsafeFixture.root, "docker.log")), /ENOENT/);
} finally {
  await rm(unsafeFixture.root, { recursive: true, force: true });
}

const workflow = await readFile(workflowPath, "utf8");
const imageWorkflow = await readFile(
  join(repoRoot, ".github/workflows/container-images.yml"),
  "utf8",
);
assert.match(workflow, /workflows:\s*\n\s*- Container Images/);
assert.match(workflow, /git merge-base --is-ancestor "\$TARGET_SHA" origin\/master/);
assert.match(workflow, /tailscale\/github-action@v4/);
assert.match(workflow, /"deploy \$TARGET_SHA" < "\$REEF_MIGRATION_ARCHIVE"/);
assert.match(workflow, /find migrations -type f -name '\*\.sql' -print/);
assert.match(workflow, /tar -czf "\$migration_archive" -T "\$migration_files"/);
assert.doesNotMatch(
  workflow,
  /tar -czf "\$migration_archive" -C scripts\/dev\/db migrations/,
);
assert.doesNotMatch(workflow, /openbao/i);
assert.match(imageWorkflow, /- 'scripts\/dev\/db\/migrations\/\*\*'/);
assert.match(imageWorkflow, /- '\.github\/workflows\/service-deploy\.yml'/);
assert.match(
  imageWorkflow,
  /\\\.github\/workflows\/\(container-images\|service-deploy\)\\\.yml\$/,
);

const keyFixture = await mkdtemp(join(tmpdir(), "reef-deploy-key-test-"));
try {
  const sshDir = join(keyFixture, ".ssh");
  const deployRoot = join(keyFixture, "reef");
  const keyPath = join(keyFixture, "deploy-key");
  await mkdir(sshDir, { recursive: true });
  await mkdir(join(deployRoot, "scripts"), { recursive: true });
  await writeFile(
    join(deployRoot, "scripts", "deploy-application-services.sh"),
    "#!/usr/bin/env bash\nexit 0\n",
  );
  await chmod(join(deployRoot, "scripts", "deploy-application-services.sh"), 0o755);
  await writeFile(join(sshDir, "authorized_keys"), "ssh-ed25519 AAAAC3NzaExisting operator-key\n");
  const generated = spawnSync(
    "ssh-keygen",
    ["-q", "-t", "ed25519", "-N", "", "-f", keyPath, "-C", "test-deploy"],
    { encoding: "utf8" },
  );
  assert.equal(generated.status, 0, generated.stderr);
  const publicKey = await readFile(`${keyPath}.pub`, "utf8");
  const installed = spawnSync(installKeyScript, [], {
    encoding: "utf8",
    input: publicKey,
    env: {
      ...process.env,
      REEF_DEPLOY_DIR: deployRoot,
      REEF_DEPLOY_AUTHORIZED_KEYS: join(sshDir, "authorized_keys"),
    },
  });
  assert.equal(installed.status, 0, installed.stderr);
  const authorizedKeys = await readFile(join(sshDir, "authorized_keys"), "utf8");
  assert.match(authorizedKeys, /operator-key/);
  assert.match(
    authorizedKeys,
    new RegExp(
      `restrict,command="${deployRoot}/scripts/deploy-application-services\\.sh --ssh-command"`,
    ),
  );
  assert.match(authorizedKeys, /reef-github-actions-deploy/);
} finally {
  await rm(keyFixture, { recursive: true, force: true });
}

console.log("application service deploy tests passed");
