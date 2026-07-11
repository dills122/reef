import assert from "node:assert/strict";
import { validateStressRunShape } from "./stress-run-guard.mjs";

runTest("allows non stream-ack stress without session config", () => {
  validateStressRunShape({
    profile: "default",
    sessionConfig: "",
    captureStreamAckWorkerStats: false,
    captureStreamAckProjectorStats: false,
  });
});

runTest("requires session config for stream-ack worker diagnostics", () => {
  assert.throws(
    () =>
      validateStressRunShape({
        profile: "stream-submit",
        sessionConfig: "",
        captureStreamAckWorkerStats: true,
        captureStreamAckProjectorStats: false,
      }),
    /stream-ack stress requires DEV_STRESS_SESSION_CONFIG/,
  );
});

runTest("allows stream-ack when a session config is supplied", () => {
  validateStressRunShape({
    profile: "stream-submit",
    sessionConfig: "/tmp/stream-ack-submit-spread.yaml",
    captureStreamAckWorkerStats: true,
    captureStreamAckProjectorStats: true,
  });
});

runTest("allows materializer profile without stream-ack session config", () => {
  validateStressRunShape({
    profile: "capacity-heavy",
    sessionConfig: "",
    captureStreamAckWorkerStats: false,
    captureStreamAckProjectorStats: false,
    captureStreamDirectStats: true,
    captureVenueEventMaterializerStats: true,
  });
});

function runTest(name, fn) {
  try {
    fn();
  } catch (error) {
    console.error(`not ok - ${name}`);
    throw error;
  }
  console.log(`ok - ${name}`);
}
