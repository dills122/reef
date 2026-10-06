// Bounded correctness proofs only. Samples are not continuous peak measurement.
// CLI: node proof-supervisor.mjs --preflight /absolute/frozen-supervisor.json
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdir, open, readFile, readdir, lstat, writeFile, realpath } from 'node:fs/promises';
import { openSync, closeSync, renameSync } from 'node:fs';
import { isAbsolute, join, resolve, sep } from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import {FAULT_ACTIONS,validatePhaseProtocol,assertPhaseClock,hostMonotonicMs,readPhaseRequests,publishPhaseArtifact} from './lib/external-faults.mjs';

const exec = promisify(execFile);
const GiB = 1024 ** 3;
export const BUDGETS = Object.freeze({ abortAllocatedBytes: 9 * GiB, hardAllocatedBytes: 10 * GiB,
  guestFreeBytesFloor: 20 * GiB, rawProofBytes: 256 * 1024 ** 2, sampleIntervalMs: 5000 });
export const EMPIRICAL_DISK_PROFILE = Object.freeze({ name: 'financial-empirical-disk16-v1',
  abortAllocatedBytes: 14 * GiB, hardAllocatedBytes: 16 * GiB });
export const EMPIRICAL_8G_DISK_PROFILE = Object.freeze({ ...EMPIRICAL_DISK_PROFILE, name: 'financial-empirical-heap8-disk16-v1' });
export function resourceBudgets(p) {
  if (p.resourceProfile === undefined) {
    requireValue(p.resourcePolicyBinding === undefined, 'resource policy binding requires named profile');
    return BUDGETS;
  }
  const a = p.wrapper?.argv, b = p.resourcePolicyBinding;
  const selected = p.resourceProfile === EMPIRICAL_8G_DISK_PROFILE.name ? EMPIRICAL_8G_DISK_PROFILE : EMPIRICAL_DISK_PROFILE;
  requireValue(p.resourceProfile === selected.name && !p.externalBrokerFault
    && p.budgets === undefined && p.abortAllocatedBytes === undefined && p.hardAllocatedBytes === undefined
    && p.hostLocalResources?.paths?.length === 3
    && Array.isArray(a) && a.length === 14
    && a[0] === '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java'
    && a[1] === '-Xms128m' && a[2] === (selected === EMPIRICAL_8G_DISK_PROFILE ? '-Xmx8g' : '-Xmx4g') && a[3] === '-cp' && a[4]
    && a[5] === 'com.reef.platform.calcify.financial.FinancialRateProbe' && a[6] === 'diagnostic-run'
    && a[8].startsWith('financial-s1-') && isAbsolute(a[9])
    && a[10] === '--financial-rate-policy' && isAbsolute(a[11])
    && a[12] === '--financial-rate-measurement' && isAbsolute(a[13])
    && b?.resourceProfile === p.resourceProfile && b.diskBudgetBytes === EMPIRICAL_DISK_PROFILE.hardAllocatedBytes
    && /^[a-f0-9]{64}$/.test(b.policySha256 ?? ''), 'unsupported empirical resource profile or policy binding');
  return { ...BUDGETS, ...selected };
}
const SAMPLE_LEAD_MS = 200; // start early; strict five-second heartbeat has no grace
const RECOVERY_SAMPLE_INTERVAL_MS = 1000; // scheduling target; bounded monitor latency may exceed one second
const SCOPE = '5-second maximum sample-start/freshness window (200ms early start); RECOVERING-only nominal1-second cadence; allocated broker-directory/guest-free/raw-output samples; VM overhead excluded; not continuous peak';
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
function requireValue(condition, reason) { if (!condition) throw new Error(reason); }
function bytes(value, label) {
  requireValue(typeof value === 'number' && Number.isSafeInteger(value) && value >= 0, `invalid ${label}`);
  return value;
}
function freeze(value) {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
}
function sameLabels(expected, actual) {
  return actual && Object.entries(expected).every(([key, value]) => actual[key] === value);
}

// Legacy E3 profiles may omit endpoint pins. Declared pins use this session's
// strict single-binding loopback profile and cannot be inferred from broker IDs.
export function validateBrokerEndpoints(registry, required = false) {
  const declared = registry?.bootstrapServers !== undefined
    || registry?.containers?.some(c => c.hostKafkaEndpoint !== undefined);
  if (!declared && !required) return null;
  requireValue(Array.isArray(registry?.containers) && registry.containers.length === 3, 'Kafka endpoint registry requires three brokers');
  const brokerIds = new Set(), ports = new Set();
  const brokers = registry.containers.map(c => {
    const endpoint = c.hostKafkaEndpoint;
    requireValue(Number.isSafeInteger(c.brokerId) && c.brokerId >= 0 && !brokerIds.has(c.brokerId)
      && endpoint && endpoint.host === '127.0.0.1' && Number.isSafeInteger(endpoint.port)
      && endpoint.port > 0 && endpoint.port <= 65535 && !ports.has(endpoint.port)
      && endpoint.containerPort === `${endpoint.port}/tcp`, 'Kafka endpoint identity/loopback/port mismatch');
    brokerIds.add(c.brokerId); ports.add(endpoint.port);
    return { id: c.brokerId, host: endpoint.host, port: endpoint.port };
  }).sort((a, b) => a.id - b.id);
  requireValue(registry.bootstrapServers === brokers.map(b => `${b.host}:${b.port}`).join(','), 'Kafka endpoint bootstrap literal mismatch');
  return brokers;
}

function validateInspectedBrokerEndpoints(registry, rawInspection) {
  if (!validateBrokerEndpoints(registry)) return;
  requireValue(Array.isArray(rawInspection) && rawInspection.length === 3
    && new Set(rawInspection.map(row => row.Id)).size === 3, 'Kafka endpoint raw inspection missing/different');
  for (const c of registry.containers) {
    const ports = rawInspection.find(row => row.Id === c.id)?.NetworkSettings?.Ports;
    const endpoint = c.hostKafkaEndpoint, bindings = ports?.[endpoint.containerPort];
    requireValue(ports && Object.keys(ports).length === 1 && Array.isArray(bindings) && bindings.length === 1
      && bindings[0]?.HostIp === endpoint.host && bindings[0]?.HostPort === String(endpoint.port)
      && Object.keys(bindings[0]).sort().join(',') === 'HostIp,HostPort', 'Kafka endpoint actual Docker binding mismatch');
  }
}

// This manifest is separate from the broker profile/preflight. Container IDs are
// full Docker IDs; labels pin Compose project/service. Optional imageId/volumes
// pin image identity and named data mounts. Only these IDs can ever be stopped.
export function validatePreflight(input) {
  const p = structuredClone(input), r = p.registeredResources, w = p.wrapper;
  resourceBudgets(p);
  requireValue(p.schema === 'calcify-proof-supervisor-v1', 'unsupported supervisor schema');
  requireValue(r && typeof r.project === 'string' && /^reef-calcify-[a-z0-9-]+$/.test(r.project), 'unregistered project');
  requireValue(Array.isArray(r.containers) && r.containers.length === 3, 'exactly three registered containers required');
  const ids = new Set(), services = new Set();
  for (const c of r.containers) {
    requireValue(typeof c.id === 'string' && /^[a-f0-9]{64}$/.test(c.id) && !ids.has(c.id), 'invalid/duplicate container ID');
    requireValue(c.labels && c.labels['com.docker.compose.project'] === r.project &&
      typeof c.labels['com.docker.compose.service'] === 'string' && c.labels['com.docker.compose.service'].length > 0,
    'container labels outside registered project');
    requireValue(Object.entries(c.labels).every(([k, v]) => k.length > 0 && typeof v === 'string'), 'invalid labels');
    requireValue(!services.has(c.labels['com.docker.compose.service']), 'duplicate broker service');
    if (c.imageId !== undefined) requireValue(/^sha256:[a-f0-9]{64}$/.test(c.imageId), 'invalid image ID');
    if (c.volumes !== undefined) requireValue(Array.isArray(c.volumes) && c.volumes.length > 0 && c.volumes.every(v =>
      typeof v.name === 'string' && v.name.length > 0 && typeof v.destination === 'string' && isAbsolute(v.destination)), 'invalid registered volumes');
    ids.add(c.id); services.add(c.labels['com.docker.compose.service']);
  }
  validateBrokerEndpoints(r);
  requireValue(w && Array.isArray(w.argv) && w.argv.length > 0 && w.argv.every(a => typeof a === 'string' && !a.includes('\0')) &&
    isAbsolute(w.argv[0]), 'wrapper requires exact absolute executable argv');
  requireValue(typeof w.cwd === 'string' && isAbsolute(w.cwd) && typeof w.outputDir === 'string' &&
    isAbsolute(w.outputDir) && resolve(w.outputDir) !== resolve(w.cwd), 'wrapper requires absolute cwd and unique output directory');
  requireValue(Number.isSafeInteger(w.timeoutMs) && w.timeoutMs > 0, 'invalid wrapper timeout');
  if (p.hostLocalResources) {
    const local = p.hostLocalResources, categories = new Set(), localIds = new Set();
    requireValue(typeof local.ownedRoot === 'string' && isAbsolute(local.ownedRoot)
      && resolve(local.ownedRoot) === local.ownedRoot, 'host resources require canonical owned root');
    requireValue(Array.isArray(local.paths) && local.paths.length === 3, 'three host resource categories required');
    for (const resource of local.paths) {
      requireValue(typeof resource.id === 'string' && resource.id.length > 0 && !localIds.has(resource.id), 'invalid host resource ID');
      requireValue(['localStore', 'controllerJournal', 'proof'].includes(resource.category)
        && !categories.has(resource.category), 'invalid host resource category');
      requireValue(typeof resource.path === 'string' && isAbsolute(resource.path) && resolve(resource.path) === resource.path
        && resource.path.startsWith(local.ownedRoot + sep), 'host resource outside owned root');
      requireValue(!local.paths.some(other => other !== resource && (other.path === resource.path
        || other.path?.startsWith(resource.path + sep) || resource.path.startsWith(other.path + sep))), 'overlapping host resources');
      categories.add(resource.category); localIds.add(resource.id);
    }
    const proofPath = local.paths.find(r => r.category === 'proof').path;
    requireValue(w.outputDir === proofPath || w.outputDir.startsWith(proofPath + sep), 'proof allocation path differs from wrapper output');
  }
  if (p.externalBrokerFault) {
    const f = p.externalBrokerFault;
    requireValue(ids.has(f.targetContainer), 'external fault target outside registry');
    requireValue(bytes(f.stoppedAllocatedBytesUpperBound, 'stopped bound') > 0 &&
      f.stoppedAllocatedBytesUpperBound < BUDGETS.abortAllocatedBytes, 'invalid stopped-state reservation');
    requireValue(typeof f.evidence === 'string' && f.evidence.trim().length > 0, 'stopped bound requires evidence/exclusive-writer assumption');
    if (f.composeProject !== undefined) requireValue(f.composeProject === r.project, 'fault project outside registry');
    if (f.containers !== undefined) requireValue(Array.isArray(f.containers) && f.containers.length === 3 &&
      new Set(f.containers).size === 3 && f.containers.every(id => ids.has(id)), 'fault containers outside registry');
    {
      const protocol=validatePhaseProtocol(f,w.outputDir);
      const phaseFlags=w.argv.flatMap((arg,index)=>arg==='--fault-phase-directory'?[index]:[]);
      requireValue(phaseFlags.length===1&&w.argv[phaseFlags[0]+1]===protocol.directory,
        'frozen wrapper argv requires one adjacent literal phase flag/directory pair');
      requireValue(f.stoppedAllocatedBytesUpperBound===3*GiB,'fault protocol requires fixed3GiB charge');
      requireValue(f.composeProject===r.project&&Array.isArray(f.containers)&&f.containers.length===3&&
        new Set(f.containers).size===3&&f.containers.every(id=>ids.has(id)),'phase fault requires exact project/ID registration');
      requireValue(r.containers.every(c=>c.imageId&&c.volumes?.some(v=>v.destination==='/var/lib/redpanda/data')&&
        c.labels['reef.test']==='calcify-financial-sprint1'),'fault protocol requires image/data volume/test label pins');
      if(f.resources)requireValue(Array.isArray(f.resources)&&f.resources.length===3&&r.containers.every(c=>{
        const other=f.resources.find(resource=>resource.id===c.id);return other&&sameLabels(c.labels,other.labels)&&
          other.imageId===c.imageId&&other.volumes?.length===c.volumes.length&&c.volumes.every(v=>other.volumes.some(w=>w.name===v.name&&w.destination===v.destination));
      }),'wrapper/supervisor resource metadata disagree');
    }
  }
  return freeze(p);
}

export function validateSample(p, sample, nowMs) {
  const budgets = resourceBudgets(p);
  requireValue(sample && typeof sample.sampledAtMs === 'number' && Number.isFinite(sample.sampledAtMs) &&
    sample.sampledAtMs >= 0 && nowMs >= sample.sampledAtMs && nowMs - sample.sampledAtMs <= BUDGETS.sampleIntervalMs,
  'observer heartbeat stale/invalid');
  requireValue(Array.isArray(sample.containers) && sample.containers.length === 3 &&
    new Set(sample.containers.map(c => c.id)).size === 3, 'observer resource set differs from registry');
  validateInspectedBrokerEndpoints(p.registeredResources, sample.rawInspection);
  let total = 0, healthy = 0;
  for (const expected of p.registeredResources.containers) {
    const c = sample.containers.find(c => c.id === expected.id);
    requireValue(c && sameLabels(expected.labels, c.labels), 'observer container identity/labels mismatch');
    const allocated = bytes(c.allocatedBytes, 'container allocation');
    if (c.running === true) {
      const protocolTarget=p.externalBrokerFault?.phaseProtocol&&p.externalBrokerFault.targetContainer===c.id;
      const recovering=protocolTarget&&sample.fault?.phase==='RECOVERING'&&nowMs<sample.fault.recoveryDeadlineMs;
      requireValue((c.healthy===true||(recovering&&c.lifecycle?.Health?.Status==='starting'))&&
        c.allocationMode===(protocolTarget?'reserved-fault-target':'measured'),'running broker unhealthy/unmeasured');
      if(c.healthy)healthy++;
      if(protocolTarget)requireValue(allocated===3*GiB&&bytes(c.measuredAllocatedBytes,'target actual allocation')<=3*GiB,'fault target exceeds fixed reservation');
      else if (p.externalBrokerFault?.targetContainer === c.id) requireValue(allocated <= p.externalBrokerFault.stoppedAllocatedBytesUpperBound,
        'fault target exceeds frozen stopped-state reservation');
    } else {
      requireValue(c.running === false && p.externalBrokerFault?.targetContainer === c.id &&
        c.allocationMode === 'conservative-stopped-bound' && allocated === p.externalBrokerFault.stoppedAllocatedBytesUpperBound,
      'unregistered stopped broker or invalid stopped-state accounting');
    }
    total += allocated;
  }
  requireValue(healthy >= 2, 'at least two healthy running brokers required');
  const brokerAllocatedBytes = total;
  if (p.hostLocalResources) {
    const rows = sample.hostLocalAllocations;
    requireValue(Array.isArray(rows) && rows.length === 3 && new Set(rows.map(r => r.id)).size === 3, 'host allocation set missing/different');
    for (const expected of p.hostLocalResources.paths) {
      const row = rows.find(r => r.id === expected.id);
      requireValue(row && row.path === expected.path && row.category === expected.category
        && row.allocationMode === 'du-allocated', 'host allocation identity/mode mismatch');
      total += bytes(row.allocatedBytes, 'host allocation');
    }
  }
  bytes(total, 'total allocation'); bytes(sample.guestFreeBytes, 'guest free bytes'); bytes(sample.rawProofBytes, 'raw proof bytes');
  requireValue(total < budgets.hardAllocatedBytes, p.resourceProfile ? 'hard 16GiB empirical allocation budget' : 'hard 10GiB allocation budget');
  requireValue(total < budgets.abortAllocatedBytes, p.resourceProfile ? '14GiB empirical allocation abort threshold' : '9GiB allocation abort threshold');
  requireValue(sample.guestFreeBytes >= BUDGETS.guestFreeBytesFloor, 'guest free below 20GiB');
  requireValue(sample.rawProofBytes < BUDGETS.rawProofBytes, 'raw proof reached 256MiB');
  return { ...sample, brokerAllocatedBytes, hostAllocatedBytes: total - brokerAllocatedBytes,
    projectAllocatedBytes: total, scope: SCOPE + (p.hostLocalResources ? '; registered host store/journal/proof allocation included' : '') };
}

export async function measureHostAllocations(registry, execute = exec) {
  requireValue(await realpath(registry.ownedRoot) === registry.ownedRoot, 'host owned root symlink/noncanonical');
  return Promise.all(registry.paths.map(async resource => {
    requireValue(await realpath(resource.path) === resource.path && (await lstat(resource.path)).isDirectory(), 'host resource missing/symlink/non-directory');
    const argv = ['-sk', resource.path];
    const { stdout } = await execute('/usr/bin/du', argv, { timeout: 1500, maxBuffer: 1024 * 1024, killSignal: 'SIGKILL' });
    const match = /^(\d+)\s+(.+?)\s*$/.exec(stdout);
    requireValue(match && match[2] === resource.path, 'invalid host allocated du output');
    return { ...resource, allocatedBytes: bytes(Number(match[1]) * 1024, 'host du allocation'),
      allocationMode: 'du-allocated', receipt: { command: '/usr/bin/du', argv, stdout, units: 'KiB allocated blocks' } };
  }));
}

// Transitions consume only fresh daemon rows; cached sample validation is pure.
export function createFaultCycle(p,startedAtMs=0) {
  const f=p.externalBrokerFault,protocol=f.phaseProtocol;
  let phase='HEALTHY',seq=0,stopDeadlineMs=null,recoveryDeadlineMs=null,targetGeneration=null,runningSeen=false;
  let generations=null,recoveredPending=false;const requests=new Map(),acks=new Map();
  const snapshot=()=>({phase,seq,stopDeadlineMs,recoveryDeadlineMs,targetGeneration,generations});
  const checkDeadline=now=>{
    if(phase==='STOP_REQUESTED')requireValue(now<stopDeadlineMs,'STOP transition deadline exceeded');
    if(phase==='RECOVERING'||recoveredPending||phase==='RECOVERED'&&!acks.has(4))requireValue(now<recoveryDeadlineMs,'fixed recovery deadline exceeded');
  };
  function accept(sample,now) {
    checkDeadline(now);const observed={};
    for(const c of sample.containers) {
      const s=c.lifecycle;requireValue(s&&s.Paused===false&&s.Restarting===false&&s.Dead===false,'broker paused/restarting/dead/unknown');
      requireValue(Number.isSafeInteger(c.restartCount)&&c.restartCount>=0&&Number.isFinite(Date.parse(s.StartedAt)),'missing broker generation');
      observed[c.id]={startedAt:s.StartedAt,restartCount:c.restartCount};
      const target=c.id===f.targetContainer;
      if(!target||phase==='HEALTHY'||phase==='RECOVERED')requireValue(s.Running===true&&s.Status==='running'&&s.Health?.Status==='healthy','broker must be healthy/running');
      if(generations&&(!target||phase!=='RECOVERING'&&phase!=='RECOVERED'))requireValue(JSON.stringify(observed[c.id])===JSON.stringify(generations[c.id]),'broker generation drift');
      if(!target)continue;
      if(phase==='STOP_REQUESTED') {
        if(s.Running)requireValue(s.Status==='running'&&s.Health?.Status==='healthy','target unhealthy before STOP');
        else {requireValue(s.Status==='exited','target STOP not observed');phase='STOPPED';}
      } else if(phase==='STOPPED')requireValue(!s.Running&&s.Status==='exited','target restarted without START authorization');
      else if(phase==='RECOVERING') {
        if(!s.Running)requireValue(!runningSeen&&s.Status==='exited'&&s.StartedAt===generations[c.id].startedAt,'target stopped after new running generation');
        else {
          requireValue(s.Status==='running'&&['starting','healthy'].includes(s.Health?.Status),'target recovery health/lifecycle invalid');
          requireValue(s.StartedAt!==generations[c.id].startedAt&&Date.parse(s.StartedAt)>=Date.parse(generations[c.id].startedAt)&&
            c.restartCount===generations[c.id].restartCount,'target recovery generation invalid');
          if(targetGeneration)requireValue(s.StartedAt===targetGeneration,'second target generation');else targetGeneration=s.StartedAt;
          runningSeen=true;
          if(s.Health.Status==='healthy') {requireProbe(s);phase='RECOVERED';recoveredPending=true;}
        }
      } else if(phase==='RECOVERED') {
        requireValue(s.StartedAt===targetGeneration&&c.restartCount===generations[c.id].restartCount,'recovered target generation drift');requireProbe(s);
      }
    }
    if(!generations)generations=observed;
  }
  function requireProbe(s) {requireValue(s.Health.Log?.some(log=>log.ExitCode===0&&Date.parse(log.Start)>=Date.parse(s.StartedAt)&&Date.parse(log.End)>=Date.parse(log.Start)),
    'new target generation lacks successful health probe');}
  return {snapshot,checkDeadline,accept,commitObservation:now=>{checkDeadline(now);recoveredPending=false;},
    commitAck:ack=>acks.set(ack.seq,JSON.stringify(ack)),verifyArtifacts:rows=>{
      requireValue(rows.length>=seq,'consumed phase request disappeared');
      requireValue(rows.acknowledgements.length===acks.size,'unexpected/missing phase ACK');
      for(const ack of rows.acknowledgements)requireValue(acks.get(ack.seq)===JSON.stringify(ack),'phase ACK changed/replayed');
    },
    request:request=>{
      requireValue(request&&request.schema===protocol.schema&&request.runId===protocol.runId&&request.cycle===1&&
        request.targetContainer===f.targetContainer&&Number.isSafeInteger(request.seq)&&request.seq>=1&&request.seq<=4&&
        request.action===FAULT_ACTIONS[request.seq-1]&&Number.isFinite(request.issuedAtMs),'malformed/wrong-run phase request');
      requireValue(Object.keys(request).sort().join(',')==='action,cycle,issuedAtMs,runId,schema,seq,targetContainer','unexpected phase request fields');
      const encoded=JSON.stringify(request);
      if(requests.has(request.seq)){requireValue(requests.get(request.seq)===encoded,'consumed phase request mutated');return false;}
      requireValue(request.seq===seq+1,'skipped/replayed phase transition');return true;
    },authorize:(request,now)=>{
      checkDeadline(now);requireValue(request.issuedAtMs>=startedAtMs&&request.issuedAtMs<=now&&now-request.issuedAtMs<=protocol.stopTimeoutMs,'stale/future phase request');
      const required=['HEALTHY','STOPPED','STOPPED','RECOVERED'][request.seq-1];requireValue(phase===required,'fault transition barrier not observed');
      if(request.seq===1){phase='STOP_REQUESTED';stopDeadlineMs=now+protocol.stopTimeoutMs;}
      if(request.seq===3){phase='RECOVERING';recoveryDeadlineMs=now+protocol.recoveryTimeoutMs;}
      seq=request.seq;requests.set(seq,JSON.stringify(request));
      return {...request,authorized:true,grantedAtMs:now,...snapshot()};
    },complete:()=>requireValue(phase==='HEALTHY'&&seq===0||phase==='RECOVERED'&&seq===4,'wrapper exited during incomplete fault cycle'),
  };
}

async function bounded(operation, timeoutMs, label) {
  let timer; const controller = new AbortController(), deadlineMs = performance.now() + timeoutMs;
  try { return await Promise.race([Promise.resolve().then(() => operation(controller.signal, deadlineMs)), new Promise((_, reject) => {
    timer = setTimeout(() => { controller.abort(); reject(new Error(`${label} timeout/stale observer`)); }, timeoutMs);
  })]); } finally { clearTimeout(timer); }
}

// Inject monitor/process/clock functions for control tests. A hanging async
// monitor cannot suspend supervision; production Docker commands also time out.
export async function superviseProof(input, deps) {
  const p = validatePreflight(input), now = deps.now ?? hostMonotonicMs, sleep = deps.sleep ?? wait;
  const started=now(),fault=p.externalBrokerFault?.phaseProtocol?createFaultCycle(p,started):null;
  let owned, failure, lastSample, wrapperStatus, lastPhaseRequests,outputReady = false;
  const sampleDeadline=()=>lastSample.sampledAtMs+(fault?.snapshot().phase==='RECOVERING'?RECOVERY_SAMPLE_INTERVAL_MS:BUDGETS.sampleIntervalMs);
  const cleanupErrors = [];
  const record = row => bounded(() => deps.record({ ...row, utc: new Date().toISOString() }),
    deps.recordTimeoutMs ?? 1000, 'sample evidence write');
  async function observe() {
    let row;
    try {
    row = await bounded(() => deps.monitor(p,fault?.snapshot()), deps.sampleTimeoutMs ?? 4500, 'resource sample');
    lastSample = validateSample(p, row, now());fault?.accept(lastSample,now());
    if(fault)lastSample.fault=fault.snapshot();await record(lastSample);
    validateSample(p, lastSample, now()); // persistence time belongs to heartbeat age
    fault?.commitObservation(now());
    } catch(error) {error.observation??=row?{rawInspection:row.rawInspection??null,sample:row,fault:fault?.snapshot()}:deps.observation?.();throw error;}
  }
  try {
    await deps.prepare(p); outputReady = true;
    await observe();
    if(fault)await observe(); // actual prelaunch all-healthy observation
    requireValue(!deps.signal?.aborted, 'supervisor interrupted before launch');
    validateSample(p, lastSample, now());
    owned = await deps.launch(p.wrapper);
    let nextSample = sampleDeadline();
    for (;;) {
      if (deps.signal?.aborted) throw new Error('supervisor interrupted');
      requireValue(now() - started < p.wrapper.timeoutMs, 'wrapper deadline exceeded');
      validateSample(p, lastSample, now()); // missed sample deadline aborts before further work
      fault?.checkDeadline(now());
      if(fault) {
        const requests=await bounded(()=>deps.readPhaseRequests(),deps.recordTimeoutMs??1000,'phase request read');
        lastPhaseRequests=requests;
        validateSample(p,lastSample,now());fault.checkDeadline(now());fault.verifyArtifacts(requests);
        for(const request of requests)if(fault.request(request)) {
          await observe();const ack=fault.authorize(request,now());
          await record({result:'FAULT_AUTHORIZATION',...ack});fault.checkDeadline(now());
          await bounded((signal,deadline)=>deps.ackPhase(ack,signal,deadline),deps.recordTimeoutMs??1000,'phase ACK write');
          fault.commitAck(ack);
          fault.checkDeadline(now());validateSample(p,lastSample,now());
          nextSample=sampleDeadline();
        }
      }
      wrapperStatus = owned.status();
      if (wrapperStatus) {
        requireValue(wrapperStatus.code === 0 && wrapperStatus.signal === null, `wrapper failed ${JSON.stringify(wrapperStatus)}`);
        await observe(); // actual wrapper exit precedes final sample/handshake
        fault?.complete();
        break;
      }
      if (now() >= nextSample - SAMPLE_LEAD_MS) { await observe(); nextSample = sampleDeadline(); }
      await sleep(Math.min(200, Math.max(1, nextSample - SAMPLE_LEAD_MS - now())));
    }
  } catch (error) {
    if(fault&&!error.observation)error.observation={fault:fault.snapshot(),phaseRequests:lastPhaseRequests??null,
      rawInspection:deps.observation?.()?.rawInspection??null,inspectScope:'last completed inspect; phase/loop failure may be separate'};
    failure = error;
  }
  // Process shutdown is independent of Docker and evidence I/O. SIGCONT before
  // TERM/KILL handles STOP fault descendants. Group leader exit never skips kill.
  const rejected=failure?.observation??null;
  const diagnostic=rejected&&outputReady?record({result:'REJECTED_OBSERVATION',observation:rejected}).catch(()=>{}):Promise.resolve();
  const stops = await Promise.allSettled([Promise.resolve().then(() => owned?.stop()),
    Promise.resolve().then(() => deps.stopBrokers(p))]);
  await diagnostic;
  for (const r of stops) if (r.status === 'rejected') cleanupErrors.push(String(r.reason));
  if (cleanupErrors.length > 0 && !failure) failure = new Error('owned cleanup failed');
  const result = { ok: !failure, error: failure ? String(failure) : null, wrapperStatus,
    finalSample: lastSample, rejectedObservation:rejected, fault:fault?.snapshot(),cleanupErrors, volumesPreserved: true, scope: SCOPE };
  if (outputReady) {
    try {
      // READY is provisional; only committed handshake plus CLI exit0 completes.
      // No asynchronous evidence operation follows successful final publication.
      await record({ result: result.ok ? 'PROOF_READY' : 'PROOF_ABORT', ...result });
      if (result.ok) await bounded((signal, deadlineMs) => deps.finish(result, signal, deadlineMs),
        deps.recordTimeoutMs ?? 1000, 'completion evidence write');
    } catch (error) {
      result.ok = false; result.error = `${result.error ? result.error + '; ' : ''}evidence write failed: ${error}`;
      try { await record({ result: 'PROOF_ABORT', ...result }); }
      catch (recordError) { result.error += `; abort evidence write failed: ${recordError}`; }
    }
  }
  return result;
}

export async function launchOwnedWrapper(wrapper, { env = process.env } = {}) {
  const stdout = openSync(join(wrapper.outputDir, 'wrapper.stdout.log'), 'wx');
  let stderr;
  try { stderr = openSync(join(wrapper.outputDir, 'wrapper.stderr.log'), 'wx'); }
  catch (error) { closeSync(stdout); throw error; }
  let child;
  try { child = spawn(wrapper.argv[0], wrapper.argv.slice(1), { cwd: wrapper.cwd, detached: true,
    env, stdio: ['ignore', stdout, stderr] }); }
  finally { closeSync(stdout); closeSync(stderr); }
  let status = null;
  child.on('error', error => { status = { code: null, signal: null, error: String(error) }; });
  child.on('exit', (code, signal) => { status = { code, signal }; });
  const signalGroup = sig => {
    if (!child.pid) return;
    try { process.kill(-child.pid, sig); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  };
  let stopping;
  return { pid: child.pid, status: () => status,
    stop: () => stopping ??= (async () => { signalGroup('SIGCONT'); signalGroup('SIGTERM'); await wait(250); signalGroup('SIGKILL');
      // Keep child handle observed until its exit event; kill never depends on it.
      if (status === null) await bounded(() => new Promise(resolve => child.once('exit', resolve)), 2000, 'wrapper reap');
    })(),
  };
}

async function rawBytes(dir) {
  let total = 0;
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const path = join(dir, entry.name), stat = await lstat(path);
    requireValue(!stat.isSymbolicLink(), 'proof output contains out-of-scope symlink');
    total += stat.isDirectory() ? await rawBytes(path) : stat.size;
    if (total >= BUDGETS.rawProofBytes) return total;
  }
  return total;
}

export function dockerDependencies(p, { signal, docker = '/usr/local/bin/docker', now = hostMonotonicMs, execute = exec } = {}) {
  async function command(argv) {
    const { stdout } = await execute(docker, argv, { timeout: 1500, maxBuffer: 1024 * 1024, killSignal: 'SIGKILL' });
    return stdout;
  }
  async function inspect() {
    const rows = JSON.parse(await command(['inspect', ...p.registeredResources.containers.map(c => c.id)]));
    try {
    requireValue(Array.isArray(rows) && rows.length === 3, 'Docker inspect resource set mismatch');
    for (const c of p.registeredResources.containers) {
      const row = rows.find(row => row.Id === c.id);
      requireValue(row && sameLabels(c.labels, row.Config?.Labels), 'Docker registry/label mismatch');
      if (c.imageId) requireValue(row.Image === c.imageId, 'Docker image differs from frozen registry');
      if (c.volumes) for (const volume of c.volumes) requireValue(row.Mounts?.some(m =>
        m.Type === 'volume' && m.Name === volume.name && m.Destination === volume.destination), 'Docker volume differs from frozen registry');
    }
    validateInspectedBrokerEndpoints(p.registeredResources, rows);
    return rows;
    } catch(error) {error.rawInspection=rows;throw error;}
  }
  let journal,lastObservation;
  return { now, signal,
    prepare: async () => {
      await mkdir(p.wrapper.outputDir); // exclusive new directory, no recursive reuse
      if(p.externalBrokerFault?.phaseProtocol)await mkdir(p.externalBrokerFault.phaseProtocol.directory);
      await writeFile(join(p.wrapper.outputDir, 'supervisor-preflight.json'), JSON.stringify(p, null, 2), { flag: 'wx' });
      journal = await open(join(p.wrapper.outputDir, 'resource-samples.jsonl'), 'ax');
    },
    monitor: async (_,fault) => {
      const sampledAtMs = now();let inspected;const commands = [];
      try {
      inspected = await inspect();
      lastObservation={sampledAtMs,rawInspection:inspected,commands,fault};
      const containers = await Promise.all(p.registeredResources.containers.map(async c => {
        const row = inspected.find(row => row.Id === c.id), running = row.State?.Running === true;
        const protocolTarget=p.externalBrokerFault?.phaseProtocol&&p.externalBrokerFault.targetContainer===c.id;
        const phase=fault?.phase??'HEALTHY';
        requireValue(typeof row.State?.Running==='boolean'&&row.State.Paused===false&&row.State.Restarting===false&&row.State.Dead===false,'broker paused/restarting/dead/unknown');
        if (!running) {
          requireValue(p.externalBrokerFault?.targetContainer === c.id && row.State?.Status === 'exited'&&
            (!protocolTarget||['STOP_REQUESTED','STOPPED','RECOVERING'].includes(phase)), 'unexpected broker stopped state');
          return { id: c.id, labels: row.Config.Labels, running, healthy: false, allocationMode: 'conservative-stopped-bound',
            lifecycle:row.State,restartCount:row.RestartCount,allocatedBytes: p.externalBrokerFault.stoppedAllocatedBytesUpperBound,
            measuredAllocatedBytes:null,boundEvidence: p.externalBrokerFault.evidence };
        }
        requireValue(row.State.Status==='running'&&(row.State.Health?.Status==='healthy'||protocolTarget&&phase==='RECOVERING'&&
          row.State.Health?.Status==='starting'&&now()<fault.recoveryDeadlineMs),'broker not healthy/running');
        const argv = ['exec', c.id, 'du', '-sk', '/var/lib/redpanda/data'], stdout = await command(argv);
        requireValue(/^\d+\s+\/var\/lib\/redpanda\/data\s*$/.test(stdout), 'invalid broker du output');
        commands.push({ argv, stdout });
        const actual=Number(stdout.trim().split(/\s+/)[0])*1024;
        if(protocolTarget)requireValue(bytes(actual,'target actual allocation')<=3*GiB,'target actual exceeds3GiB reservation');
        return { id: c.id, labels: row.Config.Labels, running, healthy: row.State.Health.Status==='healthy',
          lifecycle:row.State,restartCount:row.RestartCount,allocationMode:protocolTarget?'reserved-fault-target':'measured',
          allocatedBytes:protocolTarget?3*GiB:actual,measuredAllocatedBytes:actual };
      }));
      const peer = containers.find(c => c.running && c.healthy&&c.id!==p.externalBrokerFault?.targetContainer);
      requireValue(peer, 'no running guest-free observer');
      const argv = ['exec', peer.id, 'df', '-Pk', '/var/lib/redpanda/data'], stdout = await command(argv);
      const columns = stdout.trim().split('\n').at(-1).trim().split(/\s+/);
      requireValue(columns.length === 6 && /^\d+$/.test(columns[3]), 'invalid guest df output');
      commands.push({ argv, stdout });
      return { sampledAtMs, containers, guestFreeBytes: Number(columns[3]) * 1024,
        rawProofBytes: await rawBytes(p.hostLocalResources?.paths.find(r => r.category === 'proof').path ?? p.wrapper.outputDir), commands,rawInspection:inspected,fault,
        ...(p.hostLocalResources ? { hostLocalAllocations: await measureHostAllocations(p.hostLocalResources, execute) } : {}) };
      } catch(error) {error.observation={sampledAtMs,rawInspection:inspected??error.rawInspection??null,commands,fault};throw error;}
    },
    readPhaseRequests:()=>readPhaseRequests(p.externalBrokerFault.phaseProtocol),
    observation:()=>lastObservation,
    ackPhase:(ack,signal,deadline)=>publishPhaseArtifact(p.externalBrokerFault.phaseProtocol.directory,`ack-${ack.seq}.json`,ack,signal,deadline),
    launch: launchOwnedWrapper,
    stopBrokers: async () => {
      await inspect(); // fail closed: never stop a container with mismatched ownership
      await execute(docker, ['stop', '--time', '5', ...p.registeredResources.containers.map(c => c.id)],
        { timeout: 20000, maxBuffer: 1024 * 1024, killSignal: 'SIGKILL' });
    },
    record: async row => { await journal.appendFile(JSON.stringify(row) + '\n'); await journal.sync(); },
    finish: async (row, signal, deadlineMs) => {
      const path = join(p.wrapper.outputDir, 'supervisor-finished.json');
      await writeFile(path + '.tmp', JSON.stringify(row, null, 2), { flag: 'wx', signal });
      requireValue(!signal?.aborted, 'completion write aborted before publication');
      requireValue(Number.isFinite(deadlineMs) && performance.now() <= deadlineMs, 'completion write deadline exceeded before publication');
      // Bound applies to async preparation/cancellation, not strict end-to-end
      // syscall latency. Gate and atomic local rename share one synchronous turn:
      // timeout callbacks cannot interleave and publish a late successful marker.
      // Filesystem/kernel/Node stalls can block this short commit phase.
      renameSync(path + '.tmp', path);
    },
    close: async () => journal?.close(),
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  let deps;
  try {
    requireValue(process.argv.length === 4 && process.argv[2] === '--preflight' && isAbsolute(process.argv[3]),
      'usage: node proof-supervisor.mjs --preflight /absolute/frozen-supervisor.json');
    const p = validatePreflight(JSON.parse(await readFile(process.argv[3], 'utf8'))), controller = new AbortController();
    if(p.externalBrokerFault?.phaseProtocol)assertPhaseClock(p.externalBrokerFault.phaseProtocol);
    process.once('SIGINT', () => controller.abort()); process.once('SIGTERM', () => controller.abort());
    deps = dockerDependencies(p, { signal: controller.signal, docker: process.env.CALCIFY_DOCKER_BIN ?? '/usr/local/bin/docker' });
    const result = await superviseProof(p, deps); console.log(JSON.stringify(result)); process.exitCode = result.ok ? 0 : 1;
  } catch (error) { console.error(String(error)); process.exitCode = 1; }
  finally { await deps?.close(); }
}
