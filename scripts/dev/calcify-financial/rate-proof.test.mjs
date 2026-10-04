import test from 'node:test';
import assert from 'node:assert/strict';
import { PLAN, preparePolicy, assessMeasurement, selectRepeat, estimateAged, runAdapter, calibrateAdapter } from './rate-proof.mjs';

// Fabricated unit-test measurements exercise validation only; never benchmark evidence.
const hash = 'a'.repeat(64);
const calibration = {
  schema: 'financial-rate-calibration-v1', sourceHead: hash, fixtureSha256: hash,
  realRecordEvidenceSha256: hash, sampleTrades: 1000, encodedBytes: 100000,
  physicalBytes: 200000, physicalReplicationIncluded: true,
  identityPhysicalBytes: 100, pendingPhysicalBytes: 200,
  producer: { completedTrades: 10000, elapsedMs: 1000 },
  observer: { completedTrades: 10000, elapsedMs: 1000, exactParity: true },
};
const correctness = { schema: 'financial-e3-correctness-v1', result: 'PASS', sourceHead: hash, fixtureSha256: hash, evidenceSha256: hash };
const preflight = { guestFreeBytes: 40 * 1024 ** 3, basePhysicalBytes: 1024,
  indexedReadView: true, boundedWrites: true, retainsFullDomainHistory: false,
  accessPatternEvidenceSha256: hash };
const arm = PLAN.ladder[0];
const policy = () => preparePolicy({ calibration, correctness, preflight, arm });
function measurement() {
  return {
    schema: 'financial-rate-measurement-v1', policySha256: policy().policySha256,
    sourceHead: hash, fixtureSha256: hash, workloadSha256: hash,
    units: 'unique timed executions; preflight/aged rows excluded',
    producerElapsedMs: 60000, drainMs: 100,
    deadline: { offered: 150000, admitted: 150000, decided: 150000, settled: 150000, pending: 0 },
    final: { offered: 150000, admitted: 150000, decided: 150000, settled: 150000, pending: 0 },
    parity: { completeJournal: true, completeOwnerState: true, independentOracle: true, evidenceSha256: hash },
    lagSamples: [{ elapsedMs: 0, lag: 0 }, { elapsedMs: 30000, lag: 1 }, { elapsedMs: 60000, lag: 0 }],
    resources: { processCpuMs: 120000, heapPeakBytes: 1024, rssPeakBytes: 2048, diskPeakBytes: 4096,
      encodedInputBytes: 15000000, encodedResultBytes: 15000000, encodedTechnicalBytes: 100,
      physicalBrokerBytes: 40000000, physicalChangelogBytes: 10000000,
      touchedKeys: 1500000, technicalRecords: 100, transactionWaitMs: 500 },
    restore: { records: 1000, bytes: 100000, elapsedMs: 100, verifiedCut: true },
    stageLatency: { kind: 'sampled-individual', clockDomain: 'same-monotonic', samples: 1000, p95Ms: 2, p99Ms: 3 },
  };
}

test('preparation stops until E3 correctness and real calibration plus indexed/bounded adapter exist', () => {
  assert.equal(preparePolicy({}).status, 'BLOCKED');
  assert.match(preparePolicy({ calibration, preflight, arm }).gaps.join(' '), /E3/);
  const copy = structuredClone(preflight); copy.retainsFullDomainHistory = true;
  assert.match(preparePolicy({ calibration, correctness, preflight: copy, arm }).gaps.join(' '), /full-domain/);
});

test('source/fixture mismatches and fabricated zero-byte calibration cannot freeze runnable policy', () => {
  const c = { ...calibration, sourceHead: 'b'.repeat(64) };
  assert.equal(preparePolicy({ calibration: c, correctness, preflight, arm }).status, 'BLOCKED');
  assert.equal(preparePolicy({ calibration: { ...calibration, encodedBytes: 0 }, correctness, preflight, arm }).status, 'BLOCKED');
});

test('aged estimate retains proposed 1m identities; over-budget case explicitly shrinks diagnostic only', () => {
  const full = estimateAged(calibration, preflight);
  assert.equal(full.identities, 1000000);
  assert.equal(full.pendingItems, 10000);
  const reduced = estimateAged({ ...calibration, identityPhysicalBytes: 20000 }, preflight);
  assert.equal(reduced.scope, 'REDUCED_AGED_DIAGNOSTIC');
  assert.ok(reduced.identities < 1000000);
  assert.ok(reduced.estimatedBytes <= PLAN.diskBudgetBytes);
  assert.equal(reduced.proposedIdentities, 1000000);
});

test('disk space gate and exact prefunding bound all declared timed plus pending work', () => {
  const p = policy(); assert.equal(p.status, 'FROZEN');
  assert.equal(p.openingResources.cashNanos, '1500000000000');
  assert.equal(p.openingResources.shares, '150000');
  assert.equal(preparePolicy({ calibration, correctness, preflight: { ...preflight, guestFreeBytes: 1 }, arm }).status, 'BLOCKED');
});

test('measurement separates stage rates; complete parity and all telemetry permit diagnostic pass', () => {
  const r = assessMeasurement(policy(), measurement());
  assert.equal(r.result, 'PASS_DIAGNOSTIC');
  assert.equal(r.rates.decidedPerSecond, 2500);
  assert.equal(r.cpuEquivalentCores, 2);
  assert.equal(r.touchedKeysPerTrade, 10);
  assert.equal(r.capacityQualification, false);
});

test('producer duration miss fails even when output is exact after drain', () => {
  const m = measurement(); m.producerElapsedMs = 62000;
  const r = assessMeasurement(policy(), m);
  assert.equal(r.result, 'FAIL_DIAGNOSTIC');
  assert.ok(r.failures.includes('PRODUCER_DURATION_MISS'));
});

test('late drain cannot be counted as deadline output or settled financial throughput', () => {
  const m = measurement(); m.deadline.decided = 100000; m.deadline.settled = 100000;
  const r = assessMeasurement(policy(), m);
  assert.ok(r.failures.includes('DEADLINE_USEFUL_RATE_MISS'));
  assert.equal(r.rates.decidedPerSecond, 100000 / 60);
});

for (const stage of ['offered', 'admitted', 'decided', 'settled']) {
  test(`cumulative ${stage} cannot roll back between deadline and final cuts`, () => {
    const m = measurement();
    const cumulative = ['offered', 'admitted', 'decided', 'settled'];
    for (const preceding of cumulative.slice(0, cumulative.indexOf(stage) + 1)) m.deadline[preceding]++;
    m.deadline.pending = m.deadline.decided - m.deadline.settled;
    const r = assessMeasurement(policy(), m);
    assert.ok(!r.failures.includes('DEADLINE_COUNT_PARITY'), `${stage}: valid within-cut accounting`);
    assert.ok(r.failures.includes(`DEADLINE_${stage.toUpperCase()}_EXCEEDS_FINAL`), stage);
    assert.equal(r.result, 'FAIL_DIAGNOSTIC', stage);
  });
}

test('deadline offers cannot exceed frozen expected workload even if final agrees', () => {
  const m = measurement();
  for (const cut of ['deadline', 'final']) {
    for (const stage of ['offered', 'admitted', 'decided', 'settled']) m[cut][stage]++;
  }
  const r = assessMeasurement(policy(), m);
  assert.ok(r.failures.includes('DEADLINE_OFFERED_EXCEEDS_EXPECTED'));
});

test('each cut rejects admission ordering and settlement accounting violations', () => {
  for (const cut of ['deadline', 'final']) {
    for (const stage of ['admitted', 'decided', 'settled', 'pending']) {
      const m = measurement(); m[cut][stage]++;
      assert.ok(assessMeasurement(policy(), m).failures.includes(`${cut.toUpperCase()}_COUNT_PARITY`), `${cut}/${stage}`);
    }
  }
});

test('pending may decrease during drain; only missed deadline settlement fails', () => {
  const m = measurement(); m.deadline.settled--; m.deadline.pending++;
  const r = assessMeasurement(policy(), m);
  assert.deepEqual(r.failures, ['DEADLINE_USEFUL_RATE_MISS']);
  assert.deepEqual(r.gaps, []);
});

test('missing accounting or resource metrics are explicit limited attempt; count inconsistency fails', () => {
  const m = measurement(); delete m.resources.physicalChangelogBytes;
  assert.equal(assessMeasurement(policy(), m).result, 'LIMITED');
  const broken = measurement(); broken.final.pending = 1;
  assert.ok(assessMeasurement(policy(), broken).failures.includes('FINAL_COUNT_PARITY'));
  const parity = measurement(); parity.parity.completeJournal = false;
  assert.ok(assessMeasurement(policy(), parity).failures.includes('ACCOUNTING_PARITY'));
});

test('lag growth, over-budget disk and unsupported latency clock cannot silently pass', () => {
  const m = measurement(); m.lagSamples[2].lag = 6000; m.resources.diskPeakBytes = PLAN.diskBudgetBytes + 1;
  m.stageLatency.clockDomain = 'mixed-wall-and-monotonic';
  const r = assessMeasurement(policy(), m);
  assert.ok(r.failures.includes('LAG_END_OR_GROWTH'));
  assert.ok(r.failures.includes('DISK_BUDGET_EXCEEDED'));
  assert.ok(r.gaps.includes('LATENCY_CLOCK_UNQUALIFIED'));
});

test('highest stable measured arm creates fresh/aged 300s pair with same business workload', () => {
  const stable = [{ rate: 2500, result: 'PASS_DIAGNOSTIC' }, { rate: 5000, result: 'PASS_DIAGNOSTIC' }, { rate: 10000, result: 'FAIL_DIAGNOSTIC' }];
  const pair = selectRepeat(stable, hash);
  assert.equal(pair[0].rate, 5000); assert.equal(pair[0].seconds, 300);
  assert.equal(pair[0].workloadSha256, pair[1].workloadSha256);
  assert.equal(selectRepeat([], hash).length, 0);
});

test('missing restore cost, partial monitoring and observer slowdown remain limited preflight', () => {
  const slow = { ...calibration, observer: { ...calibration.observer, elapsedMs: 5000 } };
  assert.equal(preparePolicy({ calibration: slow, correctness, preflight, arm }).status, 'BLOCKED');
  const m = measurement();
  delete m.restore;
  assert.ok(assessMeasurement(policy(), m).gaps.includes('RESTORE_WORK_REQUIRED'));
});

test('load and calibration entrypoints refuse before filesystem or adapter work without authorization', async () => {
  await assert.rejects(runAdapter('/missing-policy', '/missing-adapter', '/missing-out'), /authorize-load/);
  await assert.rejects(calibrateAdapter('/missing-request', '/missing-adapter', '/missing-out'), /authorize-calibration/);
});


test('calibration overrides cannot undercut measured physical byte budget or use invalid numbers', () => {
  const huge = { ...calibration, physicalBytes: 200000000 };
  assert.equal(preparePolicy({ calibration: huge, correctness, preflight, arm }).status, 'BLOCKED');
  for (const value of [0, -1, 0.5, 1, 399999, '400000', 'bogus', null, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    const c = { ...huge, maxPhysicalBytesPerTrade: value };
    assert.equal(preparePolicy({ calibration: c, correctness, preflight, arm }).status, 'BLOCKED', String(value));
    assert.throws(() => estimateAged(c, preflight, arm), /PHYSICAL_TRADE_BUDGET/, String(value));
  }
});

test('valid byte override preserves conservative floor and may increase budget only', () => {
  for (const value of [400, 800]) {
    const c = { ...calibration, maxPhysicalBytesPerTrade: value };
    const p = preparePolicy({ calibration: c, correctness, preflight, arm });
    assert.equal(p.status, 'FROZEN');
    assert.equal(p.aged.physicalBytesPerTradeBudget, value);
    assert.equal(p.aged.fixedBytes, preflight.basePhysicalBytes + PLAN.pendingItems * c.pendingPhysicalBytes + arm.rate * arm.seconds * value);
  }
});

test('derived per-trade budget must remain safe integer before policy freezes', () => {
  const c = { ...calibration, sampleTrades: 1, physicalBytes: Number.MAX_SAFE_INTEGER };
  assert.equal(preparePolicy({ calibration: c, correctness, preflight, arm }).status, 'BLOCKED');
  assert.throws(() => estimateAged(c, preflight, arm), /PHYSICAL_TRADE_BUDGET/);
});
