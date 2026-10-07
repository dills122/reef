import { createHash } from 'node:crypto';
import { readFile, realpath } from 'node:fs/promises';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { validatePreflight, validateBrokerEndpoints, superviseProof, dockerDependencies, launchOwnedWrapper, EMPIRICAL_DISK_PROFILE, EMPIRICAL_8G_DISK_PROFILE } from './proof-supervisor.mjs';
import { readBoundedJSON } from './physical-adapter.mjs';

export function validateBootstrapRuntime(config, supervisor, output, brokerArgument) {
  const reject = reason => { throw Error(`RATE_RUNTIME_${reason}`); };
  const canonical = p => typeof p === 'string' && path.isAbsolute(p) && path.resolve(p) === p && p !== path.parse(p).root;
  const local = supervisor.hostLocalResources, out = path.resolve(output);
  if (!local || !canonical(config?.ownedRoot) || config.ownedRoot !== local.ownedRoot || !canonical(output)
    || !Array.isArray(config.localResources) || config.localResources.length !== 3
    || !isDeepStrictEqual(config.localResources, local.paths)) reject('LOCAL_REGISTRY_MISMATCH');
  const mapping = { stateDir: 'localStore', ackJournalDir: 'controllerJournal', proofDir: 'proof' };
  for (const [field, category] of Object.entries(mapping)) {
    const rows = config.localResources.filter(r => r.category === category);
    if (rows.length !== 1 || typeof rows[0].id !== 'string' || !rows[0].id || !canonical(config[field])
      || config[field] !== rows[0].path || !config[field].startsWith(config.ownedRoot + path.sep)) reject('DIRECTORY_MAPPING_MISMATCH');
  }
  if (config.proofDir !== out) reject('OUTPUT_PROOF_MISMATCH');
  if (!isDeepStrictEqual(config.registeredResources, supervisor.registeredResources)) reject('BROKER_REGISTRY_MISMATCH');
  let brokers;
  try { brokers = validateBrokerEndpoints(supervisor.registeredResources, true); }
  catch { reject('BROKER_ENDPOINT_PINS_REQUIRED'); }
  const scope = config.brokerScope;
  if (brokerArgument !== supervisor.registeredResources.bootstrapServers
    || scope?.schema !== 'financial-broker-scope-v1' || typeof scope.clusterId !== 'string' || !scope.clusterId.trim()
    || scope.metadataSource !== 'actual AdminClient describeCluster'
    || scope.bootstrapServers !== brokerArgument || !isDeepStrictEqual(scope.brokers, brokers)) reject('BROKER_SCOPE_MISMATCH');
  return config;
}

// v1 binds JSON field order. Preserve archived digests; omit only the digest itself.
export function policyDigest(policy) {
  const unsigned = Object.fromEntries(Object.entries(policy).filter(([key]) => key !== 'policySha256'));
  return createHash('sha256').update(JSON.stringify(unsigned)).digest('hex');
}

export function validateRateResourcePolicy(p, policy) {
  if (p.resourceProfile === undefined && policy.resourceProfile === undefined) return policy;
  if (![EMPIRICAL_DISK_PROFILE.name, EMPIRICAL_8G_DISK_PROFILE.name].includes(p.resourceProfile) || policy.resourceProfile !== p.resourceProfile
    || policy.schema !== 'financial-empirical-timed-policy-v1' || policy.status !== 'FROZEN_EMPIRICAL_DIAGNOSTIC'
    || policy.capacityQualification !== false || policy.heapConservativeBound !== false
    || policy.diskBudgetBytes !== EMPIRICAL_DISK_PROFILE.hardAllocatedBytes
    || p.resourcePolicyBinding?.policySha256 !== policy.policySha256
    || p.resourcePolicyBinding?.diskBudgetBytes !== policy.diskBudgetBytes
    || policy.policySha256 !== policyDigest(policy))
    throw Error('RATE_RESOURCE_POLICY_MISMATCH');
  return policy;
}

async function verifyCanonicalRuntimeDirectories(config) {
  for (const file of [config.ownedRoot, config.stateDir, config.ackJournalDir, config.proofDir]) {
    let existing = file;
    while (true) {
      try { if (await realpath(existing) !== existing) throw Error('RATE_RUNTIME_SYMLINK_OR_ALIAS'); break; }
      catch (error) { if (error.code !== 'ENOENT') throw error; const parent = path.dirname(existing);
        if (parent === existing) throw error; existing = parent; }
    }
  }
}

export function validateRateSupervisor(input, command, args, output) {
  const p = validatePreflight(input), out = path.resolve(output);
  if (!isDeepStrictEqual(p.wrapper.argv, [command, ...args])) throw Error('RATE_SUPERVISOR_COMMAND_MISMATCH');
  if (p.wrapper.outputDir !== path.join(out, 'supervision') || p.wrapper.timeoutMs > 1800000
    || p.externalBrokerFault || !p.hostLocalResources
    || p.hostLocalResources.paths.find(r => r.category === 'proof')?.path !== out)
    throw Error('RATE_SUPERVISOR_ENVELOPE_REQUIRED');
  if (!p.registeredResources.containers.every(c => c.imageId
    && c.volumes?.some(v => v.destination === '/var/lib/redpanda/data')
    && c.labels['reef.test'] === 'calcify-financial-sprint1')) throw Error('RATE_SUPERVISOR_RESOURCE_PINS_REQUIRED');
  return p;
}

/** All live adapter work remains inside existing owned-group/resource supervisor. */
export async function superviseRateAdapter(adapter, adapterPath, args, out, env,
  { dependencies = dockerDependencies, supervise = superviseProof } = {}) {
  if (typeof adapter.supervisorEvidencePath !== 'string'
    || !/^[a-f0-9]{64}$/.test(adapter.supervisorEvidenceSha256 ?? '')) throw Error('RATE_SUPERVISOR_EVIDENCE_REQUIRED');
  const root = await realpath(path.dirname(path.resolve(adapterPath)));
  const manifest = await realpath(path.resolve(root, adapter.supervisorEvidencePath));
  if (!manifest.startsWith(root + path.sep)) throw Error('RATE_SUPERVISOR_EVIDENCE_OUTSIDE_ADAPTER');
  const bytes = await readFile(manifest);
  if (bytes.length > 1024 * 1024 || createHash('sha256').update(bytes).digest('hex') !== adapter.supervisorEvidenceSha256)
    throw Error('RATE_SUPERVISOR_EVIDENCE_HASH_OR_SIZE_MISMATCH');
  const p = validateRateSupervisor(JSON.parse(bytes), adapter.command, args, out);
  if (args[4] === 'com.reef.platform.calcify.financial.FinancialRateProbe'
    && ['run', 'calibrate', 'bootstrap-calibrate', 'diagnostic-run'].includes(args[5])) {
    if (typeof args[8] !== 'string' || !path.isAbsolute(args[8])) throw Error('RATE_RUNTIME_CONFIG_ARGUMENT_REQUIRED');
    const config = validateBootstrapRuntime(await readBoundedJSON(args[8], 1024 * 1024), p, out, args[6]);
    if (args[5] === 'diagnostic-run') {
      const index = args.indexOf('--financial-rate-policy');
      if (index < 0) throw Error('RATE_RESOURCE_POLICY_REQUIRED');
      validateRateResourcePolicy(p, await readBoundedJSON(args[index + 1], 1024 * 1024));
    }
    await verifyCanonicalRuntimeDirectories(config);
  }
  const controller = new AbortController(), abort = () => controller.abort();
  process.once('SIGINT', abort); process.once('SIGTERM', abort);
  let deps;
  try {
    deps = dependencies(p, { signal: controller.signal });
    deps.launch = wrapper => launchOwnedWrapper(wrapper, { env });
    const result = await supervise(p, deps);
    if (!result.ok) throw Error(`RATE_SUPERVISION_FAILED; raw attempt preserved: ${result.error}`);
    return result;
  } finally {
    process.removeListener('SIGINT', abort); process.removeListener('SIGTERM', abort);
    await deps?.close?.();
  }
}
