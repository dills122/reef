import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { applyStackProfile } from "./dev-stack-profiles.mjs";
import { applyStressProfile } from "./dev-stress-profiles.mjs";

const originalEnv = { ...process.env };
const originalLog = console.log;

try {
  runTest("runtime-nodb stack profile disables durable hot-path storage", () => {
    withQuietConsole(() => {
      const afterUp = applyStackProfile("runtime-nodb");

      assert.deepEqual(afterUp, []);
      assert.equal(process.env.RUNTIME_PERSISTENCE, "noop");
      assert.equal(process.env.EXTERNAL_API_IDEMPOTENCY_STORE, "inmemory");
      assert.equal(process.env.EXTERNAL_API_COMMAND_LOG_MODE, "disabled");
      assert.equal(process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE, "sync-result");
      assert.equal(process.env.EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED, "false");
      assert.equal(process.env.STREAM_ACK_WORKER_ENABLED, "false");
      assert.equal(process.env.STREAM_ACK_PROJECTOR_ENABLED, "false");
      assert.equal(process.env.EXTERNAL_API_ABUSE_BREAKER_MODE, "off");
    });
  });

  runTest("captured-ack stack profile derives intake backpressure from worker capacity", () => {
    withQuietConsole(() => {
      process.env.EXTERNAL_API_COMMAND_ASYNC_WORKER_THREADS = "3";
      process.env.EXTERNAL_API_COMMAND_ASYNC_WORKER_BATCH_SIZE = "100";

      applyStackProfile("captured-ack");

      assert.equal(process.env.EXTERNAL_API_COMMAND_LOG_MODE, "postgres");
      assert.equal(process.env.EXTERNAL_API_COMMAND_PROCESSING_MODE, "captured-ack");
      assert.equal(process.env.EXTERNAL_API_COMMAND_LOG_PAYLOAD_MODE, "side-table");
      assert.equal(process.env.EXTERNAL_API_COMMAND_ASYNC_WORKER_ENABLED, "true");
      assert.equal(process.env.EXTERNAL_API_COMMAND_INTAKE_MAX_ACTIVE_COMMANDS, "600");
      assert.equal(process.env.EXTERNAL_API_COMMAND_INTAKE_BACKPRESSURE_SAMPLE_MS, "100");
    });
  });

  runTest("stream-direct no-db stack profile composes direct-stream and stream-ack defaults", () => {
    withQuietConsole(() => {
      process.env.STREAM_ACK_LOG_PROVIDER = "redpanda";
      process.env.DEV_COMPOSE_PROFILES = "venue-event-materializer";

      const afterUp = applyStackProfile("stream-direct-nodb");

      assert.equal(afterUp.length, 1);
      assert.equal(process.env.RUNTIME_PERSISTENCE, "noop");
      assert.equal(process.env.STREAM_ACK_INTAKE_STORE, "inmemory");
      assert.equal(process.env.STREAM_ACK_COMMAND_STREAM, "REEF_DIRECT_NODB_COMMANDS_V2");
      assert.equal(process.env.STREAM_ACK_SUBJECT_PREFIX, "reef.direct.nodb.v2.cmd.v1");
      assert.equal(process.env.STREAM_ACK_PARTITION_COUNT, "16");
      assert.equal(process.env.MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS, "0..15");
      assert.equal(process.env.MATCHING_ENGINE_DIRECT_STREAM_ENABLED, "true");
      assert.equal(process.env.STREAM_ACK_WORKER_ENABLED, "false");
      assert.equal(process.env.STREAM_ACK_PROJECTOR_ENABLED, "false");
      assert.equal(process.env.RUNTIME_DB_POOL_STREAM_INTAKE_API_MAX, "64");
      assert.equal(process.env.DEV_COMPOSE_PROFILES, "venue-event-materializer,stream-ack,redpanda");
    });
  });

  runTest("stream-ack stress profile writes generated spread session config", () => {
    withQuietConsole(() => {
      const artifactDir = mkdtempSync(join(tmpdir(), "reef-stream-ack-profile-"));
      process.env.DEV_STRESS_ARTIFACT_DIR = artifactDir;
      process.env.DEV_STRESS_STREAM_ACK_INSTRUMENTS = "2";

      applyStressProfile("stream-ack");

      assert.equal(process.env.PLATFORM_INTERNAL_HTTP_MODE, "enabled");
      assert.equal(process.env.DEV_STRESS_PROFILE, "stream-submit");
      assert.equal(process.env.DEV_STRESS_RATES, "1000,2500,5000");
      assert.equal(process.env.DEV_STRESS_CAPTURE_STREAM_ACK_WORKERS, "1");
      assert.equal(process.env.DEV_STRESS_SESSION_CONFIG, join(artifactDir, "stream-ack-submit-spread.yaml"));

      const config = readFileSync(process.env.DEV_STRESS_SESSION_CONFIG, "utf8");
      assert.match(config, /scenarioRunId: stream-ack-submit-stress/);
      assert.match(config, /instrumentId: STK001/);
      assert.match(config, /instrumentId: STK002/);
      assert.doesNotMatch(config, /instrumentId: STK003/);
    });
  });

  runTest("stream-direct no-db stress profile keeps direct-drain diagnostics enabled", () => {
    withQuietConsole(() => {
      const artifactDir = mkdtempSync(join(tmpdir(), "reef-stream-direct-profile-"));
      process.env.DEV_STRESS_ARTIFACT_DIR = artifactDir;
      process.env.DEV_STRESS_STREAM_DIRECT_INSTRUMENTS = "1";

      applyStressProfile("stream-direct-nodb");

      assert.equal(process.env.DEV_STRESS_RATES, "5000,10000,15000,20000");
      assert.equal(process.env.DEV_STRESS_DURATION, "90s");
      assert.equal(process.env.DEV_STRESS_CAPTURE_STREAM_DIRECT, "1");
      assert.equal(process.env.DEV_STRESS_STREAM_DIRECT_DRAIN_WAIT_MS, "30000");
      assert.equal(process.env.DEV_STRESS_SCENARIO_ID, "stream-direct-nodb:submit");
      assert.equal(process.env.DEV_STRESS_SESSION_CONFIG, join(artifactDir, "stream-direct-submit-spread.yaml"));

      const config = readFileSync(process.env.DEV_STRESS_SESSION_CONFIG, "utf8");
      assert.match(config, /scenarioRunId: stream-direct-nodb-submit-stress/);
      assert.match(config, /duration: 90s/);
      assert.match(config, /traceCheckLimit: 0/);
    });
  });

  console.log("dev profile checks passed");
} finally {
  process.env = originalEnv;
  console.log = originalLog;
}

function runTest(_name, callback) {
  process.env = { ...originalEnv };
  console.log = originalLog;
  callback();
  process.env = { ...originalEnv };
  console.log = originalLog;
}

function withQuietConsole(callback) {
  console.log = () => {};
  callback();
}
