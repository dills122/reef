import { createHash } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { realpath, readdir, open, lstat, opendir } from 'node:fs/promises';
import path from 'node:path';
import { readBoundedBytes } from './physical-adapter.mjs';
import { validatePhysicalEvidence, physicalDelta } from './physical-evidence.mjs';
import { EXTERNAL_ARMS } from './lib/external-faults.mjs';
import { validateBrokerEndpoints } from './proof-supervisor.mjs';

const sha = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
function freeze(value) { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); } return value; }
const limits = {
  sourceRecordMaxBytes: 1024, unsignedHistoryMaxBytes: 65536, historyChangesMax: 64,
  checksummedHistoryMaxBytes: 65614, resultMaxBytes: 135324,
  sourceActions: 2100, historyRecords: 2101, maxPollRecords: 16,
  maxPartitionFetchBytes: 262144, fetchMaxBytes: 524288,
  producerBufferBytes: 4194304, producerBatchBytes: 65536, maxRequestBytes: 262144,
  bufferedRecordsPerPartition: 16, topicMaxMessageBytes: 262144,
  ackJournalMaxBytes: 33554432, rawEncodedMaxBytes: 150994944,
};
export const BOOTSTRAP = freeze({ schema: 'financial-calibration-bootstrap-v1', sampleTrades: 1000,
  pendingSample: 100, agedIdentities: 0, limits, limitsSha256: sha(limits),
  purpose: 'EMPIRICAL_BOUNDED_DIAGNOSTIC', capacityQualification: false, heapConservativeBound: false });
export const E3_SCOPE = 'Actual same-candidate RF3/EOS financial proof: core24 + golden20 + external4 + activation1; E3 configuration and plan identities retained separately from planned bootstrap configuration.';

/** Empirical finite experiment; this schema cannot authorize conventional rate arms. */
export function freezeBootstrap(input) {
  const r = structuredClone(input), reject = reason => { throw Error(`BOOTSTRAP_${reason}`); };
  if (r.schema !== BOOTSTRAP.schema || r.purpose !== BOOTSTRAP.purpose || r.capacityQualification !== false
    || r.heapConservativeBound !== false || r.heap !== undefined || r.arm !== undefined
    || r.estimatedHeapBytes != null) reject('DIAGNOSTIC_SCOPE_REQUIRED');
  for (const field of ['sampleTrades', 'pendingSample', 'agedIdentities']) if (r[field] !== BOOTSTRAP[field]) reject('FIXED_COHORT_REQUIRED');
  if (r.sourceActions !== 2100 || r.historyRecords !== 2101 || !isDeepStrictEqual(r.limits, BOOTSTRAP.limits)) reject('FROZEN_LIMITS_REQUIRED');
  if (!/^[a-f0-9]{40}$/.test(r.sourceHead ?? '') || !hash(r.fixtureSha256) || !hash(r.candidateSha256)) reject('SOURCE_IDENTITY_REQUIRED');
  if (r.correctness?.result !== 'PASS' || !hash(r.correctness.evidenceSha256)
    || r.correctness.sourceHead !== r.sourceHead || r.correctness.fixtureSha256 !== r.fixtureSha256) reject('E3_CORRECTNESS_REQUIRED');
  const c = r.capability, review = r.review;
  const correctness = r.correctness;
  if (correctness.schema !== 'financial-e3-correctness-binding-v1' || correctness.scope !== E3_SCOPE
    || correctness.candidateSha256 !== r.candidateSha256 || correctness.buildSha256 !== c?.buildSha256
    || correctness.classpathSha256 !== c?.classpathSha256 || correctness.plannedBootstrapConfigSha256 !== c?.configSha256
    || correctness.sourceCandidateEvidenceSha256 !== r.candidateEvidenceSha256
    || !hash(correctness.evidenceSha256) || !hash(correctness.proofManifestSha256)
    || !isDeepStrictEqual(correctness.arms, { core: 24, golden: 20, external: 4, activation: 1 })
    || !isDeepStrictEqual(Object.keys(correctness.e3PlanSha256 ?? {}).sort(), ['activation', 'core', 'external', 'golden'])
    || !Object.values(correctness.e3PlanSha256).every(hash)
    || !['correctnessEvidencePath', 'correctnessProofManifestEvidencePath', 'correctnessProofRoot'].every(k => typeof r[k] === 'string' && r[k])) reject('CURRENT_E3_BINDING_REQUIRED');
  if (c?.sourceHead !== r.sourceHead || c.fixtureSha256 !== r.fixtureSha256
    || !['configSha256', 'buildSha256', 'classpathSha256'].every(k => hash(c[k]))
    || c.maxHeapBytes !== 805306368 || !isDeepStrictEqual(c.vmArguments, ['-Xms128m', '-Xmx768m'])
    || !Number.isSafeInteger(c.usedHeapBytes) || c.usedHeapBytes < 0 || c.usedHeapBytes >= 644245094
    || typeof c.javaVersion !== 'string' || !/^21(?:[.+-]|$)/.test(c.javaVersion)
    || typeof c.javaVendor !== 'string' || !c.javaVendor) reject('PINNED_CAPABILITY_REQUIRED');
  if (review?.schema !== 'financial-bootstrap-review-v1' || review.verdict !== 'READY_BOUNDED_DIAGNOSTIC'
    || review.candidateSha256 !== r.candidateSha256 || review.limitsSha256 !== BOOTSTRAP.limitsSha256
    || !['fixtureSha256', 'configSha256', 'buildSha256', 'classpathSha256'].every(k => review[k] === c[k])
    || !['sampleTrades', 'pendingSample', 'agedIdentities'].every(k => review[k] === r[k])) reject('REVIEW_SCOPE_REQUIRED');
  const frozen = { ...r, status: 'FROZEN_DIAGNOSTIC', estimatedHeapBytes: null };
  delete frozen.bootstrapSha256;
  return freeze({ ...frozen, bootstrapSha256: sha(frozen) });
}

export async function verifyBootstrapEvidence(request, requestPath, configPath, actual = request.capability) {
  const frozen = freezeBootstrap(request);
  if (request.status !== frozen.status || request.bootstrapSha256 !== frozen.bootstrapSha256)
    throw Error('BOOTSTRAP_FROZEN_HASH_REQUIRED');
  const immutable = c => Object.fromEntries(Object.entries(c).filter(([key]) => key !== 'usedHeapBytes'));
  if (!isDeepStrictEqual(immutable(actual), immutable(request.capability))
    || !Number.isSafeInteger(actual.usedHeapBytes) || actual.usedHeapBytes < 0 || actual.usedHeapBytes >= 644245094)
    throw Error('BOOTSTRAP_ACTUAL_CAPABILITY_DRIFT');
  const root = await realpath(path.dirname(path.resolve(requestPath)));
  const owned = async name => {
    if (typeof name !== 'string') throw Error('BOOTSTRAP_RAW_EVIDENCE_REQUIRED');
    const file = await realpath(path.resolve(root, name));
    if (!file.startsWith(root + path.sep)) throw Error('BOOTSTRAP_EVIDENCE_OUTSIDE_REQUEST');
    return file;
  };
  let candidate;
  for (const kind of ['capability', 'review', 'candidate']) {
    const raw = await readBoundedBytes(await owned(request[`${kind}EvidencePath`]), 1024 * 1024);
    if (createHash('sha256').update(raw).digest('hex') !== request[`${kind}EvidenceSha256`]) throw Error('BOOTSTRAP_RAW_EVIDENCE_HASH');
    const parsed = JSON.parse(raw);
    if (kind === 'candidate') candidate = parsed;
    else if (!isDeepStrictEqual(parsed, request[kind])) throw Error('BOOTSTRAP_RAW_EVIDENCE_IDENTITY');
  }
  const fixture = await readBoundedBytes(await owned(request.fixtureEvidencePath), 1024 * 1024);
  if (createHash('sha256').update(fixture).digest('hex') !== request.fixtureSha256) throw Error('BOOTSTRAP_FIXTURE_HASH');
  const configRaw = await readBoundedBytes(configPath, 1024 * 1024);
  if (createHash('sha256').update(configRaw).digest('hex') !== request.capability.configSha256)
    throw Error('BOOTSTRAP_CONFIG_HASH');
  const config = JSON.parse(configRaw); let brokers;
  try { brokers = validateBrokerEndpoints(config.registeredResources, true); }
  catch { throw Error('BOOTSTRAP_CONFIG_BROKER_PINS'); }
  if (config.brokerScope?.schema !== 'financial-broker-scope-v1'
    || typeof config.brokerScope.clusterId !== 'string' || !config.brokerScope.clusterId.trim()
    || config.brokerScope.metadataSource !== 'actual AdminClient describeCluster'
    || config.brokerScope.bootstrapServers !== config.registeredResources.bootstrapServers
    || !isDeepStrictEqual(config.brokerScope.brokers, brokers)) throw Error('BOOTSTRAP_CONFIG_BROKER_SCOPE');
  if (candidate.schema !== 'financial-candidate-source-v1' || candidate.sourceHead !== request.sourceHead
    || candidate.candidateSha256 !== request.candidateSha256 || sha(candidate.sourceSha256) !== request.candidateSha256)
    throw Error('BOOTSTRAP_CANDIDATE_HASH');
  const sourceRoot = await realpath(candidate.sourceRoot), financial = 'services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial';
  if (!actual.classpathEntries?.includes(path.join(sourceRoot, 'services/platform-runtime/build/classes/kotlin/test')))
    throw Error('BOOTSTRAP_SOURCE_BUILD_ROOT_MISMATCH');
  const required = (await readdir(path.join(sourceRoot, financial))).filter(n => /^Financial.*\.kt$/.test(n)).map(n => `${financial}/${n}`);
  for (const name of ['FinancialKernel.kt', 'FinancialOracle.kt', 'FinancialBrokerProbe.kt', 'FinancialPartitionCut.kt',
    'FinancialRateProbe.kt', 'FinancialHeapGuard.kt', 'FinancialAckJournal.kt', 'FinancialPhysicalInventory.kt'])
    if (!required.includes(`${financial}/${name}`)) throw Error('BOOTSTRAP_SOURCE_COVERAGE_REQUIRED');
  for (const name of ['rate-proof.mjs', 'bootstrap-calibration.mjs', 'rate-supervision.mjs', 'broker-proof.mjs', 'proof-supervisor.mjs', 'physical-evidence.mjs', 'physical-adapter.mjs', 'lib/external-faults.mjs'])
    required.push(`scripts/dev/calcify-financial/${name}`);
  if (required.some(name => !hash(candidate.sourceSha256?.[name]))) throw Error('BOOTSTRAP_SOURCE_COVERAGE_REQUIRED');
  for (const [name, expected] of Object.entries(candidate.sourceSha256)) {
    const file = await realpath(path.resolve(sourceRoot, name));
    if (!file.startsWith(sourceRoot + path.sep) || !hash(expected)
      || createHash('sha256').update(await readBoundedBytes(file, 1024 * 1024)).digest('hex') !== expected)
      throw Error('BOOTSTRAP_SOURCE_DRIFT');
  }
  await verifyCurrentE3Evidence(request, root, owned, candidate, config.registeredResources.bootstrapServers);
  return frozen;
}

async function verifyCurrentE3Evidence(request, root, owned, candidate, bootstrapServers) {
  const reject = reason => { throw Error(`BOOTSTRAP_E3_${reason}`); }, check = (value, reason) => { if (!value) reject(reason); };
  const raw = await readBoundedBytes(await owned(request.correctnessEvidencePath), 1024 * 1024);
  check(createHash('sha256').update(raw).digest('hex') === request.correctness.evidenceSha256, 'RAW_HASH');
  const expected = Object.fromEntries(Object.entries(request.correctness).filter(([k]) => k !== 'evidenceSha256'));
  check(isDeepStrictEqual(JSON.parse(raw), expected), 'RAW_IDENTITY');
  const proofRaw = await readBoundedBytes(await owned(request.correctnessProofManifestEvidencePath), 8 * 1024 * 1024);
  check(createHash('sha256').update(proofRaw).digest('hex') === expected.proofManifestSha256, 'RAW_PROOF_MANIFEST_HASH');
  const proof = JSON.parse(proofRaw);
  check(proof.schema === 'financial-e3-proof-manifest-v1' && ['sourceHead', 'candidateSha256', 'fixtureSha256', 'buildSha256', 'classpathSha256', 'plannedBootstrapConfigSha256']
    .every(k => proof[k] === expected[k]) && isDeepStrictEqual(proof.arms, expected.arms) && isDeepStrictEqual(proof.e3PlanSha256, expected.e3PlanSha256), 'PROOF_MANIFEST_IDENTITY');
  const proofRoot = await realpath(proof.proofRoot);
  check(proofRoot === proof.proofRoot && proofRoot === request.correctnessProofRoot && proofRoot.startsWith(path.dirname(root) + path.sep)
    && proofRoot !== root && proofRoot !== path.dirname(root), 'RAW_PROOF_ROOT');
  check(Array.isArray(proof.files) && proof.files.length > 0 && proof.files.length <= 65536, 'RAW_FILE_SET');
  const enumerated = []; let entries = 0;
  async function walk(dir, depth = 0) {
    check(depth <= 64, 'RAW_DIRECTORY_DEPTH');
    for await (const entry of await opendir(dir)) {
      check(++entries <= 65536, 'RAW_TREE_ENTRY_BOUND');
      const file = path.join(dir, entry.name); check(!entry.isSymbolicLink(), 'RAW_SYMLINK');
      if (entry.isDirectory()) await walk(file, depth + 1); else { check(entry.isFile(), 'RAW_FILE_TYPE'); enumerated.push(file); check(enumerated.length <= 65536, 'RAW_FILE_COUNT'); }
    }
  }
  await walk(proofRoot); enumerated.sort();
  check(isDeepStrictEqual(proof.files.map(r => r.path), enumerated), 'RAW_COMPLETE_SORTED_SET');
  let total = 0; const selected = new Map();
  for (const row of proof.files) {
    check(typeof row.path === 'string' && row.path.startsWith(proofRoot + path.sep) && await realpath(row.path) === row.path
      && Number.isSafeInteger(row.sizeBytes) && row.sizeBytes >= 0 && row.sizeBytes <= 8 * 1024 * 1024 && hash(row.sha256), 'RAW_FILE_IDENTITY');
    const stat = await lstat(row.path); check(stat.isFile() && !stat.isSymbolicLink() && stat.size === row.sizeBytes, 'RAW_FILE_SIZE');
    total += row.sizeBytes; check(Number.isSafeInteger(total) && total <= 256 * 1024 * 1024, 'RAW_TOTAL_BOUND');
    const file = await open(row.path, 'r'), digest = createHash('sha256'), buffer = Buffer.alloc(65536); let count = 0;
    try { while (true) { const { bytesRead } = await file.read(buffer, 0, buffer.length, count); if (!bytesRead) break;
      count += bytesRead; check(count <= 8 * 1024 * 1024, 'RAW_FILE_BOUND'); digest.update(buffer.subarray(0, bytesRead)); }
    } finally { await file.close(); }
    check(count === row.sizeBytes && digest.digest('hex') === row.sha256, 'RAW_FILE_HASH');
    for (const [kind, planHash] of Object.entries(expected.e3PlanSha256)) if (row.sha256 === planHash && path.basename(row.path) === 'plan.json') {
      check(!selected.has(kind), 'DUPLICATE_SELECTED_PLAN'); selected.set(kind, row.path);
    }
  }
  const compiled = {}; let compiledBytes = 0;
  check(Array.isArray(request.capability.classpathEntries) && request.capability.classpathEntries.length > 0
    && request.capability.classpathEntries.length <= 1024, 'CURRENT_CLASSPATH_REQUIRED');
  async function compiledFile(file) {
    check(path.isAbsolute(file) && path.resolve(file) === file, 'CURRENT_COMPILED_PATH');
    const stat = await lstat(file); check(stat.isFile() && stat.size <= 512 * 1024 * 1024, 'CURRENT_COMPILED_BOUND');
    compiledBytes += stat.size; check(compiledBytes <= 2 * 1024 * 1024 * 1024, 'CURRENT_COMPILED_TOTAL_BOUND');
    const handle = await open(file, 'r'), digest = createHash('sha256'), buffer = Buffer.alloc(65536); let size = 0;
    try { while (true) { const { bytesRead } = await handle.read(buffer, 0, buffer.length, size); if (!bytesRead) break;
      size += bytesRead; check(size <= 512 * 1024 * 1024, 'CURRENT_COMPILED_BOUND'); digest.update(buffer.subarray(0, bytesRead)); }
    } finally { await handle.close(); }
    check(size === stat.size, 'CURRENT_COMPILED_DRIFT'); compiled[file] = digest.digest('hex');
  }
  for (const entry of request.capability.classpathEntries) {
    if (entry.endsWith('.jar')) await compiledFile(entry);
    else {
      const dir = path.join(entry, 'com/reef/platform/calcify/financial');
      let names; try { names = await readdir(dir); } catch (e) { if (e.code === 'ENOENT' || e.code === 'ENOTDIR') continue; throw e; }
      for (const name of names.filter(n => /^Financial.*\.class$/.test(n)).sort()) await compiledFile(path.join(dir, name));
    }
  }
  check(Object.keys(compiled).some(file => path.basename(file) === 'FinancialRateProbe.class'), 'CURRENT_FINANCIAL_BUILD_REQUIRED');
  for (const [kind, count] of Object.entries(expected.arms)) {
    const planPath = selected.get(kind); check(planPath, 'SELECTED_PLAN_MISSING');
    const plan = JSON.parse((await readBoundedBytes(planPath, 8 * 1024 * 1024)).toString('utf8'));
    check(plan.schema === 'financial-e3-plan-v1' && plan.fixtureSha256 === request.fixtureSha256, 'SELECTED_PLAN_IDENTITY');
    check(plan.broker === bootstrapServers, 'SELECTED_PLAN_BROKER');
    check(plan.classpath === request.capability.classpathEntries.join(path.delimiter)
      && isDeepStrictEqual(plan.compiledHashes, compiled), 'SELECTED_PLAN_COMPILED_CLASSPATH');
    const sources = Object.fromEntries(Object.entries(candidate.sourceSha256).filter(([name]) => name.startsWith('services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial/') && /^Financial.*\.kt$/.test(path.basename(name)))
      .map(([name, digest]) => [path.basename(name), digest]));
    check(isDeepStrictEqual(plan.sourceHashes, sources), 'SELECTED_PLAN_SOURCE');
    const runners = Object.fromEntries(['scripts/dev/calcify-financial/broker-proof.mjs', 'scripts/dev/calcify-financial/lib/external-faults.mjs', 'scripts/dev/calcify-financial/proof-supervisor.mjs'].map(name => [name, candidate.sourceSha256[name]]));
    check(isDeepStrictEqual(plan.runnerHashes, runners), 'SELECTED_PLAN_RUNNER');
    const resultPath = path.join(path.dirname(planPath), 'results.json'); check(enumerated.includes(resultPath), 'SELECTED_RESULTS_MISSING');
    const result = JSON.parse((await readBoundedBytes(resultPath, 8 * 1024 * 1024)).toString('utf8'));
    let names;
    if (kind === 'core') { check(Array.isArray(plan.mutationBoundaries) && plan.mutationBoundaries.length === 19, 'CORE_MUTATION_SCOPE');
      names = ['happy', ...plan.mutationBoundaries.map(r => `mutation-${r.index}`), 'forward', 'serialization', 'local-state-loss', 'staged-phase-loss']; }
    else if (kind === 'golden') names = Array.from({ length: 20 }, (_, i) => `golden-${i}`);
    else if (kind === 'external') { check(isDeepStrictEqual(plan.externalMatrix, EXTERNAL_ARMS), 'EXTERNAL_MATRIX'); names = EXTERNAL_ARMS.map(r => r.name); }
    else names = ['activation-quartet'];
    check(result.prefix === plan.prefix && Array.isArray(result.results) && result.results.length === count && new Set(names).size === count
      && isDeepStrictEqual(result.results.map(r => r.name).sort(), names.sort())
      && result.results.every(r => r.after?.pass === true && r.topic === `${plan.prefix}-${r.name}`
        && (kind !== 'activation' || r.before?.pass === true && r.idle?.pass === true)), 'ACTUAL_SUCCESSFUL_ARM_SET');
  }
}

export async function validateBootstrapResult(request, measured, outputRoot) {
  const reject = reason => { throw Error(`BOOTSTRAP_RESULT_${reason}`); };
  const check = (condition, reason) => { if (!condition) reject(reason); };
  const integer = n => Number.isSafeInteger(n) && n >= 0;
  const positive = n => integer(n) && n > 0;
  const frozen = freezeBootstrap(request), m = measured;
  check(request.status === frozen.status && request.bootstrapSha256 === frozen.bootstrapSha256, 'FROZEN_REQUEST_HASH');
  check(m?.schema === 'financial-bootstrap-calibration-v1' && m.purpose === BOOTSTRAP.purpose && m.capacityQualification === false
    && m.heapConservativeBound === false && m.estimatedHeapBytes === null && m.arm === undefined && m.heap === undefined, 'DIAGNOSTIC_SCOPE');
  for (const field of ['bootstrapSha256', 'sourceHead', 'fixtureSha256', 'candidateSha256']) check(m[field] === request[field], 'IDENTITY_BINDING');
  check(isDeepStrictEqual(m.capability, request.capability), 'CAPABILITY_BINDING');
  for (const field of ['sampleTrades', 'pendingSample', 'agedIdentities']) check(m[field] === BOOTSTRAP[field], 'FIXED_COHORT');
  check(m.sourceActions === 2100 && m.historyRecords === 2101, 'COMPLETE_ACTION_HISTORY');
  check(['offered', 'callbackSuccess', 'admitted', 'decided', 'settled'].every(k => m.final?.[k] === 1000)
    && m.final.pending === 0, 'FINAL_TIMED_COUNTS');
  check(positive(m.processId) && typeof m.sourceTopicUuid === 'string' && m.sourceTopicUuid.length > 0, 'PROCESS_SOURCE_IDENTITY');
  function heap(h) {
    check(h?.schema === 'financial-bootstrap-heap-observation-v1' && h.purpose === BOOTSTRAP.purpose && h.heapConservativeBound === false
      && h.estimatedHeapBytes === null && h.sampled === true && h.guardOutcome === 'PASS_SAMPLED'
      && h.actualMaxHeapBytes === 805306368 && h.admissionLimitBytes === 644245094
      && integer(h.heapPeakBytes) && h.heapPeakBytes < h.admissionLimitBytes
      && positive(h.pollMillis) && h.pollMillis <= 250 && positive(h.maxSampleAgeNanos) && h.maxSampleAgeNanos <= 5000000000, 'SAMPLED_HEAP');
    check(Array.isArray(h.baselineObservations) && h.baselineObservations.length >= 1 && h.baselineObservations.length <= 16
      && h.baselineObservations.every(b => typeof b.stage === 'string' && b.stage.length > 0 && integer(b.usedHeapBytes)
        && b.usedHeapBytes < h.admissionLimitBytes), 'HEAP_BASELINE');
  }
  heap(m.heapObservation);
  const p = m.parity, recovery = m.managedRecovery, replay = m.resultOnlyReplay;
  check(hash(p?.ownerSha256) && hash(p.historyChecksum) && p.historyRecords === 2101 && p.sourceUuid === m.sourceTopicUuid
    && p.readCommitted === true && p.independentCompleteDeltas === true, 'INDEPENDENT_PARITY');
  check(m.managedRestartVerified === true && recovery?.schema === 'financial-bootstrap-process-recovery-v1'
    && positive(recovery.processId) && recovery.processId !== m.processId && recovery.bootstrapSha256 === request.bootstrapSha256
    && recovery.sourceUuid === m.sourceTopicUuid && recovery.recoveredMembers === 2100 && recovery.controllerAdmissionsReplayed === 0
    && recovery.oldControllerAdmissionTimes === 'unknown' && recovery.firstFreshResult === null, 'NEW_JVM_RECOVERY');
  heap(recovery.heapObservation);
  check(recovery.activation?.ownerSha256 === p.ownerSha256 && recovery.activation.historyChecksum === p.historyChecksum
    && recovery.activation.historyRecords === 2101 && recovery.activation.ordinal === 2099 && integer(recovery.activation.offset), 'MANAGED_ACTIVATION_PARITY');
  check(m.resultOnlyReplayVerified === true && replay?.records === 2101 && replay.inputs === 2100 && positive(replay.bytes)
    && integer(replay.elapsedMs) && integer(replay.committedResultOffsetExclusive) && replay.committedResultOffsetExclusive >= 2100
    && replay.ownerSha256 === p.ownerSha256 && replay.historyChecksum === p.historyChecksum && replay.sourceUuid === m.sourceTopicUuid
    && replay.readCommitted === true && replay.completeCommittedCut === true, 'COMPLETE_RESULT_REPLAY');
  const journal = m.ackJournal;
  check(journal?.schema === 'financial-ack-journal-v1' && journal.publishedMembers === 2100 && journal.recoveredMembers === 0
    && journal.maxAggregateBytes === BOOTSTRAP.limits.ackJournalMaxBytes && positive(journal.logicalBytes)
    && journal.logicalBytes <= journal.maxAggregateBytes && ['dataSha256', 'publicationSha256', 'publicationWitnessSha256'].every(k => hash(journal[k])), 'DURABLE_JOURNAL');
  check(positive(m.encodedInputBytes) && positive(m.encodedResultBytes) && positive(m.encodedBytes)
    && Number.isSafeInteger(m.encodedInputBytes + m.encodedResultBytes) && m.encodedInputBytes + m.encodedResultBytes === m.encodedBytes
    && m.encodedBytes <= BOOTSTRAP.limits.rawEncodedMaxBytes && replay.bytes === m.encodedResultBytes && hash(m.realRecordEvidenceSha256), 'ENCODED_BYTE_SCOPE');
  const root = await realpath(path.resolve(outputRoot)), owned = async name => {
    check(typeof name === 'string' && name.length > 0, 'RAW_PATH');
    const resolved = path.resolve(root, name), actual = await realpath(resolved);
    check(actual === resolved && actual.startsWith(root + path.sep), 'RAW_PATH_ESCAPE'); return actual;
  };
  const process = m.managedRecoveryProcess;
  check(process?.processId === recovery.processId && process.parentProcessId === m.processId && process.exitCode === 0
    && process.newJvm === true && hash(m.managedRecoveryEvidenceSha256) && hash(process.commandEvidenceSha256), 'RECOVERY_PROCESS_RECEIPT');
  const recoveryPath = await owned(m.managedRecoveryEvidencePath), commandPath = await owned(process.commandEvidencePath);
  const recoveryRaw = await readBoundedBytes(recoveryPath, 1024 * 1024), commandRaw = await readBoundedBytes(commandPath, 1024 * 1024);
  check(createHash('sha256').update(recoveryRaw).digest('hex') === m.managedRecoveryEvidenceSha256
    && isDeepStrictEqual(JSON.parse(recoveryRaw), recovery) && createHash('sha256').update(commandRaw).digest('hex') === process.commandEvidenceSha256, 'RECOVERY_RAW_HASH_BINDING');
  const command = JSON.parse(commandRaw);
  check(Array.isArray(command) && command.length === 13 && typeof command[0] === 'string' && path.isAbsolute(command[0])
    && path.basename(command[0]) === 'java' && command[1] === '-Xms128m' && command[2] === '-Xmx768m'
    && command[3] === '-cp' && command[4] === request.capability.classpathEntries?.join(path.delimiter)
    && command[5] === 'com.reef.platform.calcify.financial.FinancialRateProbe' && command[6] === 'bootstrap-recover'
    && typeof command[7] === 'string' && command[7].length > 0 && /^financial-s1-/.test(command[8])
    && path.isAbsolute(command[9]) && path.isAbsolute(command[10]) && path.isAbsolute(command[11])
    && command[12] === recoveryPath, 'RECOVERY_RAW_COMMAND');
  // Raw encoded proof can approach144MiB; hash streamed64KiB chunks, never retain whole file in RAM.
  const encodedPath = await owned(m.realRecordEvidencePath), handle = await open(encodedPath, 'r');
  let encodedHash, encodedBytes;
  try {
    const stat = await handle.stat(); check(stat.isFile() && stat.size === m.encodedBytes && stat.size <= BOOTSTRAP.limits.rawEncodedMaxBytes, 'RAW_ENCODED_SIZE');
    const digest = createHash('sha256'), buffer = Buffer.alloc(65536); let total = 0;
    while (true) { const { bytesRead } = await handle.read(buffer, 0, buffer.length, total); if (!bytesRead) break;
      total += bytesRead; check(total <= BOOTSTRAP.limits.rawEncodedMaxBytes, 'RAW_ENCODED_BOUND'); digest.update(buffer.subarray(0, bytesRead)); }
    check(total === stat.size, 'RAW_ENCODED_DRIFT'); encodedBytes = total; encodedHash = digest.digest('hex');
    check(encodedHash === m.realRecordEvidenceSha256, 'RAW_ENCODED_HASH');
  } finally { await handle.close(); }
  const stages = ['warmed', 'before-settled', 'after-settled', 'after-pending', 'workers-closed', 'after-managed-restart', 'after-result-replay'];
  check(Array.isArray(m.physicalCheckpoints) && m.physicalCheckpoints.length === stages.length
    && m.physicalCheckpoints.every((c, i) => c.stage === stages[i]), 'PHYSICAL_CHECKPOINT_SET');
  const validated = []; let physicalRawBytes = recoveryRaw.length + commandRaw.length, firstManifest;
  const seen = new Set();
  for (const checkpoint of m.physicalCheckpoints) {
    const files = {};
    for (const kind of ['manifest', 'inventory', 'artifact']) {
      const file = await owned(checkpoint[`${kind}Path`]); check(!seen.has(file), 'DUPLICATE_PHYSICAL_RAW_PATH'); seen.add(file);
      const bytes = await readBoundedBytes(file, 8 * 1024 * 1024);
      check(hash(checkpoint[`${kind}Sha256`]) && createHash('sha256').update(bytes).digest('hex') === checkpoint[`${kind}Sha256`], 'PHYSICAL_RAW_HASH');
      physicalRawBytes += bytes.length; check(Number.isSafeInteger(physicalRawBytes) && physicalRawBytes + encodedBytes <= 256 * 1024 * 1024, 'COMBINED_RETAINED_RAW_BOUND');
      files[kind] = JSON.parse(bytes);
    }
    const manifest = files.manifest, artifact = files.artifact;
    check(isDeepStrictEqual(artifact, checkpoint.artifact) && isDeepStrictEqual(files.inventory, artifact.raw?.inventory), 'PHYSICAL_RAW_BINDING');
    check(manifest.provenance?.candidateSha256 === request.candidateSha256 && manifest.provenance.sourceCommit === request.sourceHead
      && ['configSha256', 'fixtureSha256', 'buildSha256', 'classpathSha256'].every(k => manifest.provenance[k] === request.capability[k]), 'PHYSICAL_PROVENANCE');
    check(manifest.topics?.some(t => t.category === 'source' && t.topicId === m.sourceTopicUuid && t.name === `${manifest.runId}-input`)
      && manifest.runId === command[8] && manifest.localResources?.some(r => r.category === 'proof' && r.path === root), 'PHYSICAL_SOURCE_OWNERSHIP');
    if (firstManifest) check(isDeepStrictEqual(manifest, firstManifest), 'PHYSICAL_MANIFEST_DRIFT'); else firstManifest = manifest;
    const receipt = checkpoint.process, clock = checkpoint.clockReceipt;
    check(clock?.verified === true && clock.clockScope === 'host-monotonic-ms' && typeof clock.nodeExecutable === 'string'
      && typeof clock.adapterScript === 'string' && receipt?.exitCode === 0 && receipt.error === null
      && integer(receipt.startedAtMs) && integer(receipt.completedAtMs) && receipt.completedAtMs >= receipt.startedAtMs
      && receipt.completedAtMs - receipt.startedAtMs <= 5000 && typeof receipt.stdout === 'string' && typeof receipt.stderr === 'string'
      && Buffer.byteLength(receipt.stdout) <= 32768 && Buffer.byteLength(receipt.stderr) <= 32768
      && isDeepStrictEqual(receipt.argv, [clock.nodeExecutable, clock.adapterScript, '--collect', checkpoint.manifestPath, checkpoint.inventoryPath, checkpoint.artifactPath])
      && JSON.parse(receipt.stdout).artifact === checkpoint.artifactPath, 'PHYSICAL_COLLECTOR_RECEIPT');
    // Historical samples validated at their actual completion clock, never relabelled fresh now.
    validatePhysicalEvidence(manifest, artifact, artifact.completedAtMs);
    if (validated.length) check(artifact.startedAtMs >= validated.at(-1).completedAtMs, 'PHYSICAL_CHECKPOINT_ORDER');
    validated.push(artifact);
  }
  const deltas = validated.slice(1).map((after, i) => ({ fromStage: stages[i], toStage: stages[i + 1],
    ...physicalDelta(firstManifest, validated[i], after, after.completedAtMs) }));
  return freeze({ schema: 'financial-bootstrap-assessment-v1', status: 'VALIDATED_BOUNDED_DIAGNOSTIC', bootstrapSha256: request.bootstrapSha256,
    candidateSha256: request.candidateSha256, capacityQualification: false, heapConservativeBound: false,
    encodedEvidence: { path: encodedPath, bytes: encodedBytes, sha256: encodedHash }, physicalRawBytes, physicalDeltas: deltas });
}
