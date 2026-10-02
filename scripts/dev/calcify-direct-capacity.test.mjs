import test from 'node:test';
import assert from 'node:assert/strict';
import { directConfig, directBaselineMatches, summarizeDirectRun } from './calcify-direct-capacity.mjs';

function qualified() {
  const total = 3150000;
  const stream = () => ({ exitCode: 0, windows: [{ records: total, receivedMonotonicMs: 300900 }],
    resultReceivedMonotonicMs: 301500, result: { pass: true, records: total, expected: total } });
  return { policy: { seconds: 300, targetContextsPerSecond: 10000, preflightContexts: 0, maxLoadOverrunMs: 1000, maxDrainMs: 5000 },
    loadSpawnMonotonicMs: 1000, loadExitCode: 0, baselinePass: true,
    load: { gatePassed: true, receiptGeneration: 0, failures: 0, retries: 0, completedPairs: total, acceptedOrders: total * 2,
      acceptedOrdersAtDeadline: total * 2, elapsedMs: 300200, deadlineAccounting: 'fixed monotonic', loadStartedEpochMs: 1000, scheduledDeadlineEpochMs: 301000 },
    streams: { verified: stream(), resolved: stream() }, oracleExitCode: 0,
    oracle: { pass: true, acceptedOrders: total * 2, records: total, expected: total, fullFactParity: true, orderedUnique: true } };
}

test('qualifies trade contexts/s and reports distinct order units', () => {
  const result = summarizeDirectRun(qualified());
  assert.equal(result.pass, true);
  assert.equal(result.rates.resolved.contextsPerSecond, 10500);
  assert.equal(result.acceptedOrdersAtDeadlinePerSecond, 21000);
});

test('after-deadline observation cannot rescue failed sustained throughput', () => {
  const evidence = qualified();
  evidence.streams.resolved.windows = [{ records: 2999999, receivedMonotonicMs: 300999 }, { records: 3150000, receivedMonotonicMs: 301001 }];
  const result = summarizeDirectRun(evidence);
  assert.equal(result.pass, false);
  assert.ok(result.reasons.includes('resolved below target contexts/s'));
});

test('preflight does not contribute to measured throughput', () => {
  const evidence = qualified(); evidence.policy.preflightContexts = 1;
  for (const stream of Object.values(evidence.streams)) {
    stream.result.records++; stream.result.expected++;
    stream.windows = [{ records: 3000000, receivedMonotonicMs: 300999 }, { records: 3150001, receivedMonotonicMs: 301001 }];
  }
  evidence.oracle.records++; evidence.oracle.expected++;
  assert.equal(summarizeDirectRun(evidence).rates.resolved.coveredBeforeConservativeDeadline, 2999999);
  assert.equal(summarizeDirectRun(evidence).pass, false);
});

test('missing baseline, verified count, or full-fact proof fails closed', () => {
  for (const corrupt of [e => { e.baselinePass = false; }, e => { e.streams.verified.result.records--; },
    e => { e.oracle.fullFactParity = false; }, e => { e.oracle.acceptedOrders--; }, e => { e.streams.resolved.result = null; }, e => { e.load.receiptGeneration = 1; }]) {
    const evidence = qualified(); corrupt(evidence);
    assert.equal(summarizeDirectRun(evidence).pass, false);
  }
});

test('drain bound starts from HTTP completion, not later report receipt', () => {
  const evidence = qualified();
  evidence.streams.resolved.windows = [{ records: 3001000, receivedMonotonicMs: 300999 }, { records: 3150000, receivedMonotonicMs: 306201 }];
  const result = summarizeDirectRun(evidence);
  assert.equal(result.rates.resolved.drainUpperMs, 5001);
  assert.equal(result.pass, false);
});

test('short diagnostic cannot qualify', () => {
  const evidence = qualified(); evidence.policy.seconds = 60;
  assert.equal(summarizeDirectRun(evidence).pass, false);
  assert.equal(summarizeDirectRun(evidence).capacityClaim, false);
});

test('Go omits the disabled receipt-generation field', () => {
  const evidence = qualified(); delete evidence.load.receiptGeneration;
  assert.equal(summarizeDirectRun(evidence).pass, true);
});

test('direct config defaults to no preflight and distinct path units', () => {
  const env = Object.fromEntries(Object.entries({ SMOKE_ID: 'direct-test', GENERATION: '1', OUT_DIR: '/tmp/direct-test', IMAGE: 'frozen:image', NETWORK: 'reef', LOAD_BINARY: '/tmp/load', SCOPE: 'local RF1' }).map(([k,v]) => [`CALCIFY_DIRECT_${k}`,v]));
  const cfg = directConfig(env);
  assert.equal(cfg.pace, 10500); assert.equal(cfg.preflightContexts, 0); assert.equal(cfg.seconds, 300);
  assert.equal(cfg.mode, 'paired'); assert.equal(cfg.ordersPerTrade, 2); assert.equal(cfg.preflightOrders, 0);
  assert.equal(directConfig({ ...env, CALCIFY_DIRECT_PREFLIGHT_CONTEXTS: '1' }).preflightOrders, 2);
  const aggressor = directConfig({ ...env, CALCIFY_DIRECT_WORKLOAD_MODE: 'aggressor', CALCIFY_DIRECT_PREFLIGHT_ORDERS: '64' });
  assert.equal(aggressor.ordersPerTrade, 1); assert.equal(aggressor.preflightOrders, 64);
  assert.throws(() => directConfig({ ...env, CALCIFY_DIRECT_WORKLOAD_MODE: 'unknown' }));
  assert.throws(() => directConfig({ ...env, CALCIFY_DIRECT_SECONDS: '0' }));
});


test('aggressor counts one timed order per trade and excludes 64 seeded makers', () => {
  const evidence = qualified();
  evidence.policy.workloadMode = 'aggressor'; evidence.policy.ordersPerTrade = 1; evidence.policy.preflightOrders = 64;
  evidence.load.workloadMode = 'aggressor'; evidence.load.ordersPerTrade = 1; evidence.load.completedTrades = evidence.load.completedPairs;
  evidence.load.acceptedOrders = evidence.load.completedTrades;
  evidence.load.acceptedOrdersAtDeadline = evidence.load.completedTrades;
  evidence.oracle.acceptedOrders = evidence.load.acceptedOrders + 64;
  const result = summarizeDirectRun(evidence);
  assert.equal(result.pass, true);
  assert.equal(result.rates.resolved.contextsPerSecond, 10500);
  assert.equal(result.acceptedOrdersAtDeadlinePerSecond, 10500);
  assert.equal(result.expectedContexts, evidence.load.completedTrades);
  evidence.oracle.acceptedOrders--;
  assert.equal(summarizeDirectRun(evidence).pass, false);
});

test('aggressor report must agree with configured mode and trade aliases', () => {
  const evidence = qualified(); evidence.policy.workloadMode = 'aggressor'; evidence.policy.ordersPerTrade = 1; evidence.policy.preflightOrders = 64;
  assert.equal(summarizeDirectRun(evidence).pass, false);
  evidence.policy.workloadMode = 'paired'; evidence.policy.ordersPerTrade = 2; evidence.policy.preflightOrders = 0;
  evidence.load.completedTrades = evidence.load.completedPairs - 1;
  assert.equal(summarizeDirectRun(evidence).pass, false);
});


test('zero-trade maker baseline requires exactly the configured accepted orders', () => {
  const baseline = { verifiedRecords: 0, resolvedRecords: 0, sourceTrades: 0, sourceAcceptedOrders: 64 };
  const config = { preflightContexts: 0, preflightOrders: 64 };
  assert.equal(directBaselineMatches(baseline, config), true);
  assert.equal(directBaselineMatches({ ...baseline, sourceAcceptedOrders: 63 }, config), false);
  assert.equal(directBaselineMatches({ ...baseline, sourceTrades: 1 }, config), false);
  assert.equal(directBaselineMatches({ ...baseline, sourceAcceptedOrders: undefined }, config), false);
});
