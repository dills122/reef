import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm, mkdir, realpath, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { validateRateSupervisor, superviseRateAdapter, validateBootstrapRuntime } from './rate-supervision.mjs';

const command = '/pinned/java', args = ['-Xms128m', '-Xmx768m', 'exact-class'];
function preflight() {
  return { schema: 'calcify-proof-supervisor-v1', registeredResources: {
    project: 'reef-calcify-rate-test', containers: [1, 2, 3].map(n => ({
      id: String(n).repeat(64), imageId: `sha256:${'a'.repeat(64)}`,
      volumes: [{ name: `owned-${n}`, destination: '/var/lib/redpanda/data' }],
      labels: { 'com.docker.compose.project': 'reef-calcify-rate-test', 'com.docker.compose.service': `rp${n}`,
        'reef.test': 'calcify-financial-sprint1' },
    })),
  }, hostLocalResources: { ownedRoot: '/tmp/rate-owned', paths: [
    { id: 'store', category: 'localStore', path: '/tmp/rate-owned/store' },
    { id: 'ack', category: 'controllerJournal', path: '/tmp/rate-owned/ack' },
    { id: 'proof', category: 'proof', path: '/tmp/rate-owned/proof' },
  ] }, wrapper: { argv: [command, ...args], cwd: '/repo', outputDir: '/tmp/rate-owned/proof/supervision', timeoutMs: 900000 } };
}
function pinnedPreflight() {
  const p = preflight(); p.registeredResources.bootstrapServers = '127.0.0.1:39492,127.0.0.1:39592,127.0.0.1:39692';
  p.registeredResources.containers.forEach((c, i) => { c.brokerId = i; c.hostKafkaEndpoint = { host: '127.0.0.1', port: 39492 + i * 100, containerPort: `${39492 + i * 100}/tcp` }; });
  return p;
}
function brokerScope(p) {
  return { schema: 'financial-broker-scope-v1', bootstrapServers: p.registeredResources.bootstrapServers,
    clusterId: 'actual-cluster-control', metadataSource: 'actual AdminClient describeCluster',
    brokers: p.registeredResources.containers.map(c => ({ id: c.brokerId, host: c.hostKafkaEndpoint.host, port: c.hostKafkaEndpoint.port })).sort((a, b) => a.id - b.id) };
}
test('rate supervision binds exact command, output, allocation categories and finite timeout', () => {
  assert.equal(validateRateSupervisor(preflight(), command, args, '/tmp/rate-owned/proof').wrapper.timeoutMs, 900000);
  for (const mutate of [
    p => p.wrapper.argv.push('unfrozen'), p => p.wrapper.argv[0] = '/other/java',
    p => p.wrapper.outputDir = '/tmp/rate-owned/proof/other',
    p => p.wrapper.timeoutMs = 1800001, p => delete p.hostLocalResources,
    p => delete p.registeredResources.containers[0].imageId,
    p => delete p.registeredResources.containers[0].volumes,
    p => delete p.registeredResources.containers[0].labels['reef.test'],
    p => p.externalBrokerFault = {},
  ]) { const p = preflight(); mutate(p); assert.throws(() => validateRateSupervisor(p, command, args, '/tmp/rate-owned/proof')); }
});

test('actual financial runtime directories and full registry must match supervised resource identity', () => {
  const p = pinnedPreflight(), config = { brokerScope: brokerScope(p), ownedRoot: p.hostLocalResources.ownedRoot,
    localResources: structuredClone(p.hostLocalResources.paths), registeredResources: structuredClone(p.registeredResources),
    stateDir: '/tmp/rate-owned/store', ackJournalDir: '/tmp/rate-owned/ack', proofDir: '/tmp/rate-owned/proof' };
  assert.equal(validateBootstrapRuntime(config, p, config.proofDir, p.registeredResources.bootstrapServers).stateDir, config.stateDir);
  for (const mutate of [c => c.stateDir = '/tmp/rate-owned/unregistered', c => c.ackJournalDir = '/tmp/foreign-ack',
    c => c.proofDir = '/tmp/rate-owned/other-proof', c => c.ownedRoot = '/tmp/other-root',
    c => c.localResources[0].id = 'other-id', c => c.localResources[0].category = 'proof',
    c => c.localResources[0].path = '/tmp/rate-owned/elsewhere', c => c.localResources.push({ id: 'extra', category: 'localStore', path: '/tmp/rate-owned/extra' }),
    c => c.registeredResources.containers[0].id = 'a'.repeat(64), c => c.registeredResources.project = 'reef-calcify-other',
    c => c.registeredResources.containers[0].imageId = `sha256:${'b'.repeat(64)}`, c => c.registeredResources.containers[0].volumes[0].name = 'unregistered-volume',
    c => c.registeredResources.containers[0].labels['reef.test'] = 'unregistered', c => delete c.localResources]) {
    const changed = structuredClone(config); mutate(changed);
    assert.throws(() => validateBootstrapRuntime(changed, p, config.proofDir, p.registeredResources.bootstrapServers), /RATE_RUNTIME_/);
  }
});

test('foreign bootstrap endpoint cannot borrow same broker IDs and local resource allocations', () => {
  const p = preflight(); p.registeredResources.bootstrapServers = '127.0.0.1:39492,127.0.0.1:39592,127.0.0.1:39692';
  p.registeredResources.containers.forEach((c, i) => { c.brokerId = i; c.hostKafkaEndpoint = { host: '127.0.0.1', port: 39492 + i * 100, containerPort: `${39492 + i * 100}/tcp` }; });
  const config = { ownedRoot: p.hostLocalResources.ownedRoot, localResources: structuredClone(p.hostLocalResources.paths),
    registeredResources: structuredClone(p.registeredResources), stateDir: '/tmp/rate-owned/store', ackJournalDir: '/tmp/rate-owned/ack', proofDir: '/tmp/rate-owned/proof',
    brokerScope: { schema: 'financial-broker-scope-v1', bootstrapServers: p.registeredResources.bootstrapServers, clusterId: 'actual-cluster-control',
      metadataSource: 'actual AdminClient describeCluster', brokers: p.registeredResources.containers.map(c => ({ id: c.brokerId, host: c.hostKafkaEndpoint.host, port: c.hostKafkaEndpoint.port })) } };
  assert.equal(validateBootstrapRuntime(config, p, config.proofDir, p.registeredResources.bootstrapServers).brokerScope.clusterId, 'actual-cluster-control');
  assert.throws(() => validateBootstrapRuntime(config, p, config.proofDir, '127.0.0.1:29492,127.0.0.1:29592,127.0.0.1:29692'), /RATE_RUNTIME_BROKER/);
  for (const mutate of [c => delete c.brokerScope, c => c.brokerScope.clusterId = '',
    c => c.brokerScope.metadataSource = 'declared broker IDs', c => c.brokerScope.schema = 'other',
    c => c.brokerScope.bootstrapServers = '127.0.0.1:29492', c => c.brokerScope.brokers.reverse(),
    c => c.brokerScope.brokers[0].port = 29492, c => c.brokerScope.brokers[0].id = 9]) {
    const changed = structuredClone(config); mutate(changed);
    assert.throws(() => validateBootstrapRuntime(changed, p, config.proofDir, p.registeredResources.bootstrapServers), /RATE_RUNTIME_BROKER/);
  }
  const legacy = preflight(), unpinned = structuredClone(config); unpinned.registeredResources = legacy.registeredResources;
  assert.throws(() => validateBootstrapRuntime(unpinned, legacy, config.proofDir, p.registeredResources.bootstrapServers), /RATE_RUNTIME_BROKER_ENDPOINT_PINS/);
});

test('supervisor evidence mismatch refuses before dependency setup; failed supervision still closes', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-supervision-')));
  try {
    const p = preflight(), out = path.join(root, 'proof');
    p.hostLocalResources.ownedRoot = root;
    for (const r of p.hostLocalResources.paths) r.path = path.join(root, r.category);
    p.hostLocalResources.paths.find(r => r.category === 'proof').path = out;
    p.wrapper.outputDir = path.join(out, 'supervision');
    const bytes = JSON.stringify(p), manifest = path.join(root, 'supervisor.json');
    await writeFile(manifest, bytes);
    const adapter = { command, supervisorEvidencePath: 'supervisor.json',
      supervisorEvidenceSha256: createHash('sha256').update(bytes).digest('hex') };
    let prepared = 0, closed = 0;
    const options = { dependencies: () => { prepared++; return { close: async () => { closed++; } }; },
      supervise: async () => ({ ok: false, error: 'injected stale sample' }) };
    await assert.rejects(superviseRateAdapter({ ...adapter, supervisorEvidenceSha256: 'b'.repeat(64) }, path.join(root, 'adapter.json'), args, out, {}, options), /HASH/);
    assert.equal(prepared, 0);
    await assert.rejects(superviseRateAdapter(adapter, path.join(root, 'adapter.json'), [...args, 'changed'], out, {}, options), /COMMAND/);
    assert.equal(prepared, 0);
    await assert.rejects(superviseRateAdapter(adapter, path.join(root, 'adapter.json'), args, out, {}, options), /stale sample/);
    assert.equal(prepared, 1); assert.equal(closed, 1);
    assert.equal(process.listenerCount('SIGTERM'), 0);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('actual supervised child receives frozen environment through owned launch', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-supervision-child-')));
  try {
    const p = preflight(), out = path.join(root, 'proof'); await mkdir(out);
    p.hostLocalResources.ownedRoot = root;
    for (const r of p.hostLocalResources.paths) r.path = path.join(root, r.category);
    const script = path.join(root, 'child.mjs');
    await writeFile(script, 'if(process.env.JAVA_TOOL_OPTIONS || process.env.PINNED_TEST!=="yes")process.exitCode=2;');
    const childArgs = [script]; p.wrapper.argv = [process.execPath, ...childArgs]; p.wrapper.cwd = root;
    p.hostLocalResources.paths.find(r => r.category === 'proof').path = out;
    p.wrapper.outputDir = path.join(out, 'supervision');
    const bytes = JSON.stringify(p); await writeFile(path.join(root, 'supervisor.json'), bytes);
    const adapter = { command: process.execPath, supervisorEvidencePath: 'supervisor.json',
      supervisorEvidenceSha256: createHash('sha256').update(bytes).digest('hex') };
    let closed = false;
    const result = await superviseRateAdapter(adapter, path.join(root, 'adapter.json'), childArgs, out, { PINNED_TEST: 'yes' }, {
      dependencies: () => ({ close: async () => { closed = true; } }),
      supervise: async (manifest, deps) => {
        await mkdir(manifest.wrapper.outputDir);
        const owned = await deps.launch(manifest.wrapper);
        try {
          const end = Date.now() + 3000;
          while (!owned.status() && Date.now() < end) await new Promise(resolve => setTimeout(resolve, 10));
          assert.equal(owned.status()?.code, 0);
          return { ok: true, wrapperStatus: owned.status() };
        } finally { await owned.stop(); }
      },
    });
    assert.equal(result.ok, true); assert.equal(closed, true);
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('all actual financial modes reject raw unregistered config before dependency setup', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-runtime-')));
  try {
    const out = path.join(root, 'proof'), configPath = path.join(root, 'config.json'); let prepared = 0;
    for (const mode of ['run', 'calibrate', 'bootstrap-calibrate']) {
      const actualArgs = ['-Xms128m', '-Xmx768m', '-cp', '/synthetic/classes', 'com.reef.platform.calcify.financial.FinancialRateProbe', mode, 'unused:9092', 'financial-s1-test', configPath];
      const p = pinnedPreflight(); p.hostLocalResources.ownedRoot = root; p.wrapper.argv = [command, ...actualArgs];
      for (const r of p.hostLocalResources.paths) r.path = path.join(root, r.category);
      p.hostLocalResources.paths.find(r => r.category === 'proof').path = out; p.wrapper.outputDir = path.join(out, 'supervision');
      const bytes = JSON.stringify(p); await writeFile(path.join(root, 'supervisor.json'), bytes);
      const adapter = { command, supervisorEvidencePath: 'supervisor.json', supervisorEvidenceSha256: createHash('sha256').update(bytes).digest('hex') };
      const config = { brokerScope: brokerScope(p), ownedRoot: root, localResources: p.hostLocalResources.paths, registeredResources: p.registeredResources,
        stateDir: path.join(root, 'unregistered'), ackJournalDir: path.join(root, 'controllerJournal'), proofDir: out };
      await writeFile(configPath, JSON.stringify(config));
      const options = { dependencies: () => { prepared++; return { close: async () => {} }; }, supervise: async () => ({ ok: true }) };
      await assert.rejects(superviseRateAdapter(adapter, path.join(root, 'adapter.json'), actualArgs, out, {}, options), /RATE_RUNTIME_DIRECTORY_MAPPING/);
      assert.equal(prepared, 0);
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('all actual financial modes refuse foreign frozen bootstrap argument before dependencies', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-runtime-endpoint-')));
  try {
    const out = path.join(root, 'proof'), configPath = path.join(root, 'config.json'); let prepared = 0;
    for (const mode of ['run', 'calibrate', 'bootstrap-calibrate']) {
      const p = pinnedPreflight(); p.hostLocalResources.ownedRoot = root;
      for (const r of p.hostLocalResources.paths) r.path = path.join(root, r.category);
      p.hostLocalResources.paths.find(r => r.category === 'proof').path = out; p.wrapper.outputDir = path.join(out, 'supervision');
      const actualArgs = ['-Xms128m', '-Xmx768m', '-cp', '/synthetic/classes', 'com.reef.platform.calcify.financial.FinancialRateProbe', mode,
        '127.0.0.1:29492,127.0.0.1:29592,127.0.0.1:29692', 'financial-s1-test', configPath];
      p.wrapper.argv = [command, ...actualArgs];
      const bytes = JSON.stringify(p); await writeFile(path.join(root, 'supervisor.json'), bytes);
      const adapter = { command, supervisorEvidencePath: 'supervisor.json', supervisorEvidenceSha256: createHash('sha256').update(bytes).digest('hex') };
      const config = { brokerScope: brokerScope(p), ownedRoot: root, localResources: p.hostLocalResources.paths, registeredResources: p.registeredResources,
        stateDir: path.join(root, 'localStore'), ackJournalDir: path.join(root, 'controllerJournal'), proofDir: out };
      await writeFile(configPath, JSON.stringify(config));
      const options = { dependencies: () => { prepared++; return { close: async () => {} }; }, supervise: async () => ({ ok: true }) };
      await assert.rejects(superviseRateAdapter(adapter, path.join(root, 'adapter.json'), actualArgs, out, {}, options), /RATE_RUNTIME_BROKER_SCOPE/);
      assert.equal(prepared, 0);
    }
  } finally { await rm(root, { recursive: true, force: true }); }
});

test('actual financial runtime validates planned new paths and refuses symlinked registered directory before launch', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-runtime-canonical-'))), foreign = await realpath(await mkdtemp(path.join(tmpdir(), 'rate-runtime-foreign-')));
  try {
    const out = path.join(root, 'proof'), configPath = path.join(root, 'config.json'); let prepared = 0;
    const actualArgs = ['-Xms128m', '-Xmx768m', '-cp', '/synthetic/classes', 'com.reef.platform.calcify.financial.FinancialRateProbe', 'bootstrap-calibrate', pinnedPreflight().registeredResources.bootstrapServers, 'financial-s1-test', configPath];
    const p = pinnedPreflight(); p.hostLocalResources.ownedRoot = root; p.wrapper.argv = [command, ...actualArgs];
    for (const r of p.hostLocalResources.paths) r.path = path.join(root, r.category);
    p.hostLocalResources.paths.find(r => r.category === 'proof').path = out; p.wrapper.outputDir = path.join(out, 'supervision');
    const bytes = JSON.stringify(p); await writeFile(path.join(root, 'supervisor.json'), bytes);
    const adapter = { command, supervisorEvidencePath: 'supervisor.json', supervisorEvidenceSha256: createHash('sha256').update(bytes).digest('hex') };
    const config = { brokerScope: brokerScope(p), ownedRoot: root, localResources: p.hostLocalResources.paths, registeredResources: p.registeredResources,
      stateDir: path.join(root, 'localStore'), ackJournalDir: path.join(root, 'controllerJournal'), proofDir: out };
    await writeFile(configPath, JSON.stringify(config));
    const options = { dependencies: () => { prepared++; return { close: async () => {} }; }, supervise: async () => ({ ok: true }) };
    assert.equal((await superviseRateAdapter(adapter, path.join(root, 'adapter.json'), actualArgs, out, {}, options)).ok, true);
    assert.equal(prepared, 1); await symlink(foreign, config.stateDir);
    await assert.rejects(superviseRateAdapter(adapter, path.join(root, 'adapter.json'), actualArgs, out, {}, options), /RATE_RUNTIME_SYMLINK_OR_ALIAS/);
    assert.equal(prepared, 1);
  } finally { await rm(root, { recursive: true, force: true }); await rm(foreign, { recursive: true, force: true }); }
});


test('empirical resource profile must match frozen policy hash and hard budget', async () => {
  const { validateRateResourcePolicy } = await import('./rate-supervision.mjs');
  const policy = { schema: 'financial-empirical-timed-policy-v1', status: 'FROZEN_EMPIRICAL_DIAGNOSTIC',
    resourceProfile: 'financial-empirical-disk16-v1', capacityQualification: false, heapConservativeBound: false,
    diskBudgetBytes: 16 * 1024 ** 3 };
  policy.policySha256 = createHash('sha256').update(JSON.stringify(policy)).digest('hex');
  const p = { resourceProfile: policy.resourceProfile, resourcePolicyBinding: { resourceProfile: policy.resourceProfile,
    diskBudgetBytes: policy.diskBudgetBytes, policySha256: policy.policySha256 } };
  assert.equal(validateRateResourcePolicy(p, policy), policy);
  const eight = { ...policy, resourceProfile: 'financial-empirical-heap8-disk16-v1' }; delete eight.policySha256;
  eight.policySha256 = createHash('sha256').update(JSON.stringify(eight)).digest('hex');
  const eightSupervisor = { resourceProfile: eight.resourceProfile, resourcePolicyBinding: { resourceProfile: eight.resourceProfile,
    diskBudgetBytes: eight.diskBudgetBytes, policySha256: eight.policySha256 } };
  assert.equal(validateRateResourcePolicy(eightSupervisor, eight), eight);
  assert.throws(() => validateRateResourcePolicy(p, eight), /RESOURCE_POLICY_MISMATCH/);
  for (const changed of [{ ...policy, resourceProfile: undefined }, { ...policy, diskBudgetBytes: 10 * 1024 ** 3 },
    { ...policy, policySha256: 'f'.repeat(64) }, { ...policy, capacityQualification: true }])
    assert.throws(() => validateRateResourcePolicy(p, changed), /RESOURCE_POLICY_MISMATCH/);
  assert.throws(() => validateRateResourcePolicy({}, policy), /RESOURCE_POLICY_MISMATCH/);
  assert.throws(() => validateRateResourcePolicy(p, {}), /RESOURCE_POLICY_MISMATCH/);
  assert.deepEqual(validateRateResourcePolicy({}, {}), {});
});

test('producer policies survive clone, JSON round-trip and digest-field relocation for both resource profiles', async () => {
  const { freezeDiagnostic } = await import('./rate-proof.mjs');
  const { validateRateResourcePolicy } = await import('./rate-supervision.mjs');
  const digest = 'a'.repeat(64), sourceHead = 'b'.repeat(40);
  for (const [resourceProfile, gib] of [['financial-empirical-disk16-v1', 4], ['financial-empirical-heap8-disk16-v1', 8]]) {
    const policy = freezeDiagnostic({ sourceHead, fixtureSha256: digest, sourceRoot: '/tmp/source', resourceProfile,
      capability: { schema: 'financial-heap-capability-v1', sourceHead, fixtureSha256: digest, configSha256: digest,
        buildSha256: digest, classpathSha256: digest, maxHeapBytes: gib * 1024 ** 3, usedHeapBytes: 0,
        vmArguments: ['-Xms128m', `-Xmx${gib}g`], javaVersion: '21.0.8', javaVendor: 'Oracle Corporation' },
      correctness: { result: 'PASS', evidenceSha256: digest, kernelSha256: digest, managedAdapterSha256: digest },
      runtimeProofHashes: { kernelSha256: digest, managedAdapterSha256: digest },
      fixtureEvidencePath: 'fixture.json', capabilityEvidencePath: 'capability.json', capabilityEvidenceSha256: digest,
      correctnessEvidencePath: 'correctness.json', policySha256: 'stale input digest' });
    const p = { resourceProfile, resourcePolicyBinding: { policySha256: policy.policySha256, diskBudgetBytes: policy.diskBudgetBytes } };
    const { policySha256, ...unsigned } = policy;
    // Independent legacy calculation protects already archived v1 policy bindings.
    assert.equal(policySha256, createHash('sha256').update(JSON.stringify(unsigned)).digest('hex'));
    for (const roundTrip of [structuredClone(policy), JSON.parse(`${JSON.stringify(policy, null, 2)}\n`),
      { policySha256, ...unsigned }]) {
      assert.equal(validateRateResourcePolicy(p, roundTrip), roundTrip);
      assert.deepEqual(freezeDiagnostic(roundTrip), policy);
    }
    assert.throws(() => validateRateResourcePolicy(p, { ...policy, capability: { ...policy.capability, configSha256: 'c'.repeat(64) } }), /POLICY_MISMATCH/);
    assert.throws(() => validateRateResourcePolicy(p, { ...policy, expectedTimedTrades: 1 }), /POLICY_MISMATCH/);
    assert.throws(() => validateRateResourcePolicy(p, { ...policy, policySha256: 'c'.repeat(64) }), /POLICY_MISMATCH/);
  }
});
