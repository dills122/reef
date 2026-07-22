import { randomUUID } from "node:crypto";

export function newSmokeExecutionId() {
  return `${Date.now().toString(36)}-${randomUUID().slice(0, 8)}`;
}

export function createSmokeIdentity(executionId) {
  if (!/^[A-Za-z0-9_-]+$/.test(executionId)) {
    throw new Error("DEV_SMOKE_EXECUTION_ID must contain only letters, numbers, underscores, or hyphens");
  }
  return {
    runId: `smoke-run-${executionId}`,
    venueSessionId: `smoke-session-${executionId}`,
    orderId: `smoke-ord-${executionId}`,
    submitCommandId: `smoke-cmd-submit-${executionId}`,
    cancelCommandId: `smoke-cmd-cancel-${executionId}`,
    submitTraceId: `smoke-trace-submit-${executionId}`,
    cancelTraceId: `smoke-trace-cancel-${executionId}`,
    submitCausationId: `smoke-causation-submit-${executionId}`,
    cancelCausationId: `smoke-causation-cancel-${executionId}`,
    submitCorrelationId: `smoke-correlation-submit-${executionId}`,
    cancelCorrelationId: `smoke-correlation-cancel-${executionId}`,
    submitIdempotencyKey: `smoke-submit-${executionId}`,
    cancelIdempotencyKey: `smoke-cancel-${executionId}`,
  };
}
