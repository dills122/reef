import assert from "node:assert/strict";

import {
  partitionCounts,
  selectPartitionSpreadInstruments,
  streamRoutingPartition,
} from "./lib/stream-partition-spread.mjs";

const runId = "venue-event-materializer-mixed-lifecycle-stress";
const venueSessionId = "venue-event-materializer-mixed-lifecycle-stress";
const partitionCount = 16;

const sequentialCounts = Array(partitionCount).fill(0);
for (let index = 1; index <= 64; index += 1) {
  const symbol = `STK${String(index).padStart(3, "0")}`;
  sequentialCounts[streamRoutingPartition({ runId, venueSessionId, instrumentId: symbol, partitionCount })] += 1;
}
assert.equal(sequentialCounts[14], 0);

const selected = selectPartitionSpreadInstruments({
  runId,
  venueSessionId,
  requestedCount: 64,
  partitionCount,
});
assert.equal(selected.length, 64);
assert.equal(new Set(selected.map((instrument) => instrument.symbol)).size, 64);

const selectedCounts = partitionCounts(selected, partitionCount);
assert.deepEqual(selectedCounts, Array(partitionCount).fill(4));

const partial = selectPartitionSpreadInstruments({
  runId,
  venueSessionId,
  requestedCount: 20,
  partitionCount,
});
const partialCounts = partitionCounts(partial, partitionCount);
assert.equal(Math.max(...partialCounts) - Math.min(...partialCounts), 1);
