import assert from "node:assert/strict";
import { test } from "node:test";
import {
  assertClaimGuardedProjectionReplay,
  assertStableProjectionReplaySnapshot,
  stableStringify,
} from "./lib/projection-replay-proof.mjs";

test("stableStringify canonicalizes object key order", () => {
  assert.equal(stableStringify({ b: 1, a: { d: 2, c: 3 } }), '{"a":{"c":3,"d":2},"b":1}');
});

test("assertStableProjectionReplaySnapshot accepts identical stable read output", () => {
  const before = projectionSnapshot();
  const after = projectionSnapshot();

  assert.doesNotThrow(() => assertStableProjectionReplaySnapshot(before, after));
});

test("assertStableProjectionReplaySnapshot rejects duplicate replay rows", () => {
  const before = projectionSnapshot();
  const after = projectionSnapshot();
  after.db.runtimeEvents.push({ event_id: "evt-1", event_type: "OrderAccepted" });

  assert.throws(
    () => assertStableProjectionReplaySnapshot(before, after),
    /projection replay changed stable read-model output/,
  );
});

test("assertStableProjectionReplaySnapshot rejects API freshness drift", () => {
  const before = projectionSnapshot();
  const after = projectionSnapshot();
  after.api.availability.surfaces[0].lag = 3;

  assert.throws(
    () => assertStableProjectionReplaySnapshot(before, after),
    /projection replay changed stable read-model output/,
  );
});

test("assertClaimGuardedProjectionReplay accepts an exact duplicate blocked by its completed claim", () => {
  assert.doesNotThrow(() => assertClaimGuardedProjectionReplay({
    projectedRows: 0,
    partition: 2,
    streamSequence: 562949953421313,
    watermarkRows: [{ last_partition_seq: "0" }],
    claimRows: [{
      status: "completed",
      candidate_count: "1",
      result_count: "1",
      max_stream_sequence: "562949953421313",
    }],
  }));
});

test("assertClaimGuardedProjectionReplay rejects a replay without an exact completed claim", () => {
  assert.throws(
    () => assertClaimGuardedProjectionReplay({
      projectedRows: 0,
      partition: 2,
      streamSequence: 562949953421313,
      watermarkRows: [{ last_partition_seq: "0" }],
      claimRows: [],
    }),
    /completed projection batch claim/,
  );
});

test("assertClaimGuardedProjectionReplay rejects duplicate effects", () => {
  assert.throws(
    () => assertClaimGuardedProjectionReplay({
      projectedRows: 1,
      partition: 2,
      streamSequence: 562949953421313,
      watermarkRows: [{ last_partition_seq: "562949953421313" }],
      claimRows: [{
        status: "completed",
        candidate_count: "1",
        result_count: "1",
        max_stream_sequence: "562949953421313",
      }],
    }),
    /projected 1 rows/,
  );
});

function projectionSnapshot() {
  return {
    db: {
      submitResults: [{ command_id: "cmd-1", result_type: "accepted" }],
      runtimeEvents: [{ event_id: "evt-1", event_type: "OrderAccepted" }],
      orders: [{ order_id: "ord-1", instrument_id: "AAPL" }],
      lifecycle: [{ order_id: "ord-1", status: "OPEN" }],
      marketSnapshots: [{ instrument_id: "AAPL", best_bid_price: "150250000000" }],
      watermarks: [{ projection_name: "runtime-normalized", partition_id: "1", last_partition_seq: "10" }],
    },
    api: {
      commandStatus: { commandId: "cmd-1", status: "COMPLETED", source: "canonical_outcome" },
      currentOrders: { orders: [{ orderId: "ord-1", status: "OPEN" }] },
      orderHistory: { orders: [{ orderId: "ord-1", status: "OPEN" }] },
      snapshot: { snapshot: { bestBidPrice: "150250000000" } },
      depth: { depth: { bidLevels: [{ price: "150250000000", quantity: "100" }] } },
      availability: {
        surfaces: [{ name: "currentOrders", lag: 0, lastPartitionSequence: 10 }],
      },
    },
  };
}
