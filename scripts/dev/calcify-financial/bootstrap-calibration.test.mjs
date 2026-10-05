import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, writeFile, readFile, rm, realpath, symlink } from 'node:fs/promises';
import path from 'node:path';
import { tmpdir } from 'node:os';
import { BOOTSTRAP, E3_SCOPE, freezeBootstrap, verifyBootstrapEvidence, validateBootstrapResult } from './bootstrap-calibration.mjs';
import { EXTERNAL_ARMS } from './lib/external-faults.mjs';
import { collectPhysicalEvidence, freezePhysicalManifest } from './physical-evidence.mjs';
const hash = 'a'.repeat(64);
function request() {
  return { schema: 'financial-calibration-bootstrap-v1', purpose: 'EMPIRICAL_BOUNDED_DIAGNOSTIC',
    capacityQualification: false, heapConservativeBound: false, sourceHead: 'a'.repeat(40),
    fixtureSha256: hash, candidateSha256: hash, sampleTrades: 1000, pendingSample: 100,
    agedIdentities: 0, sourceActions: 2100, historyRecords: 2101,
    limits: structuredClone(BOOTSTRAP.limits),
    candidateEvidenceSha256: hash, correctnessEvidencePath: 'correctness.json', correctnessProofManifestEvidencePath: 'e3-proof-manifest.json', correctnessProofRoot: '/synthetic/e3',
    correctness: { schema: 'financial-e3-correctness-binding-v1', result: 'PASS', evidenceSha256: hash, sourceHead: 'a'.repeat(40), fixtureSha256: hash,
      candidateSha256: hash, buildSha256: hash, classpathSha256: hash, plannedBootstrapConfigSha256: hash,
      arms: { core: 24, golden: 20, external: 4, activation: 1 }, scope: E3_SCOPE, sourceCandidateEvidenceSha256: hash,
      proofManifestSha256: hash, e3PlanSha256: { core: hash, golden: hash, external: hash, activation: hash } },
    capability: { sourceHead: 'a'.repeat(40), fixtureSha256: hash, configSha256: hash,
      buildSha256: hash, classpathSha256: hash, maxHeapBytes: 768 * 1024 ** 2, usedHeapBytes: 16 * 1024 ** 2,
      vmArguments: ['-Xms128m', '-Xmx768m'], javaVersion: '21.0.11', javaVendor: 'Oracle Corporation' },
    review: { schema: 'financial-bootstrap-review-v1', verdict: 'READY_BOUNDED_DIAGNOSTIC',
      candidateSha256: hash, fixtureSha256: hash, configSha256: hash, buildSha256: hash, classpathSha256: hash,
      limitsSha256: BOOTSTRAP.limitsSha256, sampleTrades: 1000, pendingSample: 100, agedIdentities: 0 },
  };
}
test('finite bootstrap freeze remains empirical and never manufactures heap costs', () => {
  const input = request(), frozen = freezeBootstrap(input); input.sampleTrades = 1;
  assert.equal(frozen.sampleTrades, 1000);
  assert.equal(frozen.status, 'FROZEN_DIAGNOSTIC');
  assert.equal(frozen.estimatedHeapBytes, null);
  assert.equal(frozen.capacityQualification, false);
  assert.equal(frozen.heapConservativeBound, false);
  assert.equal(frozen.bootstrapSha256.length, 64);
  assert.throws(() => frozen.limits.sourceRecordMaxBytes++);
});
test('bootstrap refuses cohort expansion, changed caps, conventional arms and mismatched review', () => {
  for (const mutate of [
    r => r.sampleTrades++, r => r.pendingSample++, r => r.agedIdentities++,
    r => r.sourceActions++, r => r.historyRecords++, r => r.arm = { rate: 2500 },
    r => r.heap = {}, r => r.estimatedHeapBytes = 1,
    r => r.limits.resultMaxBytes++, r => r.capability.maxHeapBytes++,
    r => r.capability.vmArguments.push('-Xmx1g'), r => r.correctness.result = 'FAIL',
    r => r.review.verdict = 'READY_CONSERVATIVE_BOUND', r => r.review.buildSha256 = 'b'.repeat(64),
    r => r.review.sampleTrades = 1, r => r.capacityQualification = true,
    r => r.correctness.candidateSha256 = 'b'.repeat(64), r => r.correctness.buildSha256 = 'b'.repeat(64),
    r => r.correctness.classpathSha256 = 'b'.repeat(64), r => r.correctness.plannedBootstrapConfigSha256 = 'b'.repeat(64),
    r => r.correctness.arms.core = 23, r => r.correctness.scope = 'old PASS', r => delete r.correctnessEvidencePath,
    r => r.correctness.sourceCandidateEvidenceSha256 = 'b'.repeat(64),
  ]) { const r = request(); mutate(r); assert.throws(() => freezeBootstrap(r)); }
});

test('executable bootstrap binds raw review, fixture, config and complete source bytes', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'bootstrap-raw-control-')));
  const digest = value => createHash('sha256').update(value).digest('hex');
  try {
    const input = request(), financial = 'services/platform-runtime/src/test/kotlin/com/reef/platform/calcify/financial';
    const sourceSha256 = {};
    const sources = ['FinancialKernel.kt', 'FinancialOracle.kt', 'FinancialBrokerProbe.kt', 'FinancialPartitionCut.kt',
      'FinancialRateProbe.kt', 'FinancialHeapGuard.kt', 'FinancialAckJournal.kt', 'FinancialPhysicalInventory.kt'].map(n => `${financial}/${n}`);
    for (const name of ['rate-proof.mjs', 'bootstrap-calibration.mjs', 'rate-supervision.mjs', 'broker-proof.mjs', 'proof-supervisor.mjs',
      'physical-evidence.mjs', 'physical-adapter.mjs', 'lib/external-faults.mjs']) sources.push(`scripts/dev/calcify-financial/${name}`);
    for (const name of sources) {
      const file = path.join(root, name); await mkdir(path.dirname(file), { recursive: true });
      const raw = `Synthetic source control only: ${name}`; await writeFile(file, raw); sourceSha256[name] = digest(raw);
    }
    input.candidateSha256 = digest(JSON.stringify(sourceSha256)); input.review.candidateSha256 = input.candidateSha256;
    const fixture = '{"fixture":"validation only"}', brokers = [0, 1, 2].map(id => ({ id, host: '127.0.0.1', port: 39492 + id * 100 })),
      bootstrapServers = brokers.map(b => `${b.host}:${b.port}`).join(','), config = JSON.stringify({
        registeredResources: { bootstrapServers, containers: brokers.map(b => ({ brokerId: b.id,
          hostKafkaEndpoint: { host: b.host, port: b.port, containerPort: `${b.port}/tcp` } })) },
        brokerScope: { schema: 'financial-broker-scope-v1', bootstrapServers, clusterId: 'actual-cluster-control',
          metadataSource: 'actual AdminClient describeCluster', brokers } });
    input.fixtureSha256 = digest(fixture); input.capability.fixtureSha256 = input.fixtureSha256;
    input.review.fixtureSha256 = input.fixtureSha256; input.correctness.fixtureSha256 = input.fixtureSha256;
    input.capability.configSha256 = digest(config); input.review.configSha256 = input.capability.configSha256;
    input.capability.classpathEntries = [path.join(root, 'services/platform-runtime/build/classes/kotlin/test')];
    const classPath = path.join(input.capability.classpathEntries[0], 'com/reef/platform/calcify/financial/FinancialRateProbe.class');
    await mkdir(path.dirname(classPath), { recursive: true }); const classRaw = 'Synthetic compiled identity control only'; await writeFile(classPath, classRaw);
    input.fixtureEvidencePath = 'fixture.json'; await writeFile(path.join(root, 'fixture.json'), fixture);
    const configPath = path.join(root, 'config.json'); await writeFile(configPath, config);
    const candidate = { schema: 'financial-candidate-source-v1', sourceHead: input.sourceHead,
      sourceRoot: root, sourceSha256, candidateSha256: input.candidateSha256 };
    input.correctness.candidateSha256 = input.candidateSha256; input.correctness.plannedBootstrapConfigSha256 = input.capability.configSha256;
    input.correctnessProofRoot = path.join(root, 'e3'); await mkdir(input.correctnessProofRoot);
    const files = [];
    for (const [kind, count] of Object.entries(input.correctness.arms)) {
      const dir = path.join(input.correctnessProofRoot, kind); await mkdir(dir);
      const plan = { schema: 'financial-e3-plan-v1', broker: bootstrapServers, fixtureSha256: input.fixtureSha256, prefix: `financial-s1-${kind}`,
        classpath: input.capability.classpathEntries.join(path.delimiter), compiledHashes: { [classPath]: digest(classRaw) },
        runnerHashes: Object.fromEntries(['scripts/dev/calcify-financial/broker-proof.mjs', 'scripts/dev/calcify-financial/lib/external-faults.mjs', 'scripts/dev/calcify-financial/proof-supervisor.mjs'].map(name => [name, sourceSha256[name]])),
        sourceHashes: Object.fromEntries(Object.entries(sourceSha256).filter(([name]) => name.endsWith('.kt')).map(([name, value]) => [path.basename(name), value])),
        mutationBoundaries: Array.from({ length: 19 }, (_, index) => ({ index })), externalMatrix: EXTERNAL_ARMS };
      const names = kind === 'core' ? ['happy', ...plan.mutationBoundaries.map(({ index }) => `mutation-${index}`), 'forward', 'serialization', 'local-state-loss', 'staged-phase-loss']
        : kind === 'golden' ? Array.from({ length: count }, (_, i) => `golden-${i}`) : kind === 'external' ? EXTERNAL_ARMS.map(a => a.name) : ['activation-quartet'];
      const result = { prefix: plan.prefix, results: names.map(name => ({ name, topic: `${plan.prefix}-${name}`, after: { pass: true },
        ...(kind === 'activation' ? { before: { pass: true }, idle: { pass: true } } : {}) })) };
      for (const [name, value] of [['plan.json', plan], ['results.json', result]]) {
        const raw = JSON.stringify(value), file = path.join(dir, name); await writeFile(file, raw);
        files.push({ path: file, sizeBytes: Buffer.byteLength(raw), sha256: digest(raw) });
        if (name === 'plan.json') input.correctness.e3PlanSha256[kind] = digest(raw);
      }
    }
    const proofManifest = { schema: 'financial-e3-proof-manifest-v1', proofRoot: input.correctnessProofRoot,
      ...Object.fromEntries(['sourceHead', 'candidateSha256', 'fixtureSha256'].map(k => [k, input[k]])),
      buildSha256: input.capability.buildSha256, classpathSha256: input.capability.classpathSha256,
      plannedBootstrapConfigSha256: input.capability.configSha256, arms: input.correctness.arms, e3PlanSha256: input.correctness.e3PlanSha256,
      files: files.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0) };
    const proofRaw = JSON.stringify(proofManifest); await writeFile(path.join(root, 'e3-proof-manifest.json'), proofRaw);
    input.correctness.proofManifestSha256 = digest(proofRaw);
    for (const [kind, value] of [['capability', input.capability], ['review', input.review], ['candidate', candidate]]) {
      const raw = JSON.stringify(value); input[`${kind}EvidencePath`] = `${kind}.json`;
      input[`${kind}EvidenceSha256`] = digest(raw); await writeFile(path.join(root, `${kind}.json`), raw);
    }
    input.correctness.sourceCandidateEvidenceSha256 = input.candidateEvidenceSha256;
    const correctnessRaw = JSON.stringify(Object.fromEntries(Object.entries(input.correctness).filter(([k]) => k !== 'evidenceSha256')));
    input.correctness.evidenceSha256 = digest(correctnessRaw); await writeFile(path.join(root, 'correctness.json'), correctnessRaw);
    const frozen = freezeBootstrap(input), requestPath = path.join(root, 'request.json');
    await writeFile(requestPath, JSON.stringify(frozen));
    const actual = { ...frozen.capability, usedHeapBytes: 32 * 1024 ** 2 };
    assert.equal((await verifyBootstrapEvidence(frozen, requestPath, configPath, actual)).status, 'FROZEN_DIAGNOSTIC');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, { ...actual, buildSha256: 'b'.repeat(64) }), /CAPABILITY_DRIFT/);
    await writeFile(path.join(root, 'correctness.json'), '{}');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, actual), /E3_RAW/);
    await writeFile(path.join(root, 'correctness.json'), correctnessRaw);
    // Rebind every outer hash: semantic stale/forged E3 plans must still refuse.
    const originalCorePlan = await readFile(path.join(input.correctnessProofRoot, 'core/plan.json'));
    const originalCoreResults = await readFile(path.join(input.correctnessProofRoot, 'core/results.json'));
    async function rebound(planRaw, resultsRaw) {
      const planPath = path.join(input.correctnessProofRoot, 'core/plan.json'), resultsPath = path.join(input.correctnessProofRoot, 'core/results.json');
      await writeFile(planPath, planRaw); await writeFile(resultsPath, resultsRaw);
      const manifest = structuredClone(proofManifest);
      for (const row of manifest.files) if ([planPath, resultsPath].includes(row.path)) {
        const bytes = await readFile(row.path); row.sizeBytes = bytes.length; row.sha256 = digest(bytes);
      }
      manifest.e3PlanSha256.core = digest(planRaw);
      const manifestRaw = JSON.stringify(manifest); await writeFile(path.join(root, 'e3-proof-manifest.json'), manifestRaw);
      const modified = structuredClone(input); modified.correctness.e3PlanSha256 = manifest.e3PlanSha256;
      modified.correctness.proofManifestSha256 = digest(manifestRaw);
      const markerRaw = JSON.stringify(Object.fromEntries(Object.entries(modified.correctness).filter(([k]) => k !== 'evidenceSha256')));
      modified.correctness.evidenceSha256 = digest(markerRaw); await writeFile(path.join(root, 'correctness.json'), markerRaw);
      return freezeBootstrap(modified);
    }
    for (const mutate of [p => p.broker = '127.0.0.1:29492,127.0.0.1:29592,127.0.0.1:29692', p => delete p.broker, p => p.classpath = 'old-classpath', p => p.compiledHashes[classPath] = 'b'.repeat(64),
      p => p.sourceHashes['FinancialKernel.kt'] = 'b'.repeat(64), p => p.runnerHashes['scripts/dev/calcify-financial/broker-proof.mjs'] = 'b'.repeat(64)]) {
      const p = JSON.parse(originalCorePlan); mutate(p);
      const stale = await rebound(JSON.stringify(p), originalCoreResults);
      await assert.rejects(verifyBootstrapEvidence(stale, requestPath, configPath, actual), /BOOTSTRAP_E3_SELECTED_PLAN/);
    }
    for (const mutate of [r => r.results.pop(), r => r.results[0].after.pass = false, r => r.results[1].name = r.results[0].name]) {
      const r = JSON.parse(originalCoreResults); mutate(r); const forged = await rebound(originalCorePlan, JSON.stringify(r));
      await assert.rejects(verifyBootstrapEvidence(forged, requestPath, configPath, actual), /BOOTSTRAP_E3_ACTUAL_SUCCESSFUL_ARM_SET/);
    }
    await rebound(originalCorePlan, originalCoreResults);
    await writeFile(classPath, 'changed current compiled artifact');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, actual), /BOOTSTRAP_E3_SELECTED_PLAN_COMPILED/);
    await writeFile(classPath, classRaw);
    await writeFile(path.join(input.correctnessProofRoot, 'unlisted.log'), 'unregistered raw file');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, actual), /BOOTSTRAP_E3_RAW_COMPLETE_SORTED_SET/);
    await rm(path.join(input.correctnessProofRoot, 'unlisted.log'));
    await writeFile(path.join(root, sources[0]), 'mutated source');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, actual), /SOURCE_DRIFT/);
    await writeFile(path.join(root, 'review.json'), '{}');
    await assert.rejects(verifyBootstrapEvidence(frozen, requestPath, configPath, actual), /RAW_EVIDENCE_HASH/);
  } finally { await rm(root, { recursive: true, force: true }); }
});

// Synthetic receipt controls only: no broker/process/heap measurement claimed.
async function measuredControl() {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'bootstrap-result-control-'))), proof = path.join(root, 'proof');
  await mkdir(proof); const input = request(); input.capability.classpathEntries = ['/synthetic/test-classes', '/synthetic/kafka.jar'];
  const r = freezeBootstrap(input), digest = raw => createHash('sha256').update(raw).digest('hex');
  const heap = { schema: 'financial-bootstrap-heap-observation-v1', purpose: BOOTSTRAP.purpose, heapConservativeBound: false,
    guardOutcome: 'PASS_SAMPLED', sampled: true, actualMaxHeapBytes: 805306368, admissionLimitBytes: 644245094,
    estimatedHeapBytes: null, heapPeakBytes: 32000000, pollMillis: 250, maxSampleAgeNanos: 5000000000,
    baselineObservations: [{ stage: 'synthetic', usedHeapBytes: 16000000 }], preloadOperationalCeilingBytes: 268435456 };
  const parity = { ownerSha256: hash, historyChecksum: 'b'.repeat(64), historyRecords: 2101,
    sourceUuid: 'source-uuid', readCommitted: true, independentCompleteDeltas: true };
  const m = { schema: 'financial-bootstrap-calibration-v1', purpose: BOOTSTRAP.purpose, capacityQualification: false,
    heapConservativeBound: false, estimatedHeapBytes: null, bootstrapSha256: r.bootstrapSha256, sourceHead: r.sourceHead,
    fixtureSha256: r.fixtureSha256, candidateSha256: r.candidateSha256, capability: r.capability,
    processId: 101, sampleTrades: 1000, pendingSample: 100, agedIdentities: 0, sourceActions: 2100, historyRecords: 2101,
    final: { offered: 1000, callbackSuccess: 1000, admitted: 1000, decided: 1000, settled: 1000, pending: 0 }, sourceTopicUuid: 'source-uuid',
    heapObservation: structuredClone(heap), parity, managedRestartVerified: true, resultOnlyReplayVerified: true,
    ackJournal: { schema: 'financial-ack-journal-v1', publishedMembers: 2100, recoveredMembers: 0, maxAggregateBytes: 33554432,
      logicalBytes: 1024, dataSha256: hash, publicationSha256: hash, publicationWitnessSha256: hash },
    managedRecovery: { schema: 'financial-bootstrap-process-recovery-v1', bootstrapSha256: r.bootstrapSha256,
      processId: 202, sourceUuid: 'source-uuid', recoveredMembers: 2100, controllerAdmissionsReplayed: 0,
      oldControllerAdmissionTimes: 'unknown', firstFreshResult: null, heapObservation: structuredClone(heap),
      activation: { ownerSha256: parity.ownerSha256, historyChecksum: parity.historyChecksum, historyRecords: 2101, ordinal: 2099, offset: 2500 } },
    resultOnlyReplay: { records: 2101, inputs: 2100, bytes: 6, elapsedMs: 10, committedResultOffsetExclusive: 2501,
      ownerSha256: parity.ownerSha256, historyChecksum: parity.historyChecksum, sourceUuid: 'source-uuid', readCommitted: true, completeCommittedCut: true },
    realRecordEvidencePath: 'encoded-records.bin', encodedInputBytes: 5, encodedResultBytes: 6, encodedBytes: 11,
    realRecordEvidenceSha256: digest('inputresult'), physicalCheckpoints: [] };
  await writeFile(path.join(proof, 'encoded-records.bin'), 'inputresult');
  const recoveryDir = path.join(proof, 'managed-recovery'); await mkdir(recoveryDir);
  m.managedRecoveryEvidencePath = 'managed-recovery/result.json';
  const recoveryRaw = JSON.stringify(m.managedRecovery); await writeFile(path.join(proof, m.managedRecoveryEvidencePath), recoveryRaw);
  m.managedRecoveryEvidenceSha256 = digest(recoveryRaw);
  const commandRaw = JSON.stringify(['/synthetic/jdk21/bin/java', '-Xms128m', '-Xmx768m', '-cp', input.capability.classpathEntries.join(path.delimiter),
    'com.reef.platform.calcify.financial.FinancialRateProbe', 'bootstrap-recover', 'synthetic:9092', 'financial-s1-bootstrap-test',
    path.join(root, 'config.json'), path.join(root, 'request.json'), path.join(recoveryDir, 'request.json'), path.join(recoveryDir, 'result.json')]);
  await writeFile(path.join(recoveryDir, 'command.json'), commandRaw);
  m.managedRecoveryProcess = { processId: 202, parentProcessId: 101, exitCode: 0, newJvm: true,
    commandEvidencePath: 'managed-recovery/command.json', commandEvidenceSha256: digest(commandRaw) };
  const manifest = freezePhysicalManifest({ schema: 'calcify-physical-manifest-v1', runId: 'financial-s1-bootstrap-test', ownedRoot: root,
    provenance: { sourceCommit: r.sourceHead, candidateSha256: r.candidateSha256, configSha256: r.capability.configSha256,
      fixtureSha256: r.fixtureSha256, buildSha256: r.capability.buildSha256, classpathSha256: r.capability.classpathSha256, imageDigest: `sha256:${hash}` },
    registeredResources: { project: 'reef-calcify-session-test', containers: [0, 1, 2].map(brokerId => ({ brokerId,
      id: String(brokerId + 1).repeat(64), imageId: `sha256:${hash}`, labels: { 'com.docker.compose.project': 'reef-calcify-session-test',
        'com.docker.compose.service': `rp${brokerId}`, 'reef.test': 'calcify-financial-sprint1' }, volumes: [{ name: `volume${brokerId}`, destination: '/var/lib/redpanda/data' }] })) },
    topics: [{ name: 'financial-s1-bootstrap-test-input', topicId: 'source-uuid', category: 'source', partitions: [{ partition: 0, replicas: [0, 1, 2] }] }],
    localResources: [{ id: 'proof', category: 'proof', path: proof }], categoryMappings: [] });
  const stages = ['warmed', 'before-settled', 'after-settled', 'after-pending', 'workers-closed', 'after-managed-restart', 'after-result-replay'];
  for (const [i, stage] of stages.entries()) {
    const at = 1000 + i * 100, topics = manifest.topics.map(({ name, topicId, partitions }) => ({ name, topicId, partitions }));
    const rawTopics = { schema: 'calcify-admin-topics-raw-v1', request: { topics: topics.map(t => t.name) }, response: { topics: topics.map(t => ({ ...t, error: null })) } };
    const rawDirs = { schema: 'calcify-admin-log-dirs-raw-v1', request: { brokerIds: [0, 1, 2] }, response: { brokers: [0, 1, 2].map(brokerId => ({ brokerId,
      logDirs: [{ logDir: '/var/lib/redpanda/data', error: null, replicas: [{ topic: topics[0].name, partition: 0, sizeBytes: 100, isFuture: false }] }] })) } };
    const inventory = { schema: 'calcify-replica-inventory-v1', runId: manifest.runId, provenance: manifest.provenance,
      window: { startedAtMs: at, completedAtMs: at + 1, clockScope: 'host-monotonic-ms' }, topics,
      replicas: [0, 1, 2].map(brokerId => ({ brokerId, topic: topics[0].name, topicId: 'source-uuid', partition: 0,
        logDir: '/var/lib/redpanda/data', sizeBytes: 100, error: null })), rawReceipts: [['describeTopics', rawTopics], ['describeLogDirs', rawDirs]].map(([operation, value]) => {
        const rawJSON = JSON.stringify(value); return { operation, rawJSON, bytesSha256: digest(rawJSON), startedAtMs: at, completedAtMs: at + 1 }; }) };
    const artifact = await collectPhysicalEvidence(manifest, { now: () => at + 2, metadataSnapshot: async () => inventory,
      verifyLocalPath: async () => {}, execute: async (_exe, argv) => ({ stdout: argv[0] === 'inspect' ? JSON.stringify(manifest.registeredResources.containers.map(c => ({
        Id: c.id, Image: c.imageId, Config: { Labels: c.labels }, Mounts: c.volumes.map(v => ({ Type: 'volume', Name: v.name, Destination: v.destination, RW: true })),
        State: { Running: true, Status: 'running', Paused: false, Restarting: false, Dead: false } }))) : `${8 - i}\t${argv.at(-1)}\n`, stderr: '', exitCode: 0 }) });
    const descriptor = { stage, artifact, clockReceipt: { verified: true, clockScope: 'host-monotonic-ms', nodeExecutable: '/synthetic/node', adapterScript: '/synthetic/physical-adapter.mjs' } };
    for (const [kind, value] of [['manifest', manifest], ['inventory', inventory], ['artifact', artifact]]) {
      const raw = JSON.stringify(value), file = path.join(proof, `${stage}-${kind}.json`); await writeFile(file, raw);
      descriptor[`${kind}Path`] = file; descriptor[`${kind}Sha256`] = digest(raw);
    }
    descriptor.process = { argv: ['/synthetic/node', '/synthetic/physical-adapter.mjs', '--collect', descriptor.manifestPath, descriptor.inventoryPath, descriptor.artifactPath], exitCode: 0, error: null, stdout: JSON.stringify({ artifact: descriptor.artifactPath }), stderr: '', startedAtMs: at, completedAtMs: at + 3 };
    m.physicalCheckpoints.push(descriptor);
  }
  return { request: r, measured: m, root, proof };
}

test('bootstrap measured validator binds counts recovery heap raw bytes and all physical receipts', async () => {
  const c = await measuredControl();
  try {
    const result = await validateBootstrapResult(c.request, c.measured, c.proof);
    assert.equal(result.status, 'VALIDATED_BOUNDED_DIAGNOSTIC'); assert.equal(result.physicalDeltas.length, 6);
    assert.ok(result.physicalDeltas[0].broker.deltaBytes < 0); assert.equal(result.physicalDeltas[0].categories.source.status, 'unknown');
  } finally { await rm(c.root, { recursive: true, force: true }); }
});

test('bootstrap result refuses scope inflation identity drift incomplete counts and recovered fake timing', async () => {
  const c = await measuredControl();
  try {
    for (const mutate of [m => m.capacityQualification = true, m => m.heapConservativeBound = true, m => m.estimatedHeapBytes = 1,
      m => m.sampleTrades++, m => m.pendingSample++, m => m.agedIdentities++, m => m.sourceActions--, m => m.historyRecords--,
      m => m.bootstrapSha256 = 'b'.repeat(64), m => m.sourceHead = 'b'.repeat(40), m => m.fixtureSha256 = 'b'.repeat(64), m => m.candidateSha256 = 'b'.repeat(64),
      m => m.capability.buildSha256 = 'b'.repeat(64), ...['offered', 'callbackSuccess', 'admitted', 'decided', 'settled'].map(k => m => m.final[k]--), m => m.final.pending = 100,
      m => m.managedRecovery.processId = m.processId, m => m.managedRecovery.recoveredMembers--, m => m.managedRecovery.controllerAdmissionsReplayed++,
      m => m.managedRecovery.oldControllerAdmissionTimes = 'inferred-from-source', m => m.managedRecovery.activation.ownerSha256 = 'b'.repeat(64),
      m => m.resultOnlyReplay.completeCommittedCut = false, m => m.resultOnlyReplay.inputs--, m => m.resultOnlyReplay.committedResultOffsetExclusive = 2099,
      m => m.resultOnlyReplay.historyChecksum = 'a'.repeat(64), m => m.ackJournal.publishedMembers--, m => m.ackJournal.maxAggregateBytes++]) {
      const measured = structuredClone(c.measured); mutate(measured);
      await assert.rejects(validateBootstrapResult(c.request, measured, c.proof), /BOOTSTRAP_RESULT_/);
    }
  } finally { await rm(c.root, { recursive: true, force: true }); }
});

test('primary and recovery sampled heap both require actual pin strict80percent and finite observations', async () => {
  const c = await measuredControl();
  try {
    for (const selector of [m => m.heapObservation, m => m.managedRecovery.heapObservation]) {
      for (const mutate of [h => h.actualMaxHeapBytes++, h => h.admissionLimitBytes++, h => h.heapPeakBytes = 644245094,
        h => h.heapPeakBytes = -1, h => h.sampled = false, h => h.guardOutcome = 'FAILED', h => h.estimatedHeapBytes = 0,
        h => h.pollMillis = 251, h => h.maxSampleAgeNanos++, h => h.baselineObservations = [], h => h.baselineObservations[0].usedHeapBytes = 644245094]) {
        const measured = structuredClone(c.measured); mutate(selector(measured));
        await assert.rejects(validateBootstrapResult(c.request, measured, c.proof), /SAMPLED_HEAP|HEAP_BASELINE/);
      }
    }
  } finally { await rm(c.root, { recursive: true, force: true }); }
});

test('retained encoded recovery and collector raw evidence reject byte hash ownership and checkpoint mutations', async () => {
  const c = await measuredControl();
  try {
    for (const mutate of [m => m.encodedBytes++, m => m.encodedInputBytes--, m => m.realRecordEvidenceSha256 = 'b'.repeat(64),
      m => m.managedRecoveryEvidenceSha256 = 'b'.repeat(64), m => m.managedRecoveryProcess.exitCode = 1, m => m.managedRecoveryProcess.newJvm = false,
      m => m.managedRecoveryProcess.parentProcessId++, m => m.managedRecoveryProcess.commandEvidenceSha256 = 'b'.repeat(64),
      m => m.physicalCheckpoints.pop(), m => m.physicalCheckpoints[1].stage = 'warmed', m => m.physicalCheckpoints[0].artifact.schema = 'fake',
      m => m.physicalCheckpoints[0].artifactSha256 = 'b'.repeat(64), m => m.physicalCheckpoints[0].process.exitCode = 1,
      m => m.physicalCheckpoints[0].process.argv[2] = '--fake', m => m.physicalCheckpoints[0].clockReceipt.verified = false]) {
      const measured = structuredClone(c.measured); mutate(measured); await assert.rejects(validateBootstrapResult(c.request, measured, c.proof));
    }
    const original = await readFile(path.join(c.proof, 'encoded-records.bin'));
    await writeFile(path.join(c.proof, 'encoded-records.bin'), 'mutatresult');
    await assert.rejects(validateBootstrapResult(c.request, c.measured, c.proof), /RAW_ENCODED_HASH/);
    await writeFile(path.join(c.proof, 'encoded-records.bin'), original);
    const foreign = path.join(c.root, 'foreign.bin'); await writeFile(foreign, original);
    const measured = structuredClone(c.measured); measured.realRecordEvidencePath = '../foreign.bin';
    await assert.rejects(validateBootstrapResult(c.request, measured, c.proof), /RAW_PATH_ESCAPE/);
    await symlink(foreign, path.join(c.proof, 'alias.bin')); measured.realRecordEvidencePath = 'alias.bin';
    await assert.rejects(validateBootstrapResult(c.request, measured, c.proof), /RAW_PATH_ESCAPE/);
    const file = c.measured.physicalCheckpoints[0].artifactPath; await writeFile(file, '{}');
    await assert.rejects(validateBootstrapResult(c.request, c.measured, c.proof), /PHYSICAL_RAW_HASH/);
  } finally { await rm(c.root, { recursive: true, force: true }); }
});

test('valid recomputed hashes cannot authorize fake JVM command or inconsistent physical measurements', async () => {
  const c = await measuredControl(), digest = raw => createHash('sha256').update(raw).digest('hex');
  try {
    const commandFile = path.join(c.proof, c.measured.managedRecoveryProcess.commandEvidencePath), original = await readFile(commandFile);
    const command = JSON.parse(original); command[2] = '-Xmx1g';
    const raw = JSON.stringify(command), measured = structuredClone(c.measured);
    await writeFile(commandFile, raw); measured.managedRecoveryProcess.commandEvidenceSha256 = digest(raw);
    await assert.rejects(validateBootstrapResult(c.request, measured, c.proof), /RECOVERY_RAW_COMMAND/);
    await writeFile(commandFile, original);
    const forged = structuredClone(c.measured), checkpoint = forged.physicalCheckpoints[0];
    checkpoint.artifact.logicalReplicaBytes = 999;
    const artifactRaw = JSON.stringify(checkpoint.artifact); await writeFile(checkpoint.artifactPath, artifactRaw);
    checkpoint.artifactSha256 = digest(artifactRaw);
    await assert.rejects(validateBootstrapResult(c.request, forged, c.proof), /PHYSICAL_LOGICAL_SUMMARY_DRIFT/);
  } finally { await rm(c.root, { recursive: true, force: true }); }
});
