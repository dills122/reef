import test from 'node:test';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm, symlink } from 'node:fs/promises';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { tmpdir } from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
import { PLAN, preparePolicy, assessMeasurement, selectRepeat, estimateAged, runAdapter, calibrateAdapter, estimateHeap, verifyHeapEvidence, HEAP_LAUNCHER, captureHeapCapability } from './rate-proof.mjs';

test('capacity lock has fixed shared name in platform temporary directory', async () => {
  const { capacityLockPath } = await import('./rate-proof.mjs');
  assert.equal(typeof capacityLockPath, 'function');
  assert.equal(capacityLockPath('darwin', '/irrelevant'), '/private/tmp/reef-financial-rate-capacity.lock');
  assert.equal(capacityLockPath('linux', '/tmp'), '/tmp/reef-financial-rate-capacity.lock');
  assert.equal(capacityLockPath('linux', '/tmp/custom'), '/tmp/custom/reef-financial-rate-capacity.lock');
  assert.equal(capacityLockPath(), path.join(process.platform === 'darwin' ? '/private/tmp' : tmpdir(), 'reef-financial-rate-capacity.lock'));
});

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
// Synthetic finite bounds exercise contract only; no measured or reviewed real bounds.
const heap = {
  schema: 'financial-heap-admission-v1', authorizedMaxHeapBytes: HEAP_LAUNCHER.maxHeapBytes,
  boundsEvidencePath: 'bounds.json', boundsEvidenceSha256: hash,
  fixtureEvidencePath: 'fixture.json', capabilityEvidencePath: 'capability.json', capabilityEvidenceSha256: hash,
  bounds: { schema: 'financial-heap-bounds-v1', sourceHead: hash, fixtureSha256: hash, configSha256: hash, buildSha256: hash, classpathSha256: hash,
    identityHeapBytesUpper: 100, pendingHeapBytesUpper: 200, baselineHeapBytesUpper: 64 * 1024 ** 2,
    transientHeapReserveBytes: 32 * 1024 ** 2, replayHeapReserveBytes: 32 * 1024 ** 2,
    supportedIdentities: 4000000, supportedPendingItems: 10000, validationOnly: true,
    derivation: 'Fabricated validation control; never measured', scope: 'finite synthetic control' },
  capability: { schema: 'financial-heap-capability-v1', sourceHead: hash, fixtureSha256: hash, configSha256: hash, buildSha256: hash, classpathSha256: hash,
    maxHeapBytes: HEAP_LAUNCHER.maxHeapBytes, usedHeapBytes: 16 * 1024 ** 2,
    vmArguments: HEAP_LAUNCHER.flags, javaVersion: 'synthetic-java21', javaVendor: 'validation-only' },
};
heap.reviewEvidencePath = 'review.json'; heap.reviewEvidenceSha256 = hash;
heap.review = { schema: 'financial-heap-bounds-review-v1', validationOnly: true, verdict: 'VALIDATION_ONLY', boundsEvidenceSha256: heap.boundsEvidenceSha256,
  ...Object.fromEntries(['sourceHead', 'fixtureSha256', 'configSha256', 'buildSha256', 'classpathSha256', 'supportedIdentities', 'supportedPendingItems', 'derivation', 'scope'].map(k => [k, heap.bounds[k]])) };
const arm = PLAN.ladder[0];
const policy = () => preparePolicy({ heap, calibration, correctness, preflight, arm, symlink });
function measurement() {
  return {
    schema: 'financial-rate-measurement-v1', policySha256: policy().policySha256,
    sourceHead: hash, fixtureSha256: hash, workloadSha256: hash,
    units: 'unique timed executions; preflight/aged rows excluded',
    producerElapsedMs: 60000, drainMs: 100,
    deadline: { offered: 150000, admitted: 150000, decided: 150000, settled: 150000, pending: 0 },
    final: { offered: 150000, admitted: 150000, decided: 150000, settled: 150000, pending: 0 },
    heapObservation: { schema: 'financial-heap-observation-v1', guardOutcome: 'PASS_SAMPLED', actualMaxHeapBytes: HEAP_LAUNCHER.maxHeapBytes,
      admissionLimitBytes: policy().heapEstimate.admissionLimitBytes, estimatedHeapBytes: policy().heapEstimate.estimatedHeapBytes, heapPeakBytes: 1024 },
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

test('deadline CAPTURE decision may precede paired SETTLE durable admission', () => {
  const m = measurement();
  // Injected publication cut: CAPTURE visible, SETTLE publication held.
  m.deadline = { offered: 1, admitted: 0, decided: 1, settled: 0, pending: 1 };
  const result = assessMeasurement(policy(), m);
  assert.equal(result.failures.includes('DEADLINE_COUNT_PARITY'), false);
  assert.equal(result.failures.includes('DEADLINE_USEFUL_RATE_MISS'), true);
  // Publication release and eventual drain cannot back-credit deadline rate.
  assert.equal(result.rates.admittedPerSecond, 0);
  assert.equal(result.result, 'FAIL_DIAGNOSTIC');
});

test('stage counts reject decided beyond offered and settled beyond admission', () => {
  for (const deadline of [
    { offered: 0, admitted: 0, decided: 1, settled: 0, pending: 1 },
    { offered: 1, admitted: 0, decided: 1, settled: 1, pending: 0 },
  ]) {
    const m = measurement(); m.deadline = deadline;
    assert.ok(assessMeasurement(policy(), m).failures.includes('DEADLINE_COUNT_PARITY'));
  }
});

test('preparation stops until E3 correctness and real calibration plus indexed/bounded adapter exist', () => {
  assert.equal(preparePolicy({}).status, 'BLOCKED');
  assert.match(preparePolicy({ heap, calibration, preflight, arm, symlink }).gaps.join(' '), /E3/);
  const copy = structuredClone(preflight); copy.retainsFullDomainHistory = true;
  assert.match(preparePolicy({ heap, calibration, correctness, preflight: copy, arm, symlink }).gaps.join(' '), /full-domain/);
});

test('source/fixture mismatches and fabricated zero-byte calibration cannot freeze runnable policy', () => {
  const c = { ...calibration, sourceHead: 'b'.repeat(64) };
  assert.equal(preparePolicy({ heap, calibration: c, correctness, preflight, arm, symlink }).status, 'BLOCKED');
  assert.equal(preparePolicy({ heap, calibration: { ...calibration, encodedBytes: 0 }, correctness, preflight, arm, symlink }).status, 'BLOCKED');
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
  assert.equal(preparePolicy({ heap, calibration, correctness, preflight: { ...preflight, guestFreeBytes: 1 }, arm, symlink }).status, 'BLOCKED');
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
  assert.equal(preparePolicy({ heap, calibration: slow, correctness, preflight, arm, symlink }).status, 'BLOCKED');
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
  assert.equal(preparePolicy({ heap, calibration: huge, correctness, preflight, arm, symlink }).status, 'BLOCKED');
  for (const value of [0, -1, 0.5, 1, 399999, '400000', 'bogus', null, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    const c = { ...huge, maxPhysicalBytesPerTrade: value };
    assert.equal(preparePolicy({ heap, calibration: c, correctness, preflight, arm, symlink }).status, 'BLOCKED', String(value));
    assert.throws(() => estimateAged(c, preflight, arm), /PHYSICAL_TRADE_BUDGET/, String(value));
  }
});

test('valid byte override preserves conservative floor and may increase budget only', () => {
  for (const value of [400, 800]) {
    const c = { ...calibration, maxPhysicalBytesPerTrade: value };
    const p = preparePolicy({ heap, calibration: c, correctness, preflight, arm, symlink });
    assert.equal(p.status, 'FROZEN');
    assert.equal(p.aged.physicalBytesPerTradeBudget, value);
    assert.equal(p.aged.fixedBytes, preflight.basePhysicalBytes + PLAN.pendingItems * c.pendingPhysicalBytes + arm.rate * arm.seconds * value);
  }
});

test('derived per-trade budget must remain safe integer before policy freezes', () => {
  const c = { ...calibration, sampleTrades: 1, physicalBytes: Number.MAX_SAFE_INTEGER };
  assert.equal(preparePolicy({ heap, calibration: c, correctness, preflight, arm, symlink }).status, 'BLOCKED');
  assert.throws(() => estimateAged(c, preflight, arm), /PHYSICAL_TRADE_BUDGET/);
});

test('invalid telemetry produces null derived metrics instead of non-finite values', () => {
  for (const value of [undefined, null, 'bad', -1, NaN, Infinity]) {
    const m = measurement(); m.resources.processCpuMs = value; m.resources.touchedKeys = value;
    const r = assessMeasurement(policy(), m);
    assert.notEqual(r.result, 'PASS_DIAGNOSTIC');
    assert.equal(r.cpuEquivalentCores, null);
    assert.equal(r.touchedKeysPerTrade, null);
  }
  const m = measurement(); m.resources.processCpuMs = 0; m.resources.touchedKeys = 0;
  const r = assessMeasurement(policy(), m);
  assert.equal(r.cpuEquivalentCores, 0); assert.equal(r.touchedKeysPerTrade, 0);
});

test('invalid duration and zero trades cannot publish non-finite derived metrics', () => {
  for (const seconds of [0, undefined, NaN, Infinity, -1]) {
    const p = policy(); p.arm = { ...p.arm, seconds };
    const m = measurement(); m.final.decided = 0;
    const r = assessMeasurement(p, m);
    assert.equal(r.result, 'FAIL_DIAGNOSTIC');
    assert.equal(r.cpuEquivalentCores, null); assert.equal(r.touchedKeysPerTrade, null);
    assert.ok(Object.values(r.rates).every(x => x === null));
  }
});

test('M3H missing conservative heap evidence blocks legacy real calibration', () => {
  assert.equal(preparePolicy({ calibration, correctness, preflight, arm, symlink }).status, 'BLOCKED');
});
test('M3H oversized retained heap cannot freeze despite fitting physical budget', () => {
  const c = { ...calibration, identityHeapBytes: 100000000, pendingHeapBytes: 100000000 };
  const p = { ...preflight, jvmMaxHeapBytes: 805306368, heapBaselineBytes: 134217728 };
  assert.equal(preparePolicy({ calibration: c, correctness, preflight: p, arm, symlink }).status, 'BLOCKED');
});

function reviewed(h) {
  h.review = { ...h.review, validationOnly: h.bounds.validationOnly,
    verdict: h.bounds.validationOnly ? 'VALIDATION_ONLY' : 'READY_CONSERVATIVE_BOUND', boundsEvidenceSha256: h.boundsEvidenceSha256,
    ...Object.fromEntries(['sourceHead', 'fixtureSha256', 'configSha256', 'buildSha256', 'classpathSha256', 'supportedIdentities', 'supportedPendingItems', 'derivation', 'scope'].map(k => [k, h.bounds[k]])) };
  return h;
}

test('M3H finite checked formula includes timed+aged identities, pending and both reserves', () => {
  const h = reviewed(structuredClone(heap));
  Object.assign(h.bounds, { identityHeapBytesUpper: 2, pendingHeapBytesUpper: 4, baselineHeapBytesUpper: 100,
    transientHeapReserveBytes: 10, replayHeapReserveBytes: 20 });
  Object.assign(h.capability, { maxHeapBytes: 1000, usedHeapBytes: 50 }); reviewed(h);
  assert.equal(estimateHeap(h, 10, 5, 3).estimatedHeapBytes, 172);
  assert.equal(estimateHeap(h, 10, 5, 3).admissionLimitBytes, 800);
  assert.throws(() => estimateHeap(h, -1), /EVIDENCE/);
});

test('M3H numeric controls reject missing, string, negative, NaN, infinity, unsafe and fractional values', () => {
  const keys = ['identityHeapBytesUpper', 'pendingHeapBytesUpper', 'baselineHeapBytesUpper', 'transientHeapReserveBytes',
    'replayHeapReserveBytes', 'supportedIdentities', 'supportedPendingItems'];
  for (const key of keys) for (const value of [undefined, null, '1', 0, -1, NaN, Infinity, 0.5, Number.MAX_SAFE_INTEGER + 1]) {
    const h = structuredClone(heap); h.bounds[key] = value; reviewed(h);
    assert.throws(() => estimateHeap(h, 1), /HEAP_/, `${key}/${value}`);
  }
  for (const value of [undefined, null, '805306368', 0, -1, NaN, Infinity, Number.MAX_SAFE_INTEGER + 1]) {
    const h = structuredClone(heap); h.capability.maxHeapBytes = value;
    assert.throws(() => estimateHeap(h, 1), /HEAP_/, String(value));
  }
  for (const value of [undefined, null, '0', -1, NaN, Infinity, 0.5, Number.MAX_SAFE_INTEGER + 1]) {
    const h = structuredClone(heap); h.capability.usedHeapBytes = value;
    assert.throws(() => estimateHeap(h, 1), /HEAP_/, String(value));
  }
});

test('M3H child lower max recomputes limit, higher unauthorized max cannot raise request cap', () => {
  const actual = { ...heap.capability, maxHeapBytes: 100000000 };
  assert.throws(() => estimateHeap(heap, 150000, 0, 0, actual), /ADMISSION_LIMIT/);
  assert.throws(() => estimateHeap(heap, 1, 0, 0, { ...heap.capability, maxHeapBytes: HEAP_LAUNCHER.maxHeapBytes + 1 }), /UNAUTHORIZED_MAX/);
  assert.throws(() => estimateHeap({ ...heap, authorizedMaxHeapBytes: HEAP_LAUNCHER.maxHeapBytes * 2 }, 1), /EVIDENCE/);
  assert.throws(() => estimateHeap(heap, 1, 0, 0, { ...heap.capability, usedHeapBytes: heap.bounds.baselineHeapBytesUpper + 1 }), /BASELINE_BOUND/);
});

test('M3H equality, unsupported counts and multiplication/addition overflow refuse', () => {
  const h = structuredClone(heap);
  Object.assign(h.bounds, { baselineHeapBytesUpper: 100, identityHeapBytesUpper: 2, pendingHeapBytesUpper: 4, transientHeapReserveBytes: 10, replayHeapReserveBytes: 688 });
  Object.assign(h.capability, { maxHeapBytes: 1000, usedHeapBytes: 50 }); reviewed(h);
  assert.throws(() => estimateHeap(h, 1), /ADMISSION_LIMIT/);
  h.bounds.replayHeapReserveBytes--; assert.equal(estimateHeap(h, 1).estimatedHeapBytes, 799);
  assert.throws(() => estimateHeap(h, 1, 0, 0, { ...h.capability, maxHeapBytes: 999 }), /ADMISSION_LIMIT/);
  assert.throws(() => estimateHeap(h, h.bounds.supportedIdentities + 1), /UNSUPPORTED_COUNTS/);
  assert.throws(() => estimateHeap(h, 0, 0, h.bounds.supportedPendingItems + 1), /UNSUPPORTED_COUNTS/);
  h.bounds.identityHeapBytesUpper = Number.MAX_SAFE_INTEGER; assert.throws(() => estimateHeap(h, 2), /OVERFLOW/);
  h.bounds.identityHeapBytesUpper = 1; h.bounds.baselineHeapBytesUpper = Number.MAX_SAFE_INTEGER;
  assert.throws(() => estimateHeap(h, 1), /OVERFLOW/);
});

test('M3H fresh and aged overrun block without silently shrinking original heap workload', () => {
  const h = structuredClone(heap); h.bounds.identityHeapBytesUpper = 1000;
  const fresh = preparePolicy({ calibration, correctness, preflight, arm, heap: h });
  assert.equal(fresh.status, 'FROZEN');
  const aged = preparePolicy({ calibration, correctness, preflight, arm: { ...arm, state: 'aged' }, heap: h });
  assert.equal(aged.status, 'BLOCKED'); assert.ok(aged.gaps.includes('HEAP_ADMISSION_LIMIT'));
  assert.equal(aged.aged.proposedIdentities, 1000000); assert.equal(aged.aged.identities, 1000000);
  h.bounds.identityHeapBytesUpper = 100000000;
  assert.equal(preparePolicy({ calibration, correctness, preflight, arm, heap: h }).status, 'BLOCKED');
});

test('M3H candidate/config/fixture/build/VM/review mismatch or legacy averages refuse', () => {
  for (const key of ['sourceHead', 'fixtureSha256', 'configSha256', 'buildSha256', 'classpathSha256']) {
    const h = structuredClone(heap); h.capability[key] = 'b'.repeat(64);
    assert.throws(() => estimateHeap(h, 1), /EVIDENCE/, key);
    const actual = { ...heap.capability, [key]: 'b'.repeat(64) };
    assert.throws(() => estimateHeap(heap, 1, 0, 0, actual), /EVIDENCE/, `actual/${key}`);
  }
  const h = structuredClone(heap); h.capability.vmArguments = ['-Xmx1536m']; assert.throws(() => estimateHeap(h, 1), /EVIDENCE/);
  for (const field of ['verdict', 'boundsEvidenceSha256', 'sourceHead', 'fixtureSha256', 'buildSha256', 'classpathSha256', 'configSha256', 'supportedIdentities', 'supportedPendingItems', 'derivation', 'scope']) {
    const bad = structuredClone(heap); delete bad.review[field]; assert.throws(() => estimateHeap(bad, 1), /EVIDENCE/, field);
  }
  const legacy = { ...heap, bounds: { identityHeapBytes: 100, pendingHeapBytes: 200 } };
  assert.throws(() => estimateHeap(legacy, 1), /EVIDENCE/);
});

async function rawPolicy(dir) {
  // False validationOnly and Ready text here model forged contract provenance only;
  // fixture remains fabricated and never used for JVM load/capacity evidence.
  const h = structuredClone(heap); const fixture = Buffer.from('synthetic-fixture-provenance-control');
  const byteHash = bytes => createHash('sha256').update(bytes).digest('hex');
  const fixtureHash = byteHash(fixture);
  h.bounds.fixtureSha256 = fixtureHash; h.capability.fixtureSha256 = fixtureHash; h.bounds.validationOnly = false;
  const b = Buffer.from(JSON.stringify(h.bounds)); h.boundsEvidenceSha256 = byteHash(b); reviewed(h);
  const c = Buffer.from(JSON.stringify(h.capability)); h.capabilityEvidenceSha256 = byteHash(c);
  const r = Buffer.from(JSON.stringify(h.review)); h.reviewEvidenceSha256 = byteHash(r);
  const p = preparePolicy({ calibration: { ...calibration, fixtureSha256: fixtureHash }, correctness: { ...correctness, fixtureSha256: fixtureHash }, preflight, arm, heap: h });
  assert.equal(p.status, 'FROZEN');
  await Promise.all([['bounds.json', b], ['capability.json', c], ['review.json', r], ['fixture.json', fixture], ['policy.json', JSON.stringify(p)]].map(([name, bytes]) => writeFile(path.join(dir, name), bytes)));
  return { p, policyPath: path.join(dir, 'policy.json') };
}

test('M3H executable raw bound/capability/review/fixture bytes hash and exact scope verified before adapter read', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'heap-evidence-control-'));
  try {
    const { p, policyPath } = await rawPolicy(dir); await verifyHeapEvidence(p, policyPath);
    for (const name of ['bounds.json', 'capability.json', 'review.json']) {
      await rawPolicy(dir); await writeFile(path.join(dir, name), 'forged');
      await assert.rejects(verifyHeapEvidence(p, policyPath), /RAW_EVIDENCE_HASH/);
      await assert.rejects(runAdapter(policyPath, '/adapter-must-not-be-read', path.join(dir, 'unused-output'), '--authorize-load'), /RAW_EVIDENCE_HASH/);
    }
    await rawPolicy(dir); await writeFile(path.join(dir, 'fixture.json'), 'changed-fixture');
    await assert.rejects(verifyHeapEvidence(p, policyPath), /FIXTURE_IDENTITY/);
    await rawPolicy(dir); await rm(path.join(dir, 'review.json'));
    await assert.rejects(verifyHeapEvidence(p, policyPath), /ENOENT/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('M3H synthetic evidence and unpinned launcher cannot authorize executable load', async () => {
  await assert.rejects(verifyHeapEvidence(policy(), '/unread-policy'), /VALIDATION_ONLY/);
  const dir = await mkdtemp(path.join(tmpdir(), 'heap-launcher-control-'));
  try {
    const { policyPath } = await rawPolicy(dir);
    const adapterPath = path.join(dir, 'adapter.json'); await writeFile(adapterPath, JSON.stringify({ command: 'node', args: [] }));
    await assert.rejects(runAdapter(policyPath, adapterPath, path.join(dir, 'never-created'), '--authorize-load'), /PINNED_LAUNCHER/);
    const requestPath = path.join(dir, 'legacy-calibration.json'); await writeFile(requestPath, JSON.stringify({ correctness, adapterCalibrationContract: 'financial-rate-calibration-v1' }));
    await assert.rejects(calibrateAdapter(requestPath, '/unread-adapter', '/unused-out', '--authorize-calibration'), /HEAP_CONSERVATIVE_EVIDENCE_REQUIRED/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('M3H runtime guard failure or missing lifecycle observation cannot publish diagnostic success', () => {
  const m = measurement(); delete m.heapObservation;
  assert.equal(assessMeasurement(policy(), m).result, 'LIMITED');
  for (const update of [{ guardOutcome: 'FAILED' }, { actualMaxHeapBytes: HEAP_LAUNCHER.maxHeapBytes + 1 },
    { heapPeakBytes: policy().heapEstimate.admissionLimitBytes }, { estimatedHeapBytes: 1 }, { admissionLimitBytes: 0 }]) {
    const changed = measurement(); Object.assign(changed.heapObservation, update);
    assert.ok(assessMeasurement(policy(), changed).failures.includes('HEAP_LIFECYCLE_PROTECTION_FAILED'));
  }
});

test('M3H capability timeout escalates TERM to KILL with bounded output and retained process error', async () => {
  const fake = () => {
    const child = new EventEmitter(); child.stdout = new PassThrough(); child.stderr = new PassThrough(); child.kills = [];
    child.kill = signal => { child.kills.push(signal); if (signal === 'SIGKILL') queueMicrotask(() => child.emit('close', null, signal)); return true; };
    return child;
  };
  const child = fake(); const result = await captureHeapCapability('mock-java', [], { timeoutMs: 5, killAfterMs: 5, spawnProcess: () => child });
  assert.equal(result.timedOut, true); assert.equal(result.signal, 'SIGKILL'); assert.deepEqual(child.kills, ['SIGTERM', 'SIGKILL']);
  const overflow = fake(); queueMicrotask(() => overflow.stdout.end('x'.repeat(70000)));
  const huge = await captureHeapCapability('mock-java', [], { timeoutMs: 100, killAfterMs: 5, spawnProcess: () => overflow });
  assert.equal(huge.outputOverflow, true); assert.equal(huge.stdout.length, 65536);
  const error = fake(); queueMicrotask(() => error.emit('error', Error('missing-java')));
  assert.equal((await captureHeapCapability('mock-java', [], { spawnProcess: () => error })).error, 'missing-java');
});

test('M3H symlink outside policy ownership and valid hash with changed raw identity refuse', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'heap-owned-control-')); const other = await mkdtemp(path.join(tmpdir(), 'heap-external-control-'));
  try {
    const { p, policyPath } = await rawPolicy(dir); const bytes = JSON.stringify(p.heap.bounds);
    await writeFile(path.join(other, 'bounds.json'), bytes); await rm(path.join(dir, 'bounds.json'));
    await symlink(path.join(other, 'bounds.json'), path.join(dir, 'bounds.json'));
    await assert.rejects(verifyHeapEvidence(p, policyPath), /MUST_BE_OWNED/);
    await rm(path.join(dir, 'bounds.json')); await rawPolicy(dir);
    const changed = { ...p.heap.bounds, baselineHeapBytesUpper: p.heap.bounds.baselineHeapBytesUpper + 1 };
    const raw = Buffer.from(JSON.stringify(changed)); await writeFile(path.join(dir, 'bounds.json'), raw);
    const changedPolicy = structuredClone(p); changedPolicy.heap.boundsEvidenceSha256 = createHash('sha256').update(raw).digest('hex');
    changedPolicy.heap.review.boundsEvidenceSha256 = changedPolicy.heap.boundsEvidenceSha256;
    await assert.rejects(verifyHeapEvidence(changedPolicy, policyPath), /RAW_EVIDENCE_IDENTITY/);
  } finally { await Promise.all([rm(dir, { recursive: true, force: true }), rm(other, { recursive: true, force: true })]); }
});
