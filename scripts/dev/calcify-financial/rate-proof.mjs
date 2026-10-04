import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, open, unlink } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { spawn } from 'node:child_process';
import { pipeline } from 'node:stream/promises';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

export const PLAN = Object.freeze({
  schema: 'financial-e4-plan-v1', stage: 'synthetic ordered financial adapter; single closed hot domain',
  ladder: [2500, 5000, 10000].map(rate => ({ rate, seconds: 60, state: 'fresh' })),
  repeatSeconds: 300, proposedIdentities: 1000000, pendingItems: 10000,
  diskBudgetBytes: 10 * 1024 ** 3, diskHeadroomBytes: 20 * 1024 ** 3,
  maxProducerOverrunMs: 1000, maxDrainMs: 5000, maxLagSeconds: 2,
  trade: { shares: '1', priceNanos: '10000000' },
  qualification: false,
  assumptions: ['Proposed diagnostic thresholds; no accepted capacity SLO.',
    'Serial capacity arms only; source/build/config fixed during timed load.',
    'One timed unique execution = one trade; technical/business decisions are separate counts.',
    'Prefunding covers exact declared trades plus pending cohort; no unlimited funding.',
    'Fresh/aged compare identical business input bytes; aged preflight rows excluded.',
    'D7 resolver-stage RF1/standing-liquidity result does not transfer to financial RF3/hot-domain stage.'],
});
const sha = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const revision = value => typeof value === 'string' && /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/.test(value);
const nonnegative = value => Number.isSafeInteger(value) && value >= 0;
const finiteNonnegative = value => Number.isFinite(value) && value >= 0;
const positive = value => Number.isSafeInteger(value) && value > 0;

export function estimateAged(calibration, preflight, arm = { rate: 10000, seconds: 300 }) {
  const perTrade = calibration.maxPhysicalBytesPerTrade ?? Math.ceil(calibration.physicalBytes / calibration.sampleTrades) * 2;
  const fixed = preflight.basePhysicalBytes + 10000 * calibration.pendingPhysicalBytes + arm.rate * arm.seconds * perTrade;
  const proposed = fixed + PLAN.proposedIdentities * calibration.identityPhysicalBytes;
  const identities = Math.min(PLAN.proposedIdentities, Math.max(0, Math.floor((PLAN.diskBudgetBytes - fixed) / calibration.identityPhysicalBytes)));
  return { scope: proposed <= PLAN.diskBudgetBytes ? 'PROPOSED_AGED_DIAGNOSTIC' : 'REDUCED_AGED_DIAGNOSTIC',
    proposedIdentities: PLAN.proposedIdentities, proposedEstimatedBytes: proposed,
    identities, pendingItems: PLAN.pendingItems, estimatedBytes: fixed + identities * calibration.identityPhysicalBytes,
    fixedBytes: fixed, physicalBytesPerTradeBudget: perTrade,
    limit: proposed <= PLAN.diskBudgetBytes ? null : 'Proposed 1m-identity aged fixture exceeds10GiB; reduced diagnostic does not qualify original fixture.' };
}

export function preparePolicy(request = {}) {
  const { calibration: c, correctness: e3, preflight: p, arm } = request;
  const gaps = [];
  if (!e3 || e3.schema !== 'financial-e3-correctness-v1' || e3.result !== 'PASS' || !hash(e3.evidenceSha256)) gaps.push('E3_CORRECTNESS_REQUIRED');
  if (!c || c.schema !== 'financial-rate-calibration-v1' || !hash(c.realRecordEvidenceSha256)
    || !revision(c.sourceHead) || !hash(c.fixtureSha256) || !positive(c.sampleTrades) || !positive(c.encodedBytes)
    || !positive(c.physicalBytes) || !positive(c.identityPhysicalBytes) || !positive(c.pendingPhysicalBytes)
    || c.physicalReplicationIncluded !== true) gaps.push('REAL_RECORD_BYTE_CALIBRATION_REQUIRED');
  if (!p || !nonnegative(p.basePhysicalBytes) || !positive(p.guestFreeBytes)) gaps.push('GUEST_DISK_PREFLIGHT_REQUIRED');
  if (!p || p.indexedReadView !== true || p.boundedWrites !== true || p.retainsFullDomainHistory !== false || !hash(p.accessPatternEvidenceSha256))
    gaps.push('Indexed read view/bounded changes required; no full-domain history copy or per-trade scan.');
  if (!arm || ![2500, 5000, 10000].includes(arm.rate) || ![60, 300].includes(arm.seconds) || !['fresh', 'aged'].includes(arm.state)) gaps.push('FROZEN_ARM_REQUIRED');
  if (c && e3 && (c.sourceHead !== e3.sourceHead || c.fixtureSha256 !== e3.fixtureSha256)) gaps.push('E3_CALIBRATION_PROVENANCE_MISMATCH');
  for (const role of ['producer', 'observer']) {
    const s = c?.[role];
    if (!s || !positive(s.elapsedMs) || !positive(s.completedTrades) || !arm || s.completedTrades * 1000 / s.elapsedMs < arm.rate || (role === 'observer' && s.exactParity !== true))
      gaps.push(`${role.toUpperCase()}_REAL_RECORD_RATE_CALIBRATION_REQUIRED`);
  }
  if (gaps.length) return { schema: 'financial-e4-policy-v1', status: 'BLOCKED', plan: PLAN, gaps };
  const aged = estimateAged(c, p, arm);
  if (aged.fixedBytes > PLAN.diskBudgetBytes) gaps.push('Timed+pending cohort alone exceeds10GiB; no arm permitted.');
  if (p.guestFreeBytes < aged.estimatedBytes + PLAN.diskHeadroomBytes) gaps.push('GUEST_SPACE_INSUFFICIENT');
  if (arm.seconds === 300 && (!hash(arm.workloadSha256) || request.ladderResult !== 'PASS_DIAGNOSTIC')) gaps.push('STABLE_LADDER_AND_REPEAT_WORKLOAD_REQUIRED');
  if (gaps.length) return { schema: 'financial-e4-policy-v1', status: 'BLOCKED', plan: PLAN, aged, gaps };
  const trades = BigInt(arm.rate * arm.seconds + (arm.state === 'aged' ? PLAN.pendingItems + aged.identities : 0));
  const cash = trades * BigInt(PLAN.trade.priceNanos);
  if (cash > (1n << 63n) - 1n) throw new Error('PREFUND_INT64_OVERFLOW');
  const policy = { schema: 'financial-e4-policy-v1', status: 'FROZEN', plan: PLAN,
    arm, sourceHead: c.sourceHead, fixtureSha256: c.fixtureSha256, calibrationSha256: sha(c),
    e3EvidenceSha256: e3.evidenceSha256, accessPatternEvidenceSha256: p.accessPatternEvidenceSha256,
    aged, openingResources: { cashNanos: cash.toString(), shares: trades.toString() },
    expectedTimedTrades: arm.rate * arm.seconds, gaps: [], capacityQualification: false };
  return { ...policy, policySha256: sha(policy) };
}

export function assessMeasurement(policy, m) {
  const failures = [], gaps = Array.isArray(m?.gaps) ? [...m.gaps] : [];
  if (policy.status !== 'FROZEN' || policy.policySha256 !== sha(Object.fromEntries(Object.entries(policy).filter(([k]) => k !== 'policySha256'))))
    failures.push('POLICY_NOT_FROZEN_OR_CHANGED');
  if (!m || m.schema !== 'financial-rate-measurement-v1') return { result: 'LIMITED', failures, gaps: ['MEASUREMENT_ARTIFACT_REQUIRED'], capacityQualification: false };
  if (m.policySha256 !== policy.policySha256 || m.sourceHead !== policy.sourceHead || m.fixtureSha256 !== policy.fixtureSha256
    || !hash(m.workloadSha256) || (policy.arm?.workloadSha256 && policy.arm.workloadSha256 !== m.workloadSha256)) failures.push('MEASUREMENT_PROVENANCE_MISMATCH');
  if (m.units !== 'unique timed executions; preflight/aged rows excluded') failures.push('TRADE_UNIT_MISMATCH');
  const durationMs = policy.arm?.seconds * 1000;
  const stages = ['offered', 'admitted', 'decided', 'settled', 'pending'];
  for (const cut of ['deadline', 'final']) {
    const c = m[cut];
    if (!c || stages.some(k => !nonnegative(c[k]))) { gaps.push(`${cut.toUpperCase()}_STAGE_COUNTS_REQUIRED`); continue; }
    if (c.offered < c.admitted || c.admitted < c.decided || c.decided !== c.settled + c.pending) failures.push(`${cut.toUpperCase()}_COUNT_PARITY`);
  }
  if (!positive(m.producerElapsedMs)) gaps.push('PRODUCER_ELAPSED_REQUIRED');
  else if (m.producerElapsedMs > durationMs + PLAN.maxProducerOverrunMs || m.producerElapsedMs < durationMs) failures.push('PRODUCER_DURATION_MISS');
  if (!nonnegative(m.drainMs)) gaps.push('DRAIN_REQUIRED');
  else if (m.drainMs > PLAN.maxDrainMs) failures.push('DRAIN_MISS');
  if (m.deadline && (m.deadline.decided < policy.expectedTimedTrades || m.deadline.settled < policy.expectedTimedTrades)) failures.push('DEADLINE_USEFUL_RATE_MISS');
  if (m.final && (m.final.offered !== policy.expectedTimedTrades || m.final.admitted !== m.final.offered
    || m.final.decided !== m.final.admitted || m.final.settled !== m.final.decided || m.final.pending !== 0)) failures.push('FINAL_COUNT_PARITY');
  if (!m.parity || m.parity.completeJournal !== true || m.parity.completeOwnerState !== true || m.parity.independentOracle !== true || !hash(m.parity.evidenceSha256)) failures.push('ACCOUNTING_PARITY');
  const samples = m.lagSamples;
  if (!Array.isArray(samples) || samples.length < 3 || samples.some((s, i) => !nonnegative(s.elapsedMs) || !nonnegative(s.lag) || (i && s.elapsedMs <= samples[i - 1].elapsedMs))
    || samples[0]?.elapsedMs !== 0 || samples.at(-1)?.elapsedMs !== durationMs) gaps.push('CONTINUOUS_LAG_BOUNDARY_SAMPLES_REQUIRED');
  else if (samples.at(-1).lag > policy.arm.rate * PLAN.maxLagSeconds || samples.at(-1).lag > samples[Math.floor(samples.length / 2)].lag) failures.push('LAG_END_OR_GROWTH');
  const required = ['processCpuMs', 'heapPeakBytes', 'rssPeakBytes', 'diskPeakBytes', 'encodedInputBytes', 'encodedResultBytes', 'encodedTechnicalBytes',
    'physicalBrokerBytes', 'physicalChangelogBytes', 'touchedKeys', 'technicalRecords', 'transactionWaitMs'];
  if (!m.resources || required.some(k => !(['processCpuMs', 'transactionWaitMs'].includes(k) ? finiteNonnegative(m.resources[k]) : nonnegative(m.resources[k])))) gaps.push('RESOURCE_TELEMETRY_INCOMPLETE');
  if (m.resources?.diskPeakBytes > PLAN.diskBudgetBytes) failures.push('DISK_BUDGET_EXCEEDED');
  if (!m.restore || !nonnegative(m.restore.records) || !nonnegative(m.restore.bytes) || !finiteNonnegative(m.restore.elapsedMs)
    || m.restore.verifiedCut !== true) gaps.push('RESTORE_WORK_REQUIRED');
  const latency = m.stageLatency;
  if (!latency || latency.kind !== 'sampled-individual' || latency.clockDomain !== 'same-monotonic' || !positive(latency.samples)
    || !finiteNonnegative(latency.p95Ms) || !finiteNonnegative(latency.p99Ms) || latency.p99Ms < latency.p95Ms) gaps.push('LATENCY_CLOCK_UNQUALIFIED');
  return { result: failures.length ? 'FAIL_DIAGNOSTIC' : gaps.length ? 'LIMITED' : 'PASS_DIAGNOSTIC', failures, gaps,
    rates: Object.fromEntries(stages.map(stage => [`${stage}PerSecond`, nonnegative(m.deadline?.[stage]) ? m.deadline[stage] * 1000 / durationMs : null])),
    rateBoundary: 'Counts visible at fixed deadline / scheduled duration; conservative covering rates, not individual latency.',
    cpuEquivalentCores: m.resources ? m.resources.processCpuMs / durationMs : null,
    touchedKeysPerTrade: m.resources && positive(m.final?.decided) ? m.resources.touchedKeys / m.final.decided : null,
    capacityQualification: false, sourceHead: policy.sourceHead, policySha256: policy.policySha256 };
}

export function selectRepeat(ladder, workloadSha256) {
  if (!hash(workloadSha256)) throw new Error('WORKLOAD_HASH_REQUIRED');
  const pass = ladder.filter(r => [2500, 5000, 10000].includes(r.rate) && r.result === 'PASS_DIAGNOSTIC').sort((a, b) => b.rate - a.rate)[0];
  return pass ? ['fresh', 'aged'].map(state => ({ rate: pass.rate, seconds: 300, state, workloadSha256 })) : [];
}

// Adapter contract: real executable receives --financial-rate-policy PATH and
// --financial-rate-measurement PATH; owns producer/observer, emits measured schema.
// E3 init/seed/worker/observe/reconstruct does not yet implement this rate contract.
async function runAdapterUnlocked(policyPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-load') throw new Error('Explicit --authorize-load required after E3 correctness.');
  const policy = JSON.parse(await readFile(policyPath, 'utf8'));
  if (policy.status !== 'FROZEN' || policy.policySha256 !== sha(Object.fromEntries(Object.entries(policy).filter(([k]) => k !== 'policySha256')))) throw new Error('FROZEN_POLICY_REQUIRED');
  const adapter = JSON.parse(await readFile(adapterPath, 'utf8'));
  if (typeof adapter.command !== 'string' || !Array.isArray(adapter.args) || adapter.args.some(x => typeof x !== 'string')) throw new Error('ADAPTER_COMMAND_REQUIRED');
  await mkdir(out); // Unique run directory: refuse overwriting previous attempts.
  const measurementPath = path.resolve(out, 'measurement.json');
  await writeFile(path.join(out, 'policy.json'), `${JSON.stringify(policy, null, 2)}\n`);
  const args = [...adapter.args, '--financial-rate-policy', path.resolve(policyPath), '--financial-rate-measurement', measurementPath];
  await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
  const child = spawn(adapter.command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
  const exit = new Promise(resolve => { child.once('error', error => resolve({ error: error.message })); child.once('close', (code, signal) => resolve({ code, signal })); });
  const streams = await Promise.allSettled([pipeline(child.stdout, createWriteStream(path.join(out, 'stdout.log'))), pipeline(child.stderr, createWriteStream(path.join(out, 'stderr.log')))]);
  const ended = await exit;
  await writeFile(path.join(out, 'process.json'), `${JSON.stringify({ ...ended, streamErrors: streams.filter(r => r.status === 'rejected').map(r => r.reason.message) }, null, 2)}\n`);
  if (ended.code !== 0) throw new Error('ADAPTER_PROCESS_FAILED; raw attempt preserved.');
  const result = assessMeasurement(policy, JSON.parse(await readFile(measurementPath, 'utf8')));
  await writeFile(path.join(out, 'assessment.json'), `${JSON.stringify(result, null, 2)}\n`);
  return result;
}

export async function runAdapter(policyPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-load') throw new Error('Explicit --authorize-load required after E3 correctness.');
  const lockPath = '/private/tmp/reef-financial-rate-capacity.lock';
  const lock = await open(lockPath, 'wx'); // Refuse concurrent capacity loads; no stale-lock auto deletion.
  try {
    await lock.writeFile(`${JSON.stringify({ pid: process.pid, policyPath, out })}\n`);
    return await runAdapterUnlocked(policyPath, adapterPath, out, authorize);
  } finally { await lock.close(); await unlink(lockPath); }
}

// Real calibration adapter is a separate operation: it runs actual serialization,
// producer and observer and emits measured calibration evidence. No benchmark
// generator lives here. Missing implementation blocks this entry point explicitly.
export async function calibrateAdapter(requestPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-calibration') throw new Error('Explicit --authorize-calibration required after E3 correctness.');
  const request = JSON.parse(await readFile(requestPath, 'utf8'));
  if (request.correctness?.result !== 'PASS' || !hash(request.correctness?.evidenceSha256)) throw new Error('E3_CORRECTNESS_REQUIRED');
  if (request.adapterCalibrationContract !== 'financial-rate-calibration-v1') throw new Error('REAL_CALIBRATION_ADAPTER_NOT_READY');
  const adapter = JSON.parse(await readFile(adapterPath, 'utf8'));
  if (typeof adapter.command !== 'string' || !Array.isArray(adapter.args) || adapter.args.some(x => typeof x !== 'string')) throw new Error('ADAPTER_COMMAND_REQUIRED');
  const lockPath = '/private/tmp/reef-financial-rate-capacity.lock';
  const lock = await open(lockPath, 'wx');
  try {
    await mkdir(out);
    const resultPath = path.resolve(out, 'calibration.json');
    const args = [...adapter.args, '--financial-rate-calibration-request', path.resolve(requestPath), '--financial-rate-calibration', resultPath];
    await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
    const child = spawn(adapter.command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    const exit = new Promise(resolve => { child.once('error', error => resolve({ error: error.message })); child.once('close', (code, signal) => resolve({ code, signal })); });
    await Promise.allSettled([pipeline(child.stdout, createWriteStream(path.join(out, 'stdout.log'))), pipeline(child.stderr, createWriteStream(path.join(out, 'stderr.log')))]);
    const ended = await exit;
    await writeFile(path.join(out, 'process.json'), `${JSON.stringify(ended, null, 2)}\n`);
    if (ended.code !== 0) throw new Error('CALIBRATION_PROCESS_FAILED; raw attempt preserved.');
    const measured = JSON.parse(await readFile(resultPath, 'utf8'));
    if (measured.schema !== 'financial-rate-calibration-v1') throw new Error('CALIBRATION_ARTIFACT_REQUIRED');
    if (typeof measured.realRecordEvidencePath !== 'string') throw new Error('RAW_ENCODED_RECORD_EVIDENCE_REQUIRED');
    const recordPath = path.resolve(out, measured.realRecordEvidencePath);
    if (!recordPath.startsWith(`${path.resolve(out)}${path.sep}`)) throw new Error('ENCODED_EVIDENCE_MUST_BE_OWNED_BY_RUN');
    const encoded = await readFile(recordPath);
    if (encoded.byteLength !== measured.encodedBytes || createHash('sha256').update(encoded).digest('hex') !== measured.realRecordEvidenceSha256)
      throw new Error('ENCODED_RECORD_BYTE_OR_HASH_MISMATCH');
    return measured;
  } finally { await lock.close(); await unlink(lockPath); }
}

async function main(args) {
  const [mode, ...rest] = args;
  if (mode === 'plan') return PLAN;
  if (mode === 'freeze') return preparePolicy(JSON.parse(await readFile(rest[0], 'utf8')));
  if (mode === 'assess') return assessMeasurement(JSON.parse(await readFile(rest[0], 'utf8')), JSON.parse(await readFile(rest[1], 'utf8')));
  if (mode === 'calibrate') return calibrateAdapter(...rest);
  if (mode === 'run') return runAdapter(...rest);
  throw new Error('Usage: rate-proof.mjs plan | freeze REQUEST.json | assess POLICY.json MEASUREMENT.json | calibrate REQUEST.json ADAPTER.json UNIQUE_OUT --authorize-calibration | run POLICY.json ADAPTER.json UNIQUE_OUT --authorize-load');
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(result => {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (result.status === 'BLOCKED' || (result.result && result.result !== 'PASS_DIAGNOSTIC')) process.exitCode = 1;
  }).catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
