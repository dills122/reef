import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";
import test from "node:test";

test("post-match workers run in dedicated processes with legacy projection disabled", () => {
  const command = spawnSync("docker", ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml",
    "--profile", "postmatch", "--profile", "postmatch-workers", "config", "--format", "json"], {
    encoding: "utf8",
    env: { ...process.env, POSTMATCH_SHADOW_WORKERS_ENABLED: "true",
      POSTMATCH_AUDIT_SHADOW_ENABLED: "true",
      POSTMATCH_SETTLEMENT_INTAKE_ENABLED: "true",
      POSTMATCH_SETTLEMENT_OBLIGATIONS_ENABLED: "true",
      POSTMATCH_SETTLEMENT_TRANSITION_ENABLED: "true" },
  });
  assert.equal(command.status, 0, command.stderr);
  const services = JSON.parse(command.stdout).services;
  const projectorNames = [0, 1, 2, 3].map(index => `platform-projector-${index}`);
  const liveNames = [0, 1, 2, 3].map(index => `platform-postmatch-live-${index}`);
  const settlementNames = [0, 1, 2, 3].map(index => `platform-postmatch-settlement-${index}`);
  for (const name of projectorNames) {
    assert.equal(services[name].environment.PLATFORM_RUNTIME_ROLE, "projector");
    assert.equal(services[name].environment.POSTMATCH_SHADOW_WORKERS_ENABLED, "false");
    assert.equal(services[name].environment.POSTMATCH_AUDIT_SHADOW_ENABLED, "false");
    assert.equal(services[name].environment.POSTMATCH_SETTLEMENT_TRANSITION_ENABLED, "false");
  }
  for (const name of liveNames) {
    const env = services[name].environment;
    assert.equal(env.PLATFORM_RUNTIME_ROLE, "postmatch");
    assert.equal(env.STREAM_ACK_PROJECTOR_ENABLED, "false");
    assert.equal(env.POSTMATCH_SHADOW_WORKERS_ENABLED, "true");
    assert.equal(env.POSTMATCH_AUDIT_SHADOW_ENABLED, "true");
    assert.equal(env.POSTMATCH_SETTLEMENT_TRANSITION_ENABLED, "false");
    assert.equal(env.RUNTIME_DB_POOL_POSTMATCH_SOURCE_MAX, "2");
    assert.equal(env.RUNTIME_DB_POOL_POSTMATCH_AUDIT_SOURCE_MAX, "2");
  }
  for (const name of settlementNames) {
    const env = services[name].environment;
    assert.equal(env.PLATFORM_RUNTIME_ROLE, "postmatch");
    assert.equal(env.STREAM_ACK_PROJECTOR_ENABLED, "false");
    assert.equal(env.POSTMATCH_SHADOW_WORKERS_ENABLED, "false");
    assert.equal(env.POSTMATCH_AUDIT_SHADOW_ENABLED, "false");
    assert.equal(env.POSTMATCH_SETTLEMENT_INTAKE_ENABLED, "true");
    assert.equal(env.POSTMATCH_SETTLEMENT_OBLIGATIONS_ENABLED, "true");
    assert.equal(env.POSTMATCH_SETTLEMENT_TRANSITION_ENABLED, "true");
    assert.equal(env.RUNTIME_DB_POOL_SETTLEMENT_INTAKE_SOURCE_MAX, "2");
    assert.equal(env.RUNTIME_DB_POOL_SETTLEMENT_POLICY_SOURCE_MAX, "2");
    assert.equal(env.RUNTIME_DB_POOL_SETTLEMENT_TRANSITION_SOURCE_MAX, "2");
  }
  const all = [...liveNames, ...settlementNames].map(name => services[name].environment.POSTMATCH_WORKER_PARTITIONS);
  assert.deepEqual(all.slice(0, 4), ["0,1,2,3", "4,5,6,7", "8,9,10,11", "12,13,14,15"]);
  assert.deepEqual(all.slice(4), all.slice(0, 4));
});

test("matched benchmark topology has six materializers and sixteen distinct projector owners", () => {
  const command = spawnSync("docker", ["compose", "-f", "compose.base.yml", "-f", "compose.local.yml",
    "-f", "compose.benchmark-scale.yml", "--profile", "benchmark-scale", "--profile", "redpanda",
    "--profile", "venue-event-materializer", "--profile", "venue-event-materializer-scaled",
    "config", "--format", "json"], { encoding: "utf8", env: { ...process.env,
      STREAM_ACK_PROJECTOR_0_PARTITIONS: "0", STREAM_ACK_PROJECTOR_1_PARTITIONS: "1",
      STREAM_ACK_PROJECTOR_2_PARTITIONS: "2", STREAM_ACK_PROJECTOR_3_PARTITIONS: "3" } });
  assert.equal(command.status, 0, command.stderr);
  const services = JSON.parse(command.stdout).services;
  const owners = Array.from({ length: 16 }, (_, index) => {
    const environment = services[`platform-projector-${index}`].environment;
    assert.equal(environment.PLATFORM_RUNTIME_ROLE, "projector");
    assert.equal(environment.POSTMATCH_SHADOW_WORKERS_ENABLED, "false");
    return environment.STREAM_ACK_PROJECTOR_PARTITIONS;
  });
  assert.deepEqual(owners, Array.from({ length: 16 }, (_, index) => String(index)));
  for (let index = 0; index < 6; index += 1) {
    const name = index === 0 ? "platform-materializer" : `platform-materializer-${index}`;
    assert.equal(services[name].environment.PLATFORM_RUNTIME_ROLE, "materializer");
  }
});

test("matched benchmark resets prior volumes before smoke and again before measured load", () => {
  const script = readFileSync(new URL("./do-benchmark-host.sh", import.meta.url), "utf8");
  assert.match(script, /REEF_DO_MATCHED_SOURCE_PG_MAX_CONNECTIONS="\$\{REEF_DO_MATCHED_SOURCE_PG_MAX_CONNECTIONS:-320\}"/);
  assert.match(script, /REEF_DO_MATCHED_SETTLEMENT_PG_MAX_CONNECTIONS="\$\{REEF_DO_MATCHED_SETTLEMENT_PG_MAX_CONNECTIONS:-240\}"/);
  assert.match(script, /export REEF_PG_MAX_CONNECTIONS="\$REEF_DO_MATCHED_SOURCE_PG_MAX_CONNECTIONS"\s+export REEF_SETTLEMENT_PG_MAX_CONNECTIONS="\$REEF_DO_MATCHED_SETTLEMENT_PG_MAX_CONNECTIONS"\s+run_stage reset-before-matched-materializer-smoke/);
  assert.match(script, /if \[ "\$\{REEF_DO_MATCHED_SKIP_SMOKE:-0\}" != "1" \]; then\s+run_stage make-dev-smoke-venue-event-materializer/);
  assert.match(script, /run_stage make-dev-smoke-venue-event-materializer make dev-smoke-venue-event-materializer\s+fi\s+run_stage reset-after-materializer-smoke/);
  const before = script.indexOf("run_stage reset-before-matched-materializer-smoke");
  const smoke = script.indexOf("run_stage make-dev-smoke-venue-event-materializer");
  const after = script.indexOf("run_stage reset-after-materializer-smoke");
  assert.ok(before > 0 && before < smoke && smoke < after);
  assert.match(script.slice(before, smoke), /down --volumes --remove-orphans/);
  assert.match(script.slice(after, after + 160), /down --volumes --remove-orphans/);
});
