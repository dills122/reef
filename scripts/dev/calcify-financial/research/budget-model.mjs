import assert from 'node:assert/strict';

// Arithmetic scenarios, not measured service capacities or a physical disk bound.
export function queue({ arrivalPerSecond, servicePerSecond, seconds, bytesPerQueuedTrade }) {
  for (const value of [arrivalPerSecond, servicePerSecond, seconds, bytesPerQueuedTrade]) {
    assert(Number.isFinite(value) && value >= 0);
  }
  const backlog = Math.max(0, arrivalPerSecond - servicePerSecond) * seconds;
  return { backlog, logicalBacklogBytes: backlog * bytesPerQueuedTrade,
    drainSecondsAfterStop: servicePerSecond > 0 ? backlog / servicePerSecond : backlog > 0 ? null : 0,
    sustainableDutyFraction: arrivalPerSecond === 0 ? 1 : Math.min(1, servicePerSecond / arrivalPerSecond) };
}

export function headroom({ rate, outageSeconds, recoverySeconds }) {
  assert(rate > 0 && outageSeconds >= 0 && recoverySeconds > 0);
  return rate * (1 + outageSeconds / recoverySeconds);
}

// Read-committed returns a prefix ending before first open transaction.
function readCommitted(records) {
  const firstOpen = records.findIndex(record => record.transaction === 'open');
  return records.slice(0, firstOpen < 0 ? records.length : firstOpen).filter(record => record.transaction === 'committed');
}
// Finite exhaustive checks of chosen toy semantics; not a broker test.
let checks = 0;
for (let arrival = 0; arrival <= 12; arrival++) {
  for (let service = 0; service <= 12; service++) {
    for (let seconds = 0; seconds <= 30; seconds++) {
      let backlog = 0;
      for (let tick = 0; tick < seconds; tick++) backlog = Math.max(0, backlog + arrival - service);
      assert.equal(queue({ arrivalPerSecond: arrival, servicePerSecond: service, seconds, bytesPerQueuedTrade: 1 }).backlog, backlog);
      checks++;
    }
  }
}
const blocked = [{ domain: 'A', transaction: 'open' }, { domain: 'B', transaction: 'committed' }];
assert.deepEqual(readCommitted(blocked), []);
assert.equal(readCommitted([{ ...blocked[0], transaction: 'aborted' }, blocked[1]]).length, 1);
assert.equal(readCommitted([blocked[1]]).length, 1); // Separate B partition.
assert.equal(headroom({ rate: 10000, outageSeconds: 60, recoverySeconds: 300 }), 12000);
assert.equal(queue({ arrivalPerSecond: 10, servicePerSecond: 10, seconds: 600, bytesPerQueuedTrade: 100 }).backlog, 0);
assert.equal(queue({ arrivalPerSecond: 10, servicePerSecond: 0, seconds: 60, bytesPerQueuedTrade: 100 }).drainSecondsAfterStop, null);

const bytesPerQueuedTrade = 2048; // Explicit illustrative context-only assumption.
const scenarios = [0, 1051.7333333333333, 3500, 7500, 10000, 12000].map(servicePerSecond => ({
  arrivalPerSecond: 10000, servicePerSecond, seconds: 600, bytesPerQueuedTrade,
  ...queue({ arrivalPerSecond: 10000, servicePerSecond, seconds: 600, bytesPerQueuedTrade }),
}));
const historicalResultsPerTrade = 883951833 / 150000;
const fixtureSource = 24797, fixtureModeOffSource = 16647, fixtureCapture = 29930;
console.log(JSON.stringify({ schema: 'calcify-architecture-budget-v1', scope: 'arithmetic and finite toy model only',
  checks: { queueRecurrenceCases: checks, readCommittedHeadOfLine: true, separatePartitionControl: true }, scenarios,
  historicE4: { resultBytesPerTrade: historicalResultsPerTrade, inputBytesPerTrade: 112172230 / 150000,
    stateWritesPerTrade: 5550017 / 150000, resultBytesAtSixMillionTrades: historicalResultsPerTrade * 6000000,
    forceSeconds: 60525331066 / 1e9, producerSeconds: 132.040,
    note: 'Arithmetic from archived test adapter measurement; excludes additional source, context, changelog, SQL/WAL, replication, indexes and compaction.' },
  finiteO1: { fixtureCommands: 12, fixtureTrades: 3, fixtureSourceBytes: fixtureSource,
    fixtureModeOffSourceBytes: fixtureModeOffSource, fixtureCaptureBytes: fixtureCapture,
    sourceExpansionRatio: fixtureSource / fixtureModeOffSource,
    allPayloadBytesPerFixtureTrade: (fixtureSource + fixtureCapture) / 3,
    maxPublicationTradeAllowance: 16 * 8,
    maximumSecondsAt10kIgnoringOtherLimits: (16 * 8) / 10000,
    note: 'Actual tiny fixture mix; not a throughput or representative bytes/trade estimate.' },
  recovery: { arrivalPerSecond: 10000, outageSeconds: 60, requiredDrainSeconds: 300,
    requiredServicePerSecond: headroom({ rate: 10000, outageSeconds: 60, recoverySeconds: 300 }) },
  snapshotIllustration: { records: 6000000, bytesAddedPerRecord: 128,
    fullImageLogicalBytes: 128 * 6000000 * 6000001 / 2,
    keyedDeltaLogicalBytes: 128 * 6000000,
    note: 'Hypothetical append-growing state; demonstrates quadratic rewrite cost, not measured finite reducer cost.' },
}, null, 2));
