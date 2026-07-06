import assert from "node:assert/strict";

import { validateStreamProfile } from "./lib/stream-profile-guard.mjs";

const originalEnv = { ...process.env };

try {
  withEnv(baseStreamEnv(), () => {
    assert.equal(validateStreamProfile("stream-direct-nodb", { throwOnError: false }).ok, true);
  });

  withEnv({ ...baseStreamEnv(), STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES: "0" }, () => {
    const result = validateStreamProfile("stream-direct-nodb", { throwOnError: false });
    assert.equal(result.ok, false);
    assert.ok(result.issues.some((issue) => issue.includes("STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES")));
  });

  withEnv({ ...baseStreamEnv(), STREAM_ACK_PUBLISHER: "noop" }, () => {
    assert.equal(validateStreamProfile("noop-ceiling", { throwOnError: false }).ok, true);
    assert.equal(validateStreamProfile("materializer-soak", { throwOnError: false }).ok, false);
  });

  withEnv({
    ...baseStreamEnv(),
    STREAM_ACK_LOG_PROVIDER: "redpanda",
    VENUE_EVENT_MATERIALIZER_ENABLED: "true",
    DEV_STRESS_CAPTURE_STREAM_DIRECT: "1",
    DEV_STRESS_CAPTURE_VENUE_EVENT_MATERIALIZER: "1",
    DEV_STRESS_FAIL_ON_VENUE_EVENT_MATERIALIZER_FAILURES: "1",
    VENUE_EVENT_MATERIALIZER_BATCH_SIZE: "1000",
    MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT: "250000",
  }, () => {
    assert.equal(validateStreamProfile("materializer-soak", { throwOnError: false }).ok, true);
  });

  console.log("stream profile guard checks passed");
} finally {
  process.env = originalEnv;
}

function baseStreamEnv() {
  return {
    RUNTIME_PERSISTENCE: "noop",
    EXTERNAL_API_IDEMPOTENCY_STORE: "inmemory",
    EXTERNAL_API_COMMAND_CAPTURE_MODE: "disabled",
    EXTERNAL_API_COMMAND_LOG_MODE: "disabled",
    STREAM_ACK_INTAKE_STORE: "inmemory",
    STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES: "100000",
    STREAM_ACK_INMEMORY_INTAKE_SHARDS: "256",
    PLATFORM_HTTP_SERVER: "netty",
    MATCHING_ENGINE_DIRECT_STREAM_ENABLED: "true",
    STREAM_ACK_PUBLISH_PIPELINE_ENABLED: "true",
    STREAM_ACK_WORKER_ENABLED: "false",
    STREAM_ACK_PROJECTOR_ENABLED: "false",
    STREAM_ACK_PUBLISHER: "",
    EXTERNAL_API_ABUSE_BREAKER_MODE: "off",
  };
}

function withEnv(nextEnv, callback) {
  process.env = { ...originalEnv, ...nextEnv };
  callback();
}
