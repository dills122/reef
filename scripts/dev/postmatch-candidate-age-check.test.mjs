import test from "node:test";
import assert from "node:assert/strict";
import { assessCandidateVisibleAge } from "./postmatch-candidate-age-check.mjs";

const receipt = (at, partition, outcomes, age) =>
  `platform-postmatch-live-0 | postmatch_candidate_visible_age observed_at=${at} event_stream=TEST partition=${partition} from_exclusive=0 through=1 outcomes=${outcomes} source_to_visible_upper_bound_ms=${age}`;

test("candidate age gate weights conservative post-commit windows by outcome count", () => {
  const result = assessCandidateVisibleAge([
    receipt("2026-09-28T10:00:01Z", 0, 99, 100),
    receipt("2026-09-28T10:00:02Z", 1, 1, 40000),
  ], "TEST", "2026-09-28T10:00:00Z");
  assert.equal(result.status, "fail");
  assert.equal(result.p95Ms, 100);
  assert.equal(result.p99Ms, 100);
  assert.equal(result.maxMs, 40000);
  assert.equal(result.outcomeCount, 100);
});

test("candidate age gate excludes prior generation traffic and old smoke receipts", () => {
  const result = assessCandidateVisibleAge([
    receipt("2026-09-28T09:59:59Z", 0, 1, 60000),
    receipt("2026-09-28T10:00:01Z", 1, 2, 40),
    receipt("2026-09-28T10:00:01Z", 2, 5, 60000).replace("event_stream=TEST", "event_stream=OTHER"),
  ], "TEST", "2026-09-28T10:00:00Z");
  assert.equal(result.status, "pass");
  assert.equal(result.outcomeCount, 2);
  assert.deepEqual(result.partitions, [1]);
});

test("candidate age gate fails closed on absent or malformed receipts", () => {
  assert.equal(assessCandidateVisibleAge([], "TEST", "2026-09-28T10:00:00Z").status, "fail");
  const malformed = receipt("bad-time", 0, 1, 3);
  assert.match(assessCandidateVisibleAge([malformed], "TEST", "2026-09-28T10:00:00Z")
    .failures.join(" "), /malformed/);
});

test("candidate age gate requires every committed market window and partition receipt", () => {
  const lines = [receipt("2026-09-28T10:00:01Z", 0, 10, 200)];
  const expected = [{ partition: 0, from: "0", through: "1" },
    { partition: 1, from: "0", through: "1" }];
  const missing = assessCandidateVisibleAge(lines, "TEST", "2026-09-28T10:00:00Z",
    undefined, expected, 2);
  assert.equal(missing.status, "fail");
  assert.match(missing.failures.join(" "), /lacks one visible-age receipt/);
  const complete = assessCandidateVisibleAge([...lines,
    receipt("2026-09-28T10:00:02Z", 1, 10, 250)], "TEST",
    "2026-09-28T10:00:00Z", undefined, expected, 2);
  assert.equal(complete.status, "pass");
  assert.equal(complete.expectedWindowCount, 2);
  const duplicate = assessCandidateVisibleAge([...lines, ...lines,
    receipt("2026-09-28T10:00:02Z", 1, 10, 250)], "TEST",
    "2026-09-28T10:00:00Z", undefined, expected, 2);
  assert.equal(duplicate.status, "fail");
});
