export function validateStressRunShape(config) {
  const streamAckDiagnostics =
    Boolean(config.captureStreamAckWorkerStats) || Boolean(config.captureStreamAckProjectorStats);
  const directOrMaterializerDiagnostics =
    Boolean(config.captureStreamDirectStats) || Boolean(config.captureVenueEventMaterializerStats);
  const requiresGeneratedSessionConfig =
    config.profile === "stream-submit" &&
    streamAckDiagnostics &&
    !directOrMaterializerDiagnostics &&
    !config.sessionConfig &&
    config.allowMissingSessionConfig !== true;

  if (!requiresGeneratedSessionConfig) return;

  throw new Error(
    [
      "stream-ack stress requires DEV_STRESS_SESSION_CONFIG",
      "Use `make dev-stress-stream-ack` or applyStressProfile('stream-ack') before importing scripts/dev/stress.mjs.",
      "Running scripts/dev/stress.mjs directly with stream-ack worker diagnostics can concentrate traffic on one partition and produce misleading backpressure failures.",
      "Set DEV_STRESS_SESSION_CONFIG explicitly only when intentionally supplying a reviewed spread session config.",
    ].join("\n"),
  );
}
