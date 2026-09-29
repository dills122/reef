import assert from "node:assert/strict";
import test from "node:test";
import { assessRecovery } from "./postmatch-candidate-recovery-campaign.mjs";

function fixture() {
  const finality = { acknowledged_batch_sequence: "300", acknowledged_batch_digest: "a".repeat(64),
    journal_incarnation_id: "incarnation", control_sequence: "326",
    control_digest: "b".repeat(64), source_binding_digest: "c".repeat(64),
    lease_epoch: "7", snapshot_batch_sequence: "256", snapshot_state_version: "2",
    snapshot_state_digest: "d".repeat(64) };
  const journal = { next_batch_sequence: "301", last_batch_digest: "a".repeat(64),
    owner_epoch: "7", incarnation_id: "incarnation", last_control_sequence: "326",
    last_control_digest: "b".repeat(64) };
  const cohort = { status: "pass", sourceGeneration: "generation", sourceHeads: { 0: "12" },
    journal: { next_batch_sequence: "301", last_batch_digest: "a".repeat(64) },
    finality: { acknowledged_batch_sequence: "300", acknowledged_batch_digest: "a".repeat(64) },
    financial: { last_batch_sequence: "300" }, market: [{ partition_id: "0",
      last_stream_sequence: "12" }], coverage: { sourceOutcomes: 12, windows: 4 },
    firstTrades: { compared: 3, mismatches: [] }, marketTrades: { compared: 3, mismatches: [] },
    tradeProjectionParity: { journal_trades: 3, projected_trades: 3, mismatches: 0 },
    balanceParity: { controls: 326, projected: 4, unmatched: 0, failures: [] },
    duplicates: { duplicate_settlements: 0 } };
  const before = { state: { finality, journal }, cohort,
    container: { id: "abc", startedAt: "before", running: true } };
  const after = { state: { finality: { ...finality, lease_epoch: "8" },
    journal: { ...journal, owner_epoch: "8" } }, cohort: structuredClone(cohort),
    container: { id: "abc", startedAt: "after", running: true } };
  return { before, after };
}

test("closed cohort V2 snapshot restores under a higher independent lease epoch", () => {
  const { before, after } = fixture();
  assert.deepEqual(assessRecovery(before, after), []);
});

test("rejects old lease reuse, unanchored snapshot, and excessive tail", () => {
  const { before, after } = fixture();
  after.state.finality.lease_epoch = "7";
  assert.match(assessRecovery(before, after).join(" "), /lease/);
  before.state.finality.snapshot_state_version = "1";
  assert.match(assessRecovery(before, after).join(" "), /V2 snapshot/);
  before.state.finality.snapshot_state_version = "2";
  before.state.finality.snapshot_batch_sequence = "1";
  assert.match(assessRecovery(before, after).join(" "), /bounded replay tail/);
});

test("rejects changed acknowledged facts or source/projection cohort after takeover", () => {
  const { before, after } = fixture();
  after.state.finality.acknowledged_batch_digest = "f".repeat(64);
  after.cohort.sourceHeads[0] = "13";
  after.cohort.financial.last_batch_sequence = "299";
  const failures = assessRecovery(before, after).join(" ");
  assert.match(failures, /acknowledged_batch_digest/);
  assert.match(failures, /sourceHeads/);
  assert.match(failures, /financial/);
});

test("rejects writer that did not restart", () => {
  const { before, after } = fixture();
  after.container.startedAt = before.container.startedAt;
  assert.match(assessRecovery(before, after).join(" "), /did not restart/);
});

test("rejects changed market and financial parity after takeover", () => {
  const { before, after } = fixture();
  after.cohort.marketTrades.compared = 2;
  after.cohort.tradeProjectionParity.mismatches = 1;
  after.cohort.balanceParity.projected = 3;
  const failures = assessRecovery(before, after).join(" ");
  assert.match(failures, /marketTrades/);
  assert.match(failures, /tradeProjectionParity/);
  assert.match(failures, /balanceParity/);
});
