import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmod, mkdir, mkdtemp, readFile, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
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
      FAKE_PULL_STATE: join(root, "pull.state"),
      FAKE_SLEEP_LOG: join(root, "sleep.log"),
      FAKE_TIMEOUT_LOG: join(root, "timeout.log"),
      FAKE_TIMEOUT_STATE: join(root, "timeout.state"),
      FAKE_PSQL_INPUT_LOG: join(root, "psql-input.log"),
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
      FAKE_PULL_STATE: join(root, "pull.state"),
      FAKE_SLEEP_LOG: join(root, "sleep.log"),
      FAKE_TIMEOUT_LOG: join(root, "timeout.log"),
      FAKE_TIMEOUT_STATE: join(root, "timeout.state"),
      FAKE_PSQL_INPUT_LOG: join(root, "psql-input.log"),
      FAKE_CURL_STATE: join(root, "curl.state"),
      SSH_ORIGINAL_COMMAND: `deploy ${gitSha}`,
      ...extraEnv,
    },
  });
}

async function createFixture({ includeDirtyQueueMigration = false } = {}) {
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
  if (includeDirtyQueueMigration) {
    await writeFile(
      join(migrations, "runtime", "0069_logged_projection_dirty_queues.sql"),
      [
        "ALTER TABLE runtime.order_lifecycle_dirty SET LOGGED;",
        "ALTER TABLE runtime.market_data_snapshot_dirty SET LOGGED;",
        "",
      ].join("\n"),
    );
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
if [[ "$*" == "compose --profile manual config --services" ]]; then
  printf '%s\\n' openbao matching-engine platform-runtime simulator
elif [[ "\${1:-}" == "ps" && "$*" == *"label=com.docker.compose.service=platform-runtime"* ]]; then
  if [[ "\${FAKE_RUNTIME_CONTAINERS:-present}" != "empty" ]]; then
    echo runtime-stopped-id
    echo runtime-second-id
  fi
elif [[ "\${1:-}" == "compose" && "\${2:-}" == "ps" ]]; then
  printf '%s-id\\n' "\${@: -1}"
elif [[ "\${1:-}" == "image" && "\${2:-}" == "inspect" ]]; then
  printf '%s\\n' "\${FAKE_IMAGE_REVISION:-${gitSha}}"
elif [[ "\${1:-}" == "pull" ]]; then
  pull_count=0
  if [[ -f "$FAKE_PULL_STATE" ]]; then pull_count="$(cat "$FAKE_PULL_STATE")"; fi
  pull_count=$((pull_count + 1))
  printf '%s\\n' "$pull_count" > "$FAKE_PULL_STATE"
  if (( pull_count >= \${FAKE_PULL_FAIL_START:-1} && pull_count < \${FAKE_PULL_FAIL_START:-1} + \${FAKE_PULL_FAIL_ATTEMPTS:-0} )); then
    if [[ "\${FAKE_PULL_FAILURE:-transient}" == "auth" ]]; then
      echo 'unauthorized: authentication required' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "auth-with-503" ]]; then
      echo 'unauthorized: authentication required; upstream status 503' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "manifest" ]]; then
      echo 'manifest unknown: manifest unknown' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "registry" ]]; then
      echo 'received unexpected HTTP status: 503 Service Unavailable' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "registry500" ]]; then
      echo 'received unexpected HTTP status: 500 Internal Server Error' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "registry-head" ]]; then
      echo 'unexpected status from HEAD request to https://registry-1.docker.io/v2/reef/manifests/sha: 502 Bad Gateway' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "rate-limit" ]]; then
      echo 'received unexpected HTTP status: 429 Too Many Requests' >&2
    elif [[ "\${FAKE_PULL_FAILURE:-transient}" == "network" ]]; then
      echo 'dial tcp: connect: network is unreachable' >&2
    else
      echo 'Get "https://registry-1.docker.io/v2/": net/http: TLS handshake timeout' >&2
    fi
    exit 1
  fi
  echo "pulled $2"
elif [[ "\${1:-}" == "inspect" ]]; then
  if [[ "$*" == *"runtime-stopped-id"* ]]; then
    printf 'exited\\n'
  elif [[ "$*" == *"runtime-second-id"* ]]; then
    printf '%s\\n' "\${FAKE_RUNTIME_SECOND_STATUS:-running}"
  else
    printf 'true\\n'
  fi
fi
if [[ "\${1:-}" == "compose" && "\${2:-}" == "exec" ]]; then
  cat >> "$FAKE_PSQL_INPUT_LOG" || true
else
  cat >/dev/null || true
fi
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
  await writeFile(
    join(bin, "sleep"),
    `#!/usr/bin/env bash
printf '%s\\n' "$1" >> "$FAKE_SLEEP_LOG"
`,
  );
  await writeFile(
    join(bin, "timeout"),
    `#!/usr/bin/env bash
set -euo pipefail
printf '%s\\n' "$*" >> "$FAKE_TIMEOUT_LOG"
count=0
if [[ -f "$FAKE_TIMEOUT_STATE" ]]; then count="$(cat "$FAKE_TIMEOUT_STATE")"; fi
count=$((count + 1))
printf '%s\\n' "$count" > "$FAKE_TIMEOUT_STATE"
if (( count <= \${FAKE_TIMEOUT_FAIL_ATTEMPTS:-0} )); then exit "\${FAKE_TIMEOUT_FAILURE_STATUS:-124}"; fi
shift 3
exec "$@"
`,
  );
  await chmod(join(bin, "docker"), 0o755);
  await chmod(join(bin, "curl"), 0o755);
  await chmod(join(bin, "flock"), 0o755);
  await chmod(join(bin, "sleep"), 0o755);
  await chmod(join(bin, "timeout"), 0o755);
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
  assert.match(dockerLog, /compose --profile manual config --services/);
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

for (const failure of ["transient", "registry", "registry500", "registry-head", "rate-limit", "network"]) {
  const transientPullFixture = await createFixture();
  try {
    const result = runDeploy(
      transientPullFixture.root,
      transientPullFixture.archive,
      transientPullFixture.bin,
      { FAKE_PULL_FAIL_ATTEMPTS: "2", FAKE_PULL_FAILURE: failure },
    );
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.match(result.stderr, /retrying in 2s/);
    assert.match(result.stderr, /retrying in 4s/);
    assert.equal(await readFile(join(transientPullFixture.root, "sleep.log"), "utf8"), "2\n4\n");
    assert.match(await readFile(join(transientPullFixture.root, "timeout.log"), "utf8"), /^--signal=TERM --kill-after=5s 120s docker pull /);
    const dockerLog = await readFile(join(transientPullFixture.root, "docker.log"), "utf8");
    assert.equal((dockerLog.match(/pull dills122\/reef-matching-engine:sha-/g) ?? []).length, 3);
    assert.equal((dockerLog.match(/pull dills122\/reef-arena-platform-runtime:sha-/g) ?? []).length, 1);
    assert.equal((dockerLog.match(/pull dills122\/reef-simulator:sha-/g) ?? []).length, 1);
    assert.match(dockerLog, /compose up -d --no-deps --force-recreate platform-runtime/);
  } finally {
    await rm(transientPullFixture.root, { recursive: true, force: true });
  }
}

const timeoutFixture = await createFixture();
try {
  const result = runDeploy(
    timeoutFixture.root,
    timeoutFixture.archive,
    timeoutFixture.bin,
    { FAKE_TIMEOUT_FAIL_ATTEMPTS: "2" },
  );
  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /image pull timed out after 120s/);
  assert.equal(await readFile(join(timeoutFixture.root, "sleep.log"), "utf8"), "2\n4\n");
  assert.equal((await readFile(join(timeoutFixture.root, "timeout.log"), "utf8")).split("\n").filter(Boolean).length, 5);
} finally {
  await rm(timeoutFixture.root, { recursive: true, force: true });
}

const exhaustedTimeoutFixture = await createFixture();
try {
  const before = await readFile(join(exhaustedTimeoutFixture.root, ".env"), "utf8");
  const result = runDeploy(
    exhaustedTimeoutFixture.root,
    exhaustedTimeoutFixture.archive,
    exhaustedTimeoutFixture.bin,
    { FAKE_TIMEOUT_FAIL_ATTEMPTS: "10" },
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /image pull exhausted 3 attempts/);
  assert.equal(await readFile(join(exhaustedTimeoutFixture.root, ".env"), "utf8"), before);
  const dockerLog = await readFile(join(exhaustedTimeoutFixture.root, "docker.log"), "utf8");
  assert.doesNotMatch(dockerLog, /compose exec -T .* psql|compose up /);
  assert.equal((await readFile(join(exhaustedTimeoutFixture.root, "timeout.log"), "utf8")).split("\n").filter(Boolean).length, 3);
} finally {
  await rm(exhaustedTimeoutFixture.root, { recursive: true, force: true });
}

const killedPullFixture = await createFixture();
try {
  const before = await readFile(join(killedPullFixture.root, ".env"), "utf8");
  const result = runDeploy(
    killedPullFixture.root,
    killedPullFixture.archive,
    killedPullFixture.bin,
    { FAKE_TIMEOUT_FAIL_ATTEMPTS: "10", FAKE_TIMEOUT_FAILURE_STATUS: "137" },
  );
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /exit 137\); refusing retry/);
  assert.equal(await readFile(join(killedPullFixture.root, ".env"), "utf8"), before);
  assert.equal((await readFile(join(killedPullFixture.root, "timeout.log"), "utf8")).split("\n").filter(Boolean).length, 1);
  const dockerLog = await readFile(join(killedPullFixture.root, "docker.log"), "utf8");
  assert.doesNotMatch(dockerLog, /compose exec -T .* psql|compose up /);
  await assert.rejects(() => readFile(join(killedPullFixture.root, "sleep.log")), /ENOENT/);
} finally {
  await rm(killedPullFixture.root, { recursive: true, force: true });
}

const latePullFixture = await createFixture();
try {
  const before = await readFile(join(latePullFixture.root, ".env"), "utf8");
  const result = runDeploy(
    latePullFixture.root,
    latePullFixture.archive,
    latePullFixture.bin,
    { FAKE_PULL_FAIL_START: "3", FAKE_PULL_FAIL_ATTEMPTS: "10" },
  );
  assert.notEqual(result.status, 0);
  assert.equal(await readFile(join(latePullFixture.root, ".env"), "utf8"), before);
  const dockerLog = await readFile(join(latePullFixture.root, "docker.log"), "utf8");
  assert.equal((dockerLog.match(/pull dills122\/reef-simulator:sha-/g) ?? []).length, 3);
  assert.doesNotMatch(dockerLog, /compose exec -T .* psql|compose up /);
} finally {
  await rm(latePullFixture.root, { recursive: true, force: true });
}

for (const [failure, attempts] of [["transient", 3], ["auth", 1], ["auth-with-503", 1], ["manifest", 1]]) {
  const failedPullFixture = await createFixture();
  try {
    const before = await readFile(join(failedPullFixture.root, ".env"), "utf8");
    const result = runDeploy(
      failedPullFixture.root,
      failedPullFixture.archive,
      failedPullFixture.bin,
      { FAKE_PULL_FAIL_ATTEMPTS: "10", FAKE_PULL_FAILURE: failure },
    );
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, failure === "transient" ? /exhausted 3 attempts/ : /without a retryable registry error/);
    assert.equal(await readFile(join(failedPullFixture.root, ".env"), "utf8"), before);
    const dockerLog = await readFile(join(failedPullFixture.root, "docker.log"), "utf8");
    assert.equal((dockerLog.match(/pull dills122\/reef-matching-engine:sha-/g) ?? []).length, attempts);
    assert.doesNotMatch(dockerLog, /compose exec -T .* psql|compose up /);
    if (failure !== "transient") {
      await assert.rejects(() => readFile(join(failedPullFixture.root, "sleep.log")), /ENOENT/);
    }
  } finally {
    await rm(failedPullFixture.root, { recursive: true, force: true });
  }
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

const dirtyQueueFixture = await createFixture({ includeDirtyQueueMigration: true });
try {
  const automatic = await runForcedDeploy(
    dirtyQueueFixture.root,
    dirtyQueueFixture.archive,
    dirtyQueueFixture.bin,
    { REEF_APPLY_RUNTIME_0069: "1" },
  );
  assert.equal(automatic.status, 0, `${automatic.stdout}\n${automatic.stderr}`);
  assert.match(automatic.stdout, /skip runtime\/0069_logged_projection_dirty_queues\.sql/);
  const automaticLog = await readFile(join(dirtyQueueFixture.root, "docker.log"), "utf8");
  assert.match(automaticLog, /compose exec -T postgres psql/);
  assert.match(automaticLog, /compose up .*platform-runtime/);
  const automaticSql = await readFile(join(dirtyQueueFixture.root, "psql-input.log"), "utf8");
  assert.doesNotMatch(automaticSql, /ALTER TABLE runtime\..* SET LOGGED/);

  const manualEnv = {
    ...process.env,
    PATH: `${dirtyQueueFixture.bin}:${process.env.PATH}`,
    REEF_DEPLOY_DIR: dirtyQueueFixture.root,
    REEF_MIGRATIONS_ROOT: join(dirtyQueueFixture.root, "migration-source", "migrations"),
    REEF_APPLY_RUNTIME_0069: "1",
    FAKE_DOCKER_LOG: join(dirtyQueueFixture.root, "docker.log"),
    FAKE_PSQL_INPUT_LOG: join(dirtyQueueFixture.root, "psql-input.log"),
  };
  const active = spawnSync(migrationScript, [], {
    encoding: "utf8",
    env: { ...manualEnv, FAKE_RUNTIME_SECOND_STATUS: "running" },
  });
  assert.notEqual(active.status, 0, `${active.stdout}\n${active.stderr}`);
  assert.match(active.stderr, /platform-runtime to be stopped/);

  const restarting = spawnSync(migrationScript, [], {
    encoding: "utf8",
    env: { ...manualEnv, FAKE_RUNTIME_SECOND_STATUS: "restarting" },
  });
  assert.notEqual(restarting.status, 0, `${restarting.stdout}\n${restarting.stderr}`);
  assert.match(restarting.stderr, /platform-runtime to be stopped/);

  const missingContainer = spawnSync(migrationScript, [], {
    encoding: "utf8",
    env: { ...manualEnv, FAKE_RUNTIME_CONTAINERS: "empty" },
  });
  assert.notEqual(missingContainer.status, 0);
  assert.match(missingContainer.stderr, /stopped platform-runtime container or explicit fresh-bootstrap opt-in/);

  const quiesced = spawnSync(migrationScript, [], {
    encoding: "utf8",
    env: { ...manualEnv, FAKE_RUNTIME_SECOND_STATUS: "exited" },
  });
  assert.equal(quiesced.status, 0, `${quiesced.stdout}\n${quiesced.stderr}`);
  const sql = await readFile(join(dirtyQueueFixture.root, "psql-input.log"), "utf8");
  const dockerLog = await readFile(join(dirtyQueueFixture.root, "docker.log"), "utf8");
  assert.match(dockerLog, /compose exec -T postgres psql -U postgres -d reef -v ON_ERROR_STOP=1 -X -q/);
  assert.match(dockerLog, /ps -a --filter label=com\.docker\.compose\.service=platform-runtime/);
  assert.match(sql, /BEGIN;\nSET LOCAL lock_timeout = '2s';/);
  assert.match(sql, /SET LOCAL statement_timeout = '30s';/);
  assert.match(sql, /ALTER TABLE runtime\.order_lifecycle_dirty SET LOGGED/);
  assert.match(sql, /ALTER TABLE runtime\.market_data_snapshot_dirty SET LOGGED/);

  const freshBootstrap = spawnSync(migrationScript, [], {
    encoding: "utf8",
    env: {
      ...manualEnv,
      FAKE_RUNTIME_CONTAINERS: "empty",
      REEF_RUNTIME_0069_FRESH_BOOTSTRAP: "1",
    },
  });
  assert.equal(freshBootstrap.status, 0, `${freshBootstrap.stdout}\n${freshBootstrap.stderr}`);
} finally {
  await rm(dirtyQueueFixture.root, { recursive: true, force: true });
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
assert.doesNotMatch(workflow, /Gate runtime 0069 rollout|REEF_RUNTIME_0069_ROLLOUT_COMPLETE/);
assert.match(workflow, /workflows:\s*\n\s*- Container Images/);
assert.match(workflow, /git merge-base --is-ancestor "\$TARGET_SHA" origin\/master/);
assert.match(workflow, /tailscale\/github-action@[a-f0-9]{40} # v4/);
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

const runbook = await readFile(join(repoRoot, "infra/hetzner-core/OPERATIONS_RUNBOOK.md"), "utf8");
const rolloutBlock = runbook.match(/```bash\n(bash -euo pipefail <<'REEF_DEPLOY_SCRIPT_SYNC'\n[\s\S]*?\nREEF_DEPLOY_SCRIPT_SYNC)\n```/)?.[1];
assert.ok(rolloutBlock, "host-script rollout command block must remain executable");
const rolloutFixture = await mkdtemp(join(tmpdir(), "reef-deploy-rollout-test-"));
try {
  const bin = join(rolloutFixture, "bin");
  const log = join(rolloutFixture, "rollout.log");
  const publicKey = join(rolloutFixture, ".ssh", "reef-github-deploy.pub");
  const digest = "b".repeat(64);
  await mkdir(bin, { recursive: true });
  await writeFile(join(bin, "make"), `#!/usr/bin/env bash
printf 'make %s\\n' "$*" >> "$ROLLOUT_LOG"
`);
  await writeFile(join(bin, "shasum"), `#!/usr/bin/env bash
printf '%s  script\\n' "$FAKE_LOCAL_DIGEST"
`);
  await writeFile(join(bin, "ssh"), `#!/usr/bin/env bash
printf 'ssh %s\\n' "$2" >> "$ROLLOUT_LOG"
if [[ "$2" == *sha256sum* ]]; then
  printf '%s  script\\n' "$FAKE_REMOTE_DIGEST"
else
  printf 'timeout (GNU coreutils) 9.0\\n'
fi
`);
  for (const command of ["make", "shasum", "ssh"]) {
    await chmod(join(bin, command), 0o755);
  }
  const rolloutEnv = {
    ...process.env,
    HOME: rolloutFixture,
    PATH: `${bin}:${process.env.PATH}`,
    REEF_HETZNER_HOST: "reef-test-host",
    ROLLOUT_LOG: log,
    FAKE_LOCAL_DIGEST: digest,
    FAKE_REMOTE_DIGEST: "c".repeat(64),
  };
  const missingKey = spawnSync("bash", ["-c", rolloutBlock], {
    cwd: repoRoot, encoding: "utf8", env: rolloutEnv,
  });
  assert.notEqual(missingKey.status, 0);
  assert.match(missingKey.stderr, /dedicated GitHub deploy public key is missing/);
  await assert.rejects(() => readFile(log), /ENOENT/);

  await mkdir(dirname(publicKey), { recursive: true });
  await writeFile(publicKey, "ssh-ed25519 test-key reef-test\n");
  const mismatch = spawnSync("bash", ["-c", rolloutBlock], {
    cwd: repoRoot, encoding: "utf8", env: rolloutEnv,
  });
  assert.notEqual(mismatch.status, 0);
  assert.match(mismatch.stderr, /host deploy script digest mismatch/);
  assert.doesNotMatch(await readFile(log, "utf8"), /timeout --version/);

  await writeFile(log, "");
  const matched = spawnSync("bash", ["-c", rolloutBlock], {
    cwd: repoRoot, encoding: "utf8", env: { ...rolloutEnv, FAKE_REMOTE_DIGEST: digest },
  });
  assert.equal(matched.status, 0, `${matched.stdout}\n${matched.stderr}`);
  assert.match(await readFile(log, "utf8"), /timeout --version/);
} finally {
  await rm(rolloutFixture, { recursive: true, force: true });
}

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
