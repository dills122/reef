import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir, open, unlink, realpath } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { tmpdir } from 'node:os';
import { EMPIRICAL_DISK_PROFILE, EMPIRICAL_8G_DISK_PROFILE } from './proof-supervisor.mjs';
import { superviseRateAdapter, policyDigest } from './rate-supervision.mjs';
import { freezeBootstrap, verifyBootstrapEvidence, validateBootstrapResult } from './bootstrap-calibration.mjs';
import { readBoundedJSON } from './physical-adapter.mjs';

// All capacity entry points share one host lock; preserve macOS session lock location.
export function capacityLockPath(platform = process.platform, temporaryDirectory = tmpdir()) {
  return path.join(platform === 'darwin' ? '/private/tmp' : temporaryDirectory, 'reef-financial-rate-capacity.lock');
}

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

// Frozen diagnostic launcher; request metadata cannot increase this cap.
export const HEAP_LAUNCHER = Object.freeze({
  java: '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java',
  flags: ['-Xms128m', '-Xmx768m'], maxHeapBytes: 768 * 1024 ** 2,
  mainClass: 'com.reef.platform.calcify.financial.FinancialRateProbe',
});
// Explicit finite empirical resource permission; never conservative admission.
export const DIAGNOSTIC_LAUNCHER = Object.freeze({ ...HEAP_LAUNCHER,
  flags: ['-Xms128m', '-Xmx4g'], maxHeapBytes: 4 * 1024 ** 3 });
export const DIAGNOSTIC_8G_LAUNCHER = Object.freeze({ ...HEAP_LAUNCHER,
  flags: ['-Xms128m', '-Xmx8g'], maxHeapBytes: 8 * 1024 ** 3 });
export function diagnosticLauncher(profile) {
  if (profile === EMPIRICAL_8G_DISK_PROFILE.name) return DIAGNOSTIC_8G_LAUNCHER;
  if (profile === undefined || profile === EMPIRICAL_DISK_PROFILE.name) return DIAGNOSTIC_LAUNCHER;
  throw Error('EMPIRICAL_RESOURCE_PROFILE_REQUIRED');
}
export function freezeDiagnostic(input) {
  const r = structuredClone(input), c = r.capability;
  const launcher = diagnosticLauncher(r.resourceProfile);
  if (r.heap !== undefined || r.heapConservativeBound === true || r.capacityQualification === true
    || r.estimatedHeapBytes != null || (r.arm && !isDeepStrictEqual(r.arm, { rate: 2500, seconds: 60, state: 'fresh' })))
    throw Error('EMPIRICAL_DIAGNOSTIC_SCOPE_REQUIRED');
  if (!revision(r.sourceHead) || !hash(r.fixtureSha256) || typeof r.sourceRoot !== 'string' || !path.isAbsolute(r.sourceRoot)
    || c?.schema !== 'financial-heap-capability-v1' || c.sourceHead !== r.sourceHead || c.fixtureSha256 !== r.fixtureSha256
    || !['configSha256', 'buildSha256', 'classpathSha256'].every(k => hash(c[k]))
    || c.maxHeapBytes !== launcher.maxHeapBytes || !nonnegative(c.usedHeapBytes)
    || c.usedHeapBytes >= Math.floor(c.maxHeapBytes * 4 / 5)
    || !isDeepStrictEqual(c.vmArguments, launcher.flags)
    || typeof c.javaVersion !== 'string' || !/^21(?:[.+-]|$)/.test(c.javaVersion) || typeof c.javaVendor !== 'string' || !c.javaVendor)
    throw Error('EMPIRICAL_PINNED_CAPABILITY_REQUIRED');
  if (r.correctness?.result !== 'PASS' || !hash(r.correctness.evidenceSha256)
    || !['kernelSha256', 'managedAdapterSha256'].every(k => hash(r.runtimeProofHashes?.[k]) && r.correctness[k] === r.runtimeProofHashes[k])
    || !['fixtureEvidencePath', 'capabilityEvidencePath', 'correctnessEvidencePath'].every(k => typeof r[k] === 'string' && r[k])
    || !hash(r.capabilityEvidenceSha256)) throw Error('EMPIRICAL_CORRECTNESS_BINDING_REQUIRED');
  const frozen = { ...r, schema: 'financial-empirical-timed-policy-v1', status: 'FROZEN_EMPIRICAL_DIAGNOSTIC',
    purpose: 'EMPIRICAL_TIMED_DIAGNOSTIC', arm: { rate: 2500, seconds: 60, state: 'fresh' }, expectedTimedTrades: 150000,
    ackJournalMaxBytes: 2 * 1024 ** 3, capacityQualification: false, heapConservativeBound: false, estimatedHeapBytes: null,
    openingResources: { cashNanos: '1500000000000', shares: '150000' }, aged: { identities: 0, pendingItems: 0 },
    diskBudgetBytes: r.resourceProfile ? EMPIRICAL_DISK_PROFILE.hardAllocatedBytes : PLAN.diskBudgetBytes, diskHeadroomBytes: PLAN.diskHeadroomBytes };
  delete frozen.policySha256;
  return { ...frozen, policySha256: policyDigest(frozen) };
}
export async function verifyDiagnosticEvidence(policy, policyPath, configPath, actual = policy.capability) {
  const frozen = freezeDiagnostic(policy), launcher = diagnosticLauncher(policy.resourceProfile);
  if (!isDeepStrictEqual(frozen, policy)) throw Error('EMPIRICAL_FROZEN_POLICY_CHANGED');
  const root = await realpath(path.dirname(path.resolve(policyPath)));
  const owned = async name => { const file = await realpath(path.resolve(root, name));
    if (!file.startsWith(root + path.sep)) throw Error('EMPIRICAL_EVIDENCE_OUTSIDE_POLICY'); return file; };
  for (const [name, digest, object] of [
    [policy.capabilityEvidencePath, policy.capabilityEvidenceSha256, policy.capability],
    [policy.correctnessEvidencePath, policy.correctness.evidenceSha256,
      Object.fromEntries(Object.entries(policy.correctness).filter(([k]) => k !== 'evidenceSha256'))]]) {
    const bytes = await readFile(await owned(name));
    if (bytes.length > 1024 * 1024 || createHash('sha256').update(bytes).digest('hex') !== digest
      || !isDeepStrictEqual(JSON.parse(bytes), object)) throw Error('EMPIRICAL_RAW_EVIDENCE_MISMATCH');
  }
  if (createHash('sha256').update(await readFile(await owned(policy.fixtureEvidencePath))).digest('hex') !== policy.fixtureSha256
    || createHash('sha256').update(await readFile(configPath)).digest('hex') !== policy.capability.configSha256)
    throw Error('EMPIRICAL_FIXTURE_CONFIG_MISMATCH');
  const immutable = c => Object.fromEntries(Object.entries(c).filter(([k]) => k !== 'usedHeapBytes'));
  if (!isDeepStrictEqual(immutable(actual), immutable(policy.capability)) || !nonnegative(actual.usedHeapBytes)
    || actual.usedHeapBytes >= Math.floor(launcher.maxHeapBytes * 4 / 5)) throw Error('EMPIRICAL_ACTUAL_CAPABILITY_DRIFT');
  for (const [name, key] of [['FinancialKernel.kt', 'kernelSha256'], ['FinancialBrokerProbe.kt', 'managedAdapterSha256']]) {
    const file = path.join(policy.sourceRoot, 'services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial', name);
    if (createHash('sha256').update(await readFile(file)).digest('hex') !== policy.runtimeProofHashes[key]) throw Error('EMPIRICAL_RUNTIME_PROOF_SOURCE_DRIFT');
  }
  return frozen;
}
export function assessDiagnostic(policy, measurement) {
  const policyValid = isDeepStrictEqual(freezeDiagnostic(policy), policy), launcher = diagnosticLauncher(policy.resourceProfile);
  const conservativeShape = { ...policy, status: 'FROZEN', heap: { capability: policy.capability },
    heapEstimate: { estimatedHeapBytes: null } };
  conservativeShape.policySha256 = sha(Object.fromEntries(Object.entries(conservativeShape).filter(([k]) => k !== 'policySha256')));
  const observation = measurement?.heapObservation;
  const result = assessMeasurement(conservativeShape, { ...measurement, policySha256: conservativeShape.policySha256 });
  result.gaps = result.gaps.filter(g => g !== 'HEAP_LIFECYCLE_OBSERVATION_REQUIRED');
  result.failures = result.failures.filter(f => f !== 'HEAP_LIFECYCLE_PROTECTION_FAILED'
    && !(f === 'DISK_BUDGET_EXCEEDED' && [EMPIRICAL_DISK_PROFILE.name, EMPIRICAL_8G_DISK_PROFILE.name].includes(policy.resourceProfile)));
  if (nonnegative(measurement?.resources?.diskPeakBytes) && measurement.resources.diskPeakBytes > policy.diskBudgetBytes)
    result.failures.push('EMPIRICAL_DISK_BUDGET_EXCEEDED');
  const supportedSchema = measurement?.schema === 'financial-rate-measurement-v1';
  const validEnvelope = policyValid && supportedSchema && measurement.policySha256 === policy.policySha256
    && measurement.purpose === 'EMPIRICAL_TIMED_DIAGNOSTIC' && measurement.heapConservativeBound === false
    && measurement.capacityQualification === false && measurement.sourceHead === policy.sourceHead
    && measurement.fixtureSha256 === policy.fixtureSha256 && hash(measurement.workloadSha256);
  if (!supportedSchema) result.failures.push('EMPIRICAL_MEASUREMENT_SCHEMA_REQUIRED');
  if (!validEnvelope) result.failures.push('EMPIRICAL_SCOPE_OR_PROVENANCE_MISMATCH');
  if (observation?.schema !== 'financial-empirical-heap-observation-v1' || observation.guardOutcome !== 'PASS_SAMPLED'
    || observation.actualMaxHeapBytes !== launcher.maxHeapBytes
    || observation.admissionLimitBytes !== Math.floor(launcher.maxHeapBytes * 4 / 5)
    || !nonnegative(observation.heapPeakBytes) || observation.heapPeakBytes >= observation.admissionLimitBytes
    || observation.estimatedHeapBytes !== null || observation.heapConservativeBound !== false)
    result.failures.push('EMPIRICAL_HEAP_PROTECTION_FAILED');
  if (measurement?.restore?.records !== 300001 || measurement?.ackJournal?.publishedMembers !== 300000
    || measurement.ackJournal.maxAggregateBytes !== policy.ackJournalMaxBytes) result.failures.push('EMPIRICAL_COMPLETE_ACTION_HISTORY_REQUIRED');
  result.result = result.failures.length ? 'FAIL_DIAGNOSTIC' : 'LIMITED';
  const deadline = measurement?.deadline;
  const validDeadline = validEnvelope && ['offered', 'admitted', 'decided', 'settled', 'pending'].every(k => nonnegative(deadline?.[k]))
    && deadline.offered <= policy.expectedTimedTrades && deadline.offered >= deadline.admitted && deadline.offered >= deadline.decided
    && deadline.admitted >= deadline.settled && deadline.decided === deadline.settled + deadline.pending;
  result.usefulRateOutcome = !validDeadline ? 'UNKNOWN'
    : deadline.decided * 1000 / (policy.arm.seconds * 1000) >= policy.arm.rate
      && deadline.settled * 1000 / (policy.arm.seconds * 1000) >= policy.arm.rate ? 'TARGET_MET' : 'TARGET_MISSED';
  return { ...result, policySha256: policy.policySha256, heapConservativeBound: false,
    gaps: [...result.gaps, 'Empirical resource budget; no conservative heap upper bound or capacity qualification'] };
}
export async function diagnosticAdapter(policyPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-load') throw Error('Explicit --authorize-load required.');
  const policy = await readBoundedJSON(policyPath, 1024 * 1024), adapter = await readBoundedJSON(adapterPath, 65536);
  const pinned = pinnedHeapAdapter(adapter, 'diagnostic-run', diagnosticLauncher(policy.resourceProfile));
  await verifyDiagnosticEvidence(policy, policyPath, pinned[8]);
  const lockPath = capacityLockPath(), lock = await open(lockPath, 'wx');
  try {
    await lock.writeFile(`${JSON.stringify({ pid: process.pid, policyPath, out, mode: 'diagnostic-run' })}\n`);
    await mkdir(out);
    await writeFile(path.join(out, 'policy.json'), `${JSON.stringify(policy, null, 2)}\n`);
    const attempt = await captureHeapCapability(adapter.command, [...pinned.slice(0, 5), 'heap-capability', policy.sourceHead,
      path.resolve(path.dirname(policyPath), policy.fixtureEvidencePath), pinned[8]]);
    await writeFile(path.join(out, 'heap-capability-attempt.json'), `${JSON.stringify(attempt, null, 2)}\n`);
    if (attempt.code !== 0 || attempt.timedOut || attempt.outputOverflow) throw Error('EMPIRICAL_CAPABILITY_PROCESS_FAILED');
    await verifyDiagnosticEvidence(policy, policyPath, pinned[8], JSON.parse(attempt.stdout));
    const measurement = path.resolve(out, 'measurement.json');
    const args = [...pinned, '--financial-rate-policy', path.resolve(policyPath), '--financial-rate-measurement', measurement];
    await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
    const ended = await superviseRateAdapter(adapter, adapterPath, args, out, heapEnvironment());
    await writeFile(path.join(out, 'process.json'), `${JSON.stringify(ended, null, 2)}\n`);
    const result = assessDiagnostic(policy, await readBoundedJSON(measurement, 8 * 1024 * 1024));
    await writeFile(path.join(out, 'assessment.json'), `${JSON.stringify(result, null, 2)}\n`);
    return result;
  } finally { await lock.close(); await unlink(lockPath); }
}

const heapFields = ['identityHeapBytesUpper', 'pendingHeapBytesUpper', 'baselineHeapBytesUpper',
  'transientHeapReserveBytes', 'replayHeapReserveBytes', 'supportedIdentities', 'supportedPendingItems'];
const heapIdentities = ['sourceHead', 'fixtureSha256', 'configSha256', 'buildSha256', 'classpathSha256'];

/** Validation-only fixtures may exercise arithmetic; executable boundary rejects them. */
export function estimateHeap(heap, timedTrades, agedIdentities = 0, pendingItems = 0, actual = heap?.capability) {
  const b = heap?.bounds, c = heap?.capability, r = heap?.review;
  if (heap?.schema !== 'financial-heap-admission-v1' || heap.authorizedMaxHeapBytes !== HEAP_LAUNCHER.maxHeapBytes
    || b?.schema !== 'financial-heap-bounds-v1' || c?.schema !== 'financial-heap-capability-v1'
    || !heapFields.every(k => positive(b?.[k])) || !positive(c?.maxHeapBytes) || !nonnegative(c?.usedHeapBytes)
    || !positive(actual?.maxHeapBytes) || !nonnegative(actual?.usedHeapBytes)
    || ![timedTrades, agedIdentities, pendingItems].every(nonnegative)
    || !heapIdentities.every(k => (k === 'sourceHead' ? revision(b?.[k]) : hash(b?.[k])) && b[k] === c[k] && b[k] === actual[k])
    || !Array.isArray(c.vmArguments) || JSON.stringify(c.vmArguments) !== JSON.stringify(HEAP_LAUNCHER.flags)
    || JSON.stringify(actual.vmArguments) !== JSON.stringify(c.vmArguments)
    || typeof c.javaVersion !== 'string' || !c.javaVersion || typeof c.javaVendor !== 'string' || !c.javaVendor
    || actual.javaVersion !== c.javaVersion || actual.javaVendor !== c.javaVendor
    || typeof b.derivation !== 'string' || !b.derivation.trim() || typeof b.scope !== 'string' || !b.scope.trim()
    || typeof b.validationOnly !== 'boolean'
    || r?.schema !== 'financial-heap-bounds-review-v1' || r.validationOnly !== b.validationOnly
    || r.verdict !== (b.validationOnly ? 'VALIDATION_ONLY' : 'READY_CONSERVATIVE_BOUND')
    || r.boundsEvidenceSha256 !== heap.boundsEvidenceSha256
    || !heapIdentities.every(k => r[k] === b[k])
    || r.supportedIdentities !== b.supportedIdentities || r.supportedPendingItems !== b.supportedPendingItems
    || r.derivation !== b.derivation || r.scope !== b.scope
    || !hash(heap.reviewEvidenceSha256) || typeof heap.reviewEvidencePath !== 'string' || !heap.reviewEvidencePath
    || !hash(heap.boundsEvidenceSha256) || !hash(heap.capabilityEvidenceSha256)
    || typeof heap.boundsEvidencePath !== 'string' || !heap.boundsEvidencePath
    || typeof heap.capabilityEvidencePath !== 'string' || !heap.capabilityEvidencePath
    || typeof heap.fixtureEvidencePath !== 'string' || !heap.fixtureEvidencePath)
    throw Error('HEAP_CONSERVATIVE_EVIDENCE_REQUIRED');
  if (c.maxHeapBytes > heap.authorizedMaxHeapBytes || actual.maxHeapBytes > heap.authorizedMaxHeapBytes)
    throw Error('HEAP_UNAUTHORIZED_MAX');
  const identities = BigInt(timedTrades) + BigInt(agedIdentities), pending = BigInt(pendingItems);
  const limit = BigInt(Math.min(heap.authorizedMaxHeapBytes, c.maxHeapBytes, actual.maxHeapBytes)) * 4n / 5n;
  const estimated = BigInt(b.baselineHeapBytesUpper) + identities * BigInt(b.identityHeapBytesUpper)
    + pending * BigInt(b.pendingHeapBytesUpper) + BigInt(b.transientHeapReserveBytes) + BigInt(b.replayHeapReserveBytes);
  if (identities > BigInt(b.supportedIdentities) || pending > BigInt(b.supportedPendingItems)) throw Error('HEAP_UNSUPPORTED_COUNTS');
  if (estimated > BigInt(Number.MAX_SAFE_INTEGER)) throw Error('HEAP_ESTIMATE_OVERFLOW');
  if (c.usedHeapBytes > b.baselineHeapBytesUpper || actual.usedHeapBytes > b.baselineHeapBytesUpper) throw Error('HEAP_BASELINE_BOUND_EXCEEDED');
  if (estimated >= limit || BigInt(actual.usedHeapBytes) >= limit || BigInt(c.usedHeapBytes) >= limit) throw Error('HEAP_ADMISSION_LIMIT');
  return { estimatedHeapBytes: Number(estimated), admissionLimitBytes: Number(limit), identities: Number(identities), pendingItems,
    threshold: 'used >= floor(effective max * 4 / 5)', sampled: true, capacityQualification: false };
}

export async function verifyHeapEvidence(policy, policyPath, actual = policy.heap?.capability) {
  const heap = policy.heap;
  if (policy.sourceHead !== heap?.bounds?.sourceHead || policy.fixtureSha256 !== heap?.bounds?.fixtureSha256) throw Error('HEAP_CANDIDATE_FIXTURE_MISMATCH');
  estimateHeap(heap, policy.expectedTimedTrades, policy.arm?.state === 'aged' ? policy.aged.identities : 0,
    policy.arm?.state === 'aged' ? policy.aged.pendingItems : 0, actual);
  if (heap.bounds.validationOnly) throw Error('HEAP_VALIDATION_ONLY_EVIDENCE');
  const root = await realpath(path.dirname(path.resolve(policyPath)));
  const owned = async name => {
    const file = path.resolve(root, name);
    if (!file.startsWith(`${root}${path.sep}`)) throw Error('HEAP_EVIDENCE_MUST_BE_OWNED_BY_POLICY');
    const resolved = await realpath(file);
    if (!resolved.startsWith(`${root}${path.sep}`)) throw Error('HEAP_EVIDENCE_MUST_BE_OWNED_BY_POLICY');
    return resolved;
  };
  for (const [kind, object] of [['bounds', heap.bounds], ['capability', heap.capability], ['review', heap.review]]) {
    const file = await owned(heap[`${kind}EvidencePath`]);
    const bytes = await readFile(file);
    if (createHash('sha256').update(bytes).digest('hex') !== heap[`${kind}EvidenceSha256`]) throw Error('HEAP_RAW_EVIDENCE_HASH_MISMATCH');
    // Compare semantic JSON, separately from raw byte provenance.
    if (!isDeepStrictEqual(JSON.parse(bytes), object)) throw Error('HEAP_RAW_EVIDENCE_IDENTITY_MISMATCH');
  }
  const fixture = await owned(heap.fixtureEvidencePath);
  if (createHash('sha256').update(await readFile(fixture)).digest('hex') !== heap.bounds.fixtureSha256) throw Error('HEAP_FIXTURE_IDENTITY_MISMATCH');
  return heap;
}

function pinnedHeapAdapter(adapter, mode = 'run', launcher = HEAP_LAUNCHER) {
  if (adapter.command !== launcher.java || !Array.isArray(adapter.args)) throw Error('HEAP_PINNED_LAUNCHER_REQUIRED');
  const a = adapter.args;
  if (a.length !== 9 || a[0] !== launcher.flags[0] || a[1] !== launcher.flags[1]
    || a[2] !== '-cp' || typeof a[3] !== 'string' || !a[3] || a[4] !== HEAP_LAUNCHER.mainClass
    || a[5] !== mode || typeof a[8] !== 'string') throw Error('HEAP_PINNED_LAUNCHER_REQUIRED');
  return a;
}
const heapEnvironment = () => {
  const env = { ...process.env };
  for (const key of ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH']) delete env[key];
  return env;
};

/** Broker-free capability subprocess only; main load supervision remains separate E4 work. */
export async function captureHeapCapability(command, args, { timeoutMs = 15000, killAfterMs = 2000, spawnProcess = spawn } = {}) {
  const child = spawnProcess(command, args, { env: heapEnvironment(), stdio: ['ignore', 'pipe', 'pipe'] });
  let stdout = '', stderr = '', timedOut = false, outputOverflow = false, forceTimer;
  const stop = () => {
    child.kill('SIGTERM');
    forceTimer ??= setTimeout(() => child.kill('SIGKILL'), killAfterMs);
  };
  child.stdout.on('data', chunk => { stdout += chunk.toString().slice(0, 65536 - stdout.length); if (stdout.length >= 65536) { outputOverflow = true; stop(); } });
  child.stderr.on('data', chunk => { stderr += chunk.toString().slice(0, 65536 - stderr.length); if (stderr.length >= 65536) { outputOverflow = true; stop(); } });
  const timer = setTimeout(() => { timedOut = true; stop(); }, timeoutMs);
  try {
    const ended = await new Promise(resolve => { child.once('error', error => resolve({ error: error.message })); child.once('close', (code, signal) => resolve({ code, signal })); });
    return { command, args, ...ended, stdout, stderr, timedOut, outputOverflow };
  } finally { clearTimeout(timer); clearTimeout(forceTimer); }
}

function physicalTradeBudget(calibration) {
  if (!positive(calibration?.physicalBytes) || !positive(calibration?.sampleTrades)) throw Error('PHYSICAL_TRADE_BUDGET_INVALID');
  const measured = Math.ceil(calibration.physicalBytes / calibration.sampleTrades) * 2;
  const override = calibration.maxPhysicalBytesPerTrade;
  if (!positive(measured) || (override !== undefined && (!positive(override) || override < measured)))
    throw Error('PHYSICAL_TRADE_BUDGET_INVALID');
  return override ?? measured;
}

export function estimateAged(calibration, preflight, arm = { rate: 10000, seconds: 300 }) {
  const perTrade = physicalTradeBudget(calibration);
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
  if (c) {
    try { physicalTradeBudget(c); } catch { gaps.push('PHYSICAL_TRADE_BUDGET_INVALID'); }
  }
  if (gaps.length) return { schema: 'financial-e4-policy-v1', status: 'BLOCKED', plan: PLAN, gaps };
  const aged = estimateAged(c, p, arm);
  if (aged.fixedBytes > PLAN.diskBudgetBytes) gaps.push('Timed+pending cohort alone exceeds10GiB; no arm permitted.');
  if (p.guestFreeBytes < aged.estimatedBytes + PLAN.diskHeadroomBytes) gaps.push('GUEST_SPACE_INSUFFICIENT');
  if (arm.seconds === 300 && (!hash(arm.workloadSha256) || request.ladderResult !== 'PASS_DIAGNOSTIC')) gaps.push('STABLE_LADDER_AND_REPEAT_WORKLOAD_REQUIRED');
  if (gaps.length) return { schema: 'financial-e4-policy-v1', status: 'BLOCKED', plan: PLAN, aged, gaps };
  let heapEstimate;
  try {
    if (request.heap?.bounds?.sourceHead !== c.sourceHead || request.heap?.bounds?.fixtureSha256 !== c.fixtureSha256) throw Error('HEAP_CANDIDATE_FIXTURE_MISMATCH');
    heapEstimate = estimateHeap(request.heap, arm.rate * arm.seconds, arm.state === 'aged' ? aged.identities : 0,
      arm.state === 'aged' ? aged.pendingItems : 0);
  } catch (error) { gaps.push(error.message); }
  if (gaps.length) return { schema: 'financial-e4-policy-v1', status: 'BLOCKED', plan: PLAN, aged, gaps };
  const trades = BigInt(arm.rate * arm.seconds + (arm.state === 'aged' ? PLAN.pendingItems + aged.identities : 0));
  const cash = trades * BigInt(PLAN.trade.priceNanos);
  if (cash > (1n << 63n) - 1n) throw new Error('PREFUND_INT64_OVERFLOW');
  const policy = { schema: 'financial-e4-policy-v1', status: 'FROZEN', plan: PLAN,
    arm, sourceHead: c.sourceHead, fixtureSha256: c.fixtureSha256, calibrationSha256: sha(c),
    e3EvidenceSha256: e3.evidenceSha256, accessPatternEvidenceSha256: p.accessPatternEvidenceSha256,
    aged, heap: request.heap, heapEstimate, openingResources: { cashNanos: cash.toString(), shares: trades.toString() },
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
  if (!m.heapObservation || m.heapObservation.schema !== 'financial-heap-observation-v1') gaps.push('HEAP_LIFECYCLE_OBSERVATION_REQUIRED');
  else if (m.heapObservation.guardOutcome !== 'PASS_SAMPLED' || !positive(m.heapObservation.actualMaxHeapBytes)
    || m.heapObservation.actualMaxHeapBytes > HEAP_LAUNCHER.maxHeapBytes || !nonnegative(m.heapObservation.heapPeakBytes)
    || m.heapObservation.heapPeakBytes >= m.heapObservation.admissionLimitBytes
    || m.heapObservation.admissionLimitBytes !== Math.floor(Math.min(HEAP_LAUNCHER.maxHeapBytes, policy.heap?.capability?.maxHeapBytes, m.heapObservation.actualMaxHeapBytes) * 4 / 5)
    || m.heapObservation.estimatedHeapBytes !== policy.heapEstimate?.estimatedHeapBytes) failures.push('HEAP_LIFECYCLE_PROTECTION_FAILED');
  if (m.units !== 'unique timed executions; preflight/aged rows excluded') failures.push('TRADE_UNIT_MISMATCH');
  const durationMs = policy.arm?.seconds * 1000;
  const stages = ['offered', 'admitted', 'decided', 'settled', 'pending'];
  for (const cut of ['deadline', 'final']) {
    const c = m[cut];
    if (!c || stages.some(k => !nonnegative(c[k]))) { gaps.push(`${cut.toUpperCase()}_STAGE_COUNTS_REQUIRED`); continue; }
    // CAPTURE can decide before its paired SETTLE is durably admitted.
    if (c.offered < c.admitted || c.offered < c.decided || c.admitted < c.settled
      || c.decided < c.settled || c.decided !== c.settled + c.pending) failures.push(`${cut.toUpperCase()}_COUNT_PARITY`);
  }
  // Cumulative execution counts cannot shrink across cuts; pending is a gauge.
  for (const stage of ['offered', 'admitted', 'decided', 'settled']) {
    if (nonnegative(m.deadline?.[stage]) && nonnegative(m.final?.[stage]) && m.deadline[stage] > m.final[stage])
      failures.push(`DEADLINE_${stage.toUpperCase()}_EXCEEDS_FINAL`);
  }
  if (nonnegative(m.deadline?.offered) && m.deadline.offered > policy.expectedTimedTrades)
    failures.push('DEADLINE_OFFERED_EXCEEDS_EXPECTED');
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
    rates: Object.fromEntries(stages.map(stage => [`${stage}PerSecond`, nonnegative(m.deadline?.[stage]) && positive(durationMs) ? m.deadline[stage] * 1000 / durationMs : null])),
    rateBoundary: 'Counts visible at fixed deadline / scheduled duration; conservative covering rates, not individual latency.',
    cpuEquivalentCores: finiteNonnegative(m.resources?.processCpuMs) && positive(durationMs) ? m.resources.processCpuMs / durationMs : null,
    touchedKeysPerTrade: nonnegative(m.resources?.touchedKeys) && positive(m.final?.decided) ? m.resources.touchedKeys / m.final.decided : null,
    capacityQualification: false, sourceHead: policy.sourceHead, policySha256: policy.policySha256 };
}

export function selectRepeat(ladder, workloadSha256) {
  if (!hash(workloadSha256)) throw new Error('WORKLOAD_HASH_REQUIRED');
  const pass = ladder.filter(r => [2500, 5000, 10000].includes(r.rate) && r.result === 'PASS_DIAGNOSTIC').sort((a, b) => b.rate - a.rate)[0];
  return pass ? ['fresh', 'aged'].map(state => ({ rate: pass.rate, seconds: 300, state, workloadSha256 })) : [];
}

async function verifyAdapterCapability(policy, policyPath, adapter, pinned, out) {
  const capabilityArgs = [...pinned.slice(0, 5), 'heap-capability', policy.sourceHead, path.resolve(path.dirname(policyPath), policy.heap.fixtureEvidencePath), pinned[8]];
  const capabilityAttempt = await captureHeapCapability(adapter.command, capabilityArgs);
  await writeFile(path.join(out, 'heap-capability-attempt.json'), `${JSON.stringify(capabilityAttempt, null, 2)}\n`);
  if (capabilityAttempt.code !== 0 || capabilityAttempt.timedOut || capabilityAttempt.outputOverflow) throw Error('HEAP_CAPABILITY_PROCESS_FAILED');
  const observedCapability = JSON.parse(capabilityAttempt.stdout);
  await verifyHeapEvidence(policy, policyPath, observedCapability);
  if (createHash('sha256').update(await readFile(pinned[8])).digest('hex') !== policy.heap.bounds.configSha256) throw Error('HEAP_CONFIG_IDENTITY_MISMATCH');
  return observedCapability;
}

// Adapter contract: real executable receives --financial-rate-policy PATH and
// --financial-rate-measurement PATH; owns producer/observer, emits measured schema.
// E3 init/seed/worker/observe/reconstruct does not yet implement this rate contract.
async function runAdapterUnlocked(policyPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-load') throw new Error('Explicit --authorize-load required after E3 correctness.');
  const policy = JSON.parse(await readFile(policyPath, 'utf8'));
  if (policy.status !== 'FROZEN' || policy.policySha256 !== sha(Object.fromEntries(Object.entries(policy).filter(([k]) => k !== 'policySha256')))) throw new Error('FROZEN_POLICY_REQUIRED');
  await verifyHeapEvidence(policy, policyPath);
  const adapter = JSON.parse(await readFile(adapterPath, 'utf8'));
  const pinned = pinnedHeapAdapter(adapter);
  if (typeof adapter.command !== 'string' || !Array.isArray(adapter.args) || adapter.args.some(x => typeof x !== 'string')) throw new Error('ADAPTER_COMMAND_REQUIRED');
  await mkdir(out); // Unique run directory: refuse overwriting previous attempts.
  const measurementPath = path.resolve(out, 'measurement.json');
  await writeFile(path.join(out, 'policy.json'), `${JSON.stringify(policy, null, 2)}\n`);
  await verifyAdapterCapability(policy, policyPath, adapter, pinned, out);
  const args = [...adapter.args, '--financial-rate-policy', path.resolve(policyPath), '--financial-rate-measurement', measurementPath];
  await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
  const ended = await superviseRateAdapter(adapter, adapterPath, args, out, heapEnvironment());
  await writeFile(path.join(out, 'process.json'), `${JSON.stringify(ended, null, 2)}\n`);
  const result = assessMeasurement(policy, JSON.parse(await readFile(measurementPath, 'utf8')));
  await writeFile(path.join(out, 'assessment.json'), `${JSON.stringify(result, null, 2)}\n`);
  return result;
}

export async function runAdapter(policyPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-load') throw new Error('Explicit --authorize-load required after E3 correctness.');
  const lockPath = capacityLockPath();
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
  if (!request.heap) throw Error('HEAP_CONSERVATIVE_EVIDENCE_REQUIRED');
  const calibrationPolicy = { heap: request.heap, sourceHead: request.sourceHead, fixtureSha256: request.fixtureSha256, expectedTimedTrades: request.sampleTrades, arm: { state: 'aged' }, aged: { identities: 0, pendingItems: request.pendingSample } };
  await verifyHeapEvidence(calibrationPolicy, requestPath);
  const adapter = JSON.parse(await readFile(adapterPath, 'utf8'));
  const pinned = pinnedHeapAdapter(adapter, 'calibrate');
  if (typeof adapter.command !== 'string' || !Array.isArray(adapter.args) || adapter.args.some(x => typeof x !== 'string')) throw new Error('ADAPTER_COMMAND_REQUIRED');
  const lockPath = capacityLockPath();
  const lock = await open(lockPath, 'wx');
  try {
    await mkdir(out);
    await verifyAdapterCapability(calibrationPolicy, requestPath, adapter, pinned, out);
    const resultPath = path.resolve(out, 'calibration.json');
    const args = [...adapter.args, '--financial-rate-calibration-request', path.resolve(requestPath), '--financial-rate-calibration', resultPath];
    await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
    const ended = await superviseRateAdapter(adapter, adapterPath, args, out, heapEnvironment());
    await writeFile(path.join(out, 'process.json'), `${JSON.stringify(ended, null, 2)}\n`);
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

/** Explicit fixed bootstrap; observations cannot become conservative heap bounds. */
export async function bootstrapAdapter(requestPath, adapterPath, out, authorize) {
  if (authorize !== '--authorize-calibration-bootstrap') throw Error('Explicit --authorize-calibration-bootstrap required.');
  const request = await readBoundedJSON(requestPath, 1024 * 1024);
  const adapter = await readBoundedJSON(adapterPath, 65536), pinned = pinnedHeapAdapter(adapter, 'bootstrap-calibrate');
  await verifyBootstrapEvidence(request, requestPath, pinned[8]);
  const lockPath = capacityLockPath(), lock = await open(lockPath, 'wx');
  try {
    await lock.writeFile(`${JSON.stringify({ pid: process.pid, requestPath, out, mode: 'bootstrap-calibrate' })}\n`);
    await mkdir(out);
    const fixture = path.resolve(path.dirname(requestPath), request.fixtureEvidencePath);
    const capabilityArgs = [...pinned.slice(0, 5), 'heap-capability', request.sourceHead, fixture, pinned[8]];
    const attempt = await captureHeapCapability(adapter.command, capabilityArgs);
    await writeFile(path.join(out, 'heap-capability-attempt.json'), `${JSON.stringify(attempt, null, 2)}\n`);
    if (attempt.code !== 0 || attempt.timedOut || attempt.outputOverflow) throw Error('BOOTSTRAP_CAPABILITY_PROCESS_FAILED');
    await verifyBootstrapEvidence(request, requestPath, pinned[8], JSON.parse(attempt.stdout));
    const artifact = path.resolve(out, 'bootstrap.json');
    const args = [...adapter.args, '--financial-calibration-bootstrap-request', path.resolve(requestPath), '--financial-calibration-bootstrap', artifact];
    await writeFile(path.join(out, 'command.json'), `${JSON.stringify({ command: adapter.command, args }, null, 2)}\n`);
    const ended = await superviseRateAdapter(adapter, adapterPath, args, out, heapEnvironment());
    await writeFile(path.join(out, 'process.json'), `${JSON.stringify(ended, null, 2)}\n`);
    const measured = await readBoundedJSON(artifact, 8 * 1024 * 1024);
    const assessment = await validateBootstrapResult(request, measured, out);
    await writeFile(path.join(out, 'assessment.json'), `${JSON.stringify(assessment, null, 2)}\n`, { flag: 'wx' });
    return measured;
  } finally { await lock.close(); await unlink(lockPath); }
}

async function main(args) {
  const [mode, ...rest] = args;
  if (mode === 'plan') return PLAN;
  if (mode === 'freeze-diagnostic') return freezeDiagnostic(JSON.parse(await readFile(rest[0], 'utf8')));
  if (mode === 'diagnostic-run') return diagnosticAdapter(...rest);
  if (mode === 'assess-diagnostic') return assessDiagnostic(JSON.parse(await readFile(rest[0], 'utf8')), JSON.parse(await readFile(rest[1], 'utf8')));
  if (mode === 'freeze') return preparePolicy(JSON.parse(await readFile(rest[0], 'utf8')));
  if (mode === 'assess') return assessMeasurement(JSON.parse(await readFile(rest[0], 'utf8')), JSON.parse(await readFile(rest[1], 'utf8')));
  if (mode === 'calibrate') return calibrateAdapter(...rest);
  if (mode === 'freeze-bootstrap') return freezeBootstrap(JSON.parse(await readFile(rest[0], 'utf8')));
  if (mode === 'bootstrap-calibrate') return bootstrapAdapter(...rest);
  if (mode === 'run') return runAdapter(...rest);
  throw new Error('Usage: rate-proof.mjs plan | freeze REQUEST.json | freeze-bootstrap REQUEST.json | assess POLICY.json MEASUREMENT.json | calibrate REQUEST.json ADAPTER.json UNIQUE_OUT --authorize-calibration | bootstrap-calibrate REQUEST.json ADAPTER.json UNIQUE_OUT --authorize-calibration-bootstrap | run POLICY.json ADAPTER.json UNIQUE_OUT --authorize-load');
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main(process.argv.slice(2)).then(result => {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    if (result.status === 'BLOCKED' || (result.result && result.result !== 'PASS_DIAGNOSTIC')) process.exitCode = 1;
  }).catch(error => { process.stderr.write(`${error.message}\n`); process.exitCode = 1; });
}
