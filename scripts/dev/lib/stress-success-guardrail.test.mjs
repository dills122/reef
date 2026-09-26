import assert from "node:assert/strict";
import test from "node:test";

import { successRateForGuardrail } from "./stress-success-guardrail.mjs";

test("end-to-end guardrail includes expected business rejects", () => {
  const report = {
    totalRequests: 100,
    totalSuccess: 55,
    quality: {
      endToEndSuccessRatePct: 55,
      validIntentSuccessRatePct: 100,
    },
  };

  assert.equal(successRateForGuardrail(report, "end-to-end"), 55);
});

test("valid-intent guardrail excludes expected business rejects", () => {
  const report = {
    totalRequests: 100,
    totalSuccess: 55,
    quality: {
      endToEndSuccessRatePct: 55,
      validIntentSuccessRatePct: 100,
    },
  };

  assert.equal(successRateForGuardrail(report, "valid-intent"), 100);
});

test("valid-intent guardrail still exposes system failures", () => {
  const report = {
    totalRequests: 100,
    totalSuccess: 50,
    quality: {
      endToEndSuccessRatePct: 50,
      validIntentSuccessRatePct: 80,
      systemFailureRatePct: 12.5,
    },
  };

  assert.equal(successRateForGuardrail(report, "valid-intent"), 80);
});

test("end-to-end guardrail supports older reports without quality metrics", () => {
  assert.equal(successRateForGuardrail({ totalRequests: 20, totalSuccess: 19 }, "end-to-end"), 95);
});

test("rejects unknown guardrail metrics", () => {
  assert.throws(
    () => successRateForGuardrail({ totalRequests: 1, totalSuccess: 1 }, "raw-ish"),
    /unsupported stress success guardrail metric/,
  );
});
