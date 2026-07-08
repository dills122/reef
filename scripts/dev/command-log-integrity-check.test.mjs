import test from "node:test";
import assert from "node:assert/strict";
import {
  buildCommandLogIntegritySummarySql,
  integrityViolations,
  parseIntegritySummaryRows,
} from "./command-log-integrity-check.mjs";

test("builds command-log integrity summary SQL", () => {
  const sql = buildCommandLogIntegritySummarySql();

  assert.match(sql, /FROM command_log\.command_integrity_summary\(\)/);
  assert.match(sql, /ORDER BY violation_type/);
});

test("parses integrity summary rows", () => {
  const summary = parseIntegritySummaryRows([
    ["orphan_payload", "2"],
    ["terminal_result_still_queued", "0"],
  ]);

  assert.deepEqual(summary, [
    { violationType: "orphan_payload", violationCount: 2 },
    { violationType: "terminal_result_still_queued", violationCount: 0 },
  ]);
});

test("finds nonzero integrity violations", () => {
  const violations = integrityViolations([
    { violationType: "orphan_payload", violationCount: 2 },
    { violationType: "terminal_result_still_queued", violationCount: 0 },
  ]);

  assert.deepEqual(violations, [{ violationType: "orphan_payload", violationCount: 2 }]);
});
