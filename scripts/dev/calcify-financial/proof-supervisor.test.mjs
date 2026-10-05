import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import fs from 'node:fs';
import fsAsync from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import {pathToFileURL} from 'node:url';
import {createPhaseClient, hostMonotonicMs, publishPhaseArtifact,readPhaseRequests,readPhaseArtifact} from './lib/external-faults.mjs';
import { BUDGETS, validatePreflight, validateSample, superviseProof, launchOwnedWrapper, dockerDependencies,createFaultCycle } from './proof-supervisor.mjs';

const GiB = 1024 ** 3;
function preflight() {
  return { schema: 'calcify-proof-supervisor-v1', registeredResources: {
    project: 'reef-calcify-session-test', containers: [1, 2, 3].map(n => ({
      id: String(n).repeat(64), labels: { 'com.docker.compose.project': 'reef-calcify-session-test',
        'com.docker.compose.service': `rp${n - 1}` },
    })),
  }, wrapper: { argv: ['/usr/bin/true'], cwd: '/tmp', outputDir: '/tmp/unique-proof', timeoutMs: 60000 } };
}
function sample(p = preflight(), now = 10000) {
  return { sampledAtMs: now, containers: p.registeredResources.containers.map(c => ({
    ...c, running: true, healthy: true, allocatedBytes: GiB, allocationMode: 'measured',
  })), guestFreeBytes: 30 * GiB, rawProofBytes: 10 };
}
test('fixed budgets retain bounded correctness profile', () => {
  assert.deepEqual(BUDGETS, { abortAllocatedBytes: 9 * GiB, hardAllocatedBytes: 10 * GiB,
    guestFreeBytesFloor: 20 * GiB, rawProofBytes: 256 * 1024 ** 2, sampleIntervalMs: 5000 });
});
test('preflight rejects unsupported schema, duplicate IDs, scope and wrapper paths', () => {
  for (const change of [p => p.schema = 'other', p => p.registeredResources.containers.pop(),
    p => p.registeredResources.containers[1].id = p.registeredResources.containers[0].id,
    p => p.registeredResources.containers[0].labels['com.docker.compose.project'] = 'unrelated',
    p => p.wrapper.argv = [''], p => p.wrapper.cwd = 'relative',
    p => p.wrapper.outputDir = p.wrapper.cwd, p => p.wrapper.timeoutMs = Infinity]) {
    const p = preflight(); change(p); assert.throws(() => validatePreflight(p));
  }
});
test('frozen preflight clone prevents caller changing cleanup resources', () => {
  const p = preflight(), frozen = validatePreflight(p); p.registeredResources.containers[0].id = 'x';
  assert.equal(frozen.registeredResources.containers[0].id, '1'.repeat(64));
  assert.throws(() => frozen.wrapper.argv.push('unexpected'));
});
test('exact resource set and labels required on every sample', () => {
  for (const change of [s => s.containers.pop(), s => s.containers.push(s.containers[0]),
    s => s.containers[0].id = '4'.repeat(64), s => s.containers[0].labels['com.docker.compose.service'] = 'other',
    s => s.containers[0].healthy = false, s => s.containers[0].running = false]) {
    const s = sample(); change(s); assert.throws(() => validateSample(preflight(), s, 10000));
  }
});
test('negative/nonfinite readings and stale/future heartbeat fail closed', () => {
  for (const v of [-1, Infinity, NaN, '5', null]) for (const field of ['guestFreeBytes', 'rawProofBytes', 'sampledAtMs']) {
    const s = sample(); s[field] = v; assert.throws(() => validateSample(preflight(), s, 10000));
  }
  for (const v of [-1, Infinity, NaN]) {
    const s = sample(); s.containers[0].allocatedBytes = v;
    assert.throws(() => validateSample(preflight(), s, 10000));
  }
  for (const stamp of [4999, 10001]) assert.throws(() => validateSample(preflight(), sample(preflight(), stamp), 10000));
});
test('each resource budget aborts at exact threshold', () => {
  for (const change of [s => s.containers[0].allocatedBytes = 7 * GiB,
    s => s.containers[0].allocatedBytes = 10 * GiB,
    s => s.guestFreeBytes = 20 * GiB - 1, s => s.rawProofBytes = 256 * 1024 ** 2]) {
    const s = sample(); change(s); assert.throws(() => validateSample(preflight(), s, 10000));
  }
  const s = sample(); s.guestFreeBytes = 20 * GiB; assert.equal(validateSample(preflight(), s, 10000).projectAllocatedBytes, 3 * GiB);
});
test('intentional STOP charges evidenced conservative bound and requires healthy peers', () => {
  const p = preflight(); p.externalBrokerFault = { targetContainer: p.registeredResources.containers[0].id,
    stoppedAllocatedBytesUpperBound: 2 * GiB, evidence: 'Pinned volume geometry upper bound' };
  const s = sample(p); Object.assign(s.containers[0], { running: false, healthy: false,
    allocationMode: 'conservative-stopped-bound', allocatedBytes: 2 * GiB });
  assert.equal(validateSample(p, s, 10000).projectAllocatedBytes, 4 * GiB);
  s.containers[1].running = false; assert.throws(() => validateSample(p, s, 10000));
  s.containers[1].running = true; s.containers[0].allocatedBytes = GiB;
  assert.throws(() => validateSample(p, s, 10000));
  assert.throws(() => validatePreflight({ ...p, externalBrokerFault: { ...p.externalBrokerFault, targetContainer: '9'.repeat(64) } }));
  assert.throws(() => validatePreflight({ ...p, externalBrokerFault: { ...p.externalBrokerFault, evidence: '' } }));
});

function fakeDeps({ rows = [sample()], status = null, monitorError, recordError, hang = false } = {}) {
  const calls = [], times = { now: 10000 }; let index = 0;
  return { calls, times, now: () => times.now, sleep: async ms => { times.now += ms; },
    prepare: async () => calls.push('prepare'),
    monitor: async () => { calls.push('sample'); if (monitorError) throw monitorError;
      if (hang) return new Promise(() => {}); return structuredClone(rows[Math.min(index++, rows.length - 1)]); },
    launch: async () => { calls.push('launch'); return { status: () => status,
      stop: async () => calls.push('group-stop') }; },
    stopBrokers: async () => calls.push('broker-stop'),
    record: async row => { calls.push(row.result ?? 'record-sample'); if (recordError) throw recordError; },
    finish: async row => calls.push(['finish', row]), sampleTimeoutMs: 10,
  };
}
test('observer startup throw prevents launch, still stops registered brokers', async () => {
  const d = fakeDeps({ monitorError: new Error('du failed') }); const r = await superviseProof(preflight(), d);
  assert.equal(r.ok, false); assert.match(r.error, /du failed/);
  assert.equal(d.calls.includes('launch'), false); assert.ok(d.calls.includes('broker-stop'));
});
test('observer throw/stale/hung and sample record failure kill owned group and brokers', async () => {
  for (const variant of ['throw', 'stale', 'hang', 'record']) {
    const d = fakeDeps(), initial = d.monitor;
    let n = 0; d.monitor = async () => {
      if (n++ === 0) return initial();
      if (variant === 'throw') throw new Error('observer failed');
      if (variant === 'hang') return new Promise(() => {});
      return sample(preflight(), variant === 'stale' ? 0 : d.times.now);
    };
    if (variant === 'record') { let n = 0; d.record = async () => { if (n++ > 0) throw new Error('disk write failed'); }; }
    const r = await superviseProof(preflight(), d); assert.equal(r.ok, false, variant);
    assert.ok(d.calls.includes('group-stop'), variant); assert.ok(d.calls.includes('broker-stop'), variant);
  }
});
test('wrapper failure and proof timeout kill owned group and registered brokers', async () => {
  for (const status of [{ code: 2, signal: null }, { code: null, signal: 'SIGTERM' }, null]) {
    const d = fakeDeps({ status }); d.monitor = async () => sample(preflight(), d.times.now);
    const p = preflight(); p.wrapper.timeoutMs = 5000;
    const r = await superviseProof(p, d); assert.equal(r.ok, false);
    assert.ok(d.calls.includes('group-stop')); assert.ok(d.calls.includes('broker-stop'));
  }
});
test('success requires final sample and exit status handshake before completion', async () => {
  const d = fakeDeps({ status: { code: 0, signal: null } });
  const r = await superviseProof(preflight(), d); assert.equal(r.ok, true);
  assert.equal(d.calls.filter(c => c === 'sample').length, 2);
  assert.deepEqual(d.calls.find(c => Array.isArray(c))[1].wrapperStatus, { code: 0, signal: null });
  assert.ok(d.calls.indexOf('broker-stop') < d.calls.findIndex(c => Array.isArray(c)));
});
test('final sample failure cannot report successful wrapper as proof completion', async () => {
  const d = fakeDeps({ status: { code: 0, signal: null }, rows: [sample(), { ...sample(), rawProofBytes: Infinity }] });
  assert.equal((await superviseProof(preflight(), d)).ok, false);
  assert.equal(d.calls.some(c => Array.isArray(c) && c[0] === 'finish'), false);
});
test('cleanup failures are recorded and never relabel proof as successful', async () => {
  const d = fakeDeps({ status: { code: 0, signal: null } });
  d.stopBrokers = async () => { throw new Error('Docker stop failed'); };
  const r = await superviseProof(preflight(), d); assert.equal(r.ok, false);
  assert.match(r.cleanupErrors.join(), /Docker stop failed/);
});
test('hanging journal, prelaunch interrupt and synchronous cleanup throws fail closed', async () => {
  const d = fakeDeps({ status: null }); let n = 0;
  d.recordTimeoutMs = 10; d.record = () => n++ === 0 ? undefined : new Promise(() => {});
  d.monitor = async () => sample(preflight(), d.times.now);
  assert.equal((await superviseProof(preflight(), d)).ok, false);
  assert.ok(d.calls.includes('group-stop')); assert.ok(d.calls.includes('broker-stop'));
  const interrupted = fakeDeps(); interrupted.signal = { aborted: true };
  assert.equal((await superviseProof(preflight(), interrupted)).ok, false);
  assert.equal(interrupted.calls.includes('launch'), false);
  const sync = fakeDeps({ status: { code: 0, signal: null } });
  sync.stopBrokers = () => { throw new Error('sync Docker stop failed'); };
  const result = await superviseProof(preflight(), sync); assert.equal(result.ok, false);
  assert.ok(sync.calls.includes('group-stop')); assert.match(result.cleanupErrors.join(), /sync Docker/);
});
test('monitor plus journal elapsed time cannot launch with stale initial sample', async () => {
  const d = fakeDeps({ status: { code: 0, signal: null } });
  d.monitor = async () => { const stamp = d.times.now; d.times.now += 4400; return sample(preflight(), stamp); };
  d.record = async () => { d.times.now += 900; };
  const result = await superviseProof(preflight(), d);
  assert.equal(result.ok, false); assert.match(result.error, /stale/);
  assert.equal(d.calls.includes('launch'), false); assert.ok(d.calls.includes('broker-stop'));
});
test('slow initial monitor anchors first and later sample starts to sample timestamp', async () => {
  const d = fakeDeps(), starts = [];
  d.monitor = async () => { const stamp = d.times.now; starts.push(stamp); d.times.now += 4000; return sample(preflight(), stamp); };
  d.launch = async () => ({ status: () => starts.length >= 3 ? { code: 0, signal: null } : null, stop: async () => {} });
  const result = await superviseProof(preflight(), d); assert.equal(result.ok, true);
  assert.deepEqual(starts.slice(0, 3), [10000, 14800, 19600]);
});
test('polling clock jump past heartbeat deadline stops owned wrapper before new sample', async () => {
  const d = fakeDeps(), stamps = [];
  d.monitor = async () => { stamps.push(d.times.now); return sample(preflight(), d.times.now); };
  d.sleep = async () => { d.times.now += 5001; };
  const result = await superviseProof(preflight(), d); assert.equal(result.ok, false); assert.match(result.error, /stale/);
  assert.equal(stamps.length, 1); assert.ok(d.calls.includes('group-stop')); assert.ok(d.calls.includes('broker-stop'));
});
test('elapsed launch time cannot continue with stale heartbeat', async () => {
  const d = fakeDeps(), launch = d.launch;
  d.launch = async (...args) => { const owned = await launch(...args); d.times.now += 5001; return owned; };
  const result = await superviseProof(preflight(), d); assert.equal(result.ok, false); assert.match(result.error, /stale/);
  assert.ok(d.calls.includes('group-stop')); assert.ok(d.calls.includes('broker-stop'));
});
test('hanging or throwing completion write returns failure after bounded cleanup', async () => {
  for (const finish of [() => new Promise(() => {}), () => { throw new Error('finish storage failed'); }]) {
    const d = fakeDeps({ status: { code: 0, signal: null } }); d.finish = finish; d.recordTimeoutMs = 10;
    const result = await Promise.race([superviseProof(preflight(), d),
      new Promise(resolve => setTimeout(() => resolve('still pending'), 100))]);
    assert.notEqual(result, 'still pending'); assert.equal(result.ok, false); assert.match(result.error, /write|finish/);
    assert.ok(d.calls.includes('group-stop')); assert.ok(d.calls.includes('broker-stop'));
  }
});
test('polling jitter within early sample lead keeps strict freshness and bounded cadence', async () => {
  const d = fakeDeps(), starts = [];
  d.monitor = async () => { const stamp = d.times.now; starts.push(stamp); d.times.now += 4000; return sample(preflight(), stamp); };
  d.sleep = async ms => { d.times.now += ms + 5; };
  d.launch = async () => ({ status: () => starts.length >= 3 ? { code: 0, signal: null } : null, stop: async () => {} });
  const result = await superviseProof(preflight(), d); assert.equal(result.ok, true);
  assert.ok(starts[1] - starts[0] <= 5000); assert.ok(starts[2] - starts[1] <= 5000);
});
test('real asynchronous monitor/write time and polling jitter stay within five-second sample window', async () => {
  const d = fakeDeps(), starts = [], p = preflight();
  d.now = () => performance.now();
  d.monitor = async () => {
    const stamp = d.now(); starts.push(stamp); await new Promise(resolve => setTimeout(resolve, 20)); return sample(p, stamp);
  };
  d.record = async () => { await new Promise(resolve => setTimeout(resolve, 10)); };
  d.sleep = async ms => { await new Promise(resolve => setTimeout(resolve, ms + 3)); };
  d.sampleTimeoutMs = 200;
  d.launch = async () => ({ status: () => starts.length >= 2 ? { code: 0, signal: null } : null, stop: async () => {} });
  const result = await superviseProof(p, d); assert.equal(result.ok, true);
  assert.ok(starts[1] - starts[0] <= 5000);
  assert.ok(starts[1] - starts[0] >= 4800);
});
test('timed-out completion receives cancellation and cannot publish after delayed data write', async () => {
  const d = fakeDeps({ status: { code: 0, signal: null } }); let published = false, observedSignal;
  d.recordTimeoutMs = 10;
  d.finish = async (_, signal) => {
    observedSignal = signal; await new Promise(resolve => setTimeout(resolve, 40));
    if (signal.aborted) throw new Error('cancelled before publication');
    published = true;
  };
  const result = await superviseProof(preflight(), d); assert.equal(result.ok, false);
  assert.equal(observedSignal.aborted, true);
  await new Promise(resolve => setTimeout(resolve, 50)); assert.equal(published, false);
});

async function adapterFixture({ stopped = false, duFailure = false, wrongImage = false, prepare = true, protocol = true,now=()=>10000,duOutput } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'calcify-proof-docker-control-')), p = preflight(), calls = [];
  p.wrapper.cwd = root; p.wrapper.outputDir = join(root, 'proof');
  p.registeredResources.containers.forEach(c => { c.imageId = `sha256:${'a'.repeat(64)}`;
    c.labels['reef.test']='calcify-financial-sprint1';
    c.volumes = [{ name: `${p.registeredResources.project}-${c.id[0]}-data`, destination: '/var/lib/redpanda/data' }]; });
  p.externalBrokerFault = { targetContainer: p.registeredResources.containers[0].id,
    composeProject:p.registeredResources.project,containers:p.registeredResources.containers.map(c=>c.id),
    stoppedAllocatedBytesUpperBound: 3 * GiB, evidence: 'Frozen geometry; exclusively owned volume, no other writers' };
  if(protocol) {
    p.externalBrokerFault.phaseProtocol={schema:'calcify-broker-fault-phase-v1',runId:'guard-control',maxCycles:1,recoveryTimeoutMs:30000,stopTimeoutMs:30000,
      directory:join(p.wrapper.outputDir,'broker-fault'),clock:{platform:'darwin',node:'22.22.1',uv:'1.51.0'}};
    p.wrapper.argv.push('--fault-phase-directory',p.externalBrokerFault.phaseProtocol.directory);
  }
  const rows = p.registeredResources.containers.map((c, n) => ({ Id: c.id, Config: { Labels: c.labels },
    Image: wrongImage ? `sha256:${'b'.repeat(64)}` : c.imageId,
    Mounts: c.volumes.map(v => ({ Type: 'volume', Name: v.name, Destination: v.destination })),
    RestartCount:0, State: { StartedAt:'2026-10-05T01:00:00.000Z',Running: !(stopped && n === 0), Status: stopped && n === 0 ? 'exited' : 'running', Paused:false,Restarting:false,Dead:false,Health: { Status: 'healthy',Log:[] } },
  }));
  const execute = async (file, argv, options) => {
    calls.push({ file, argv, options });
    if (argv[0] === 'inspect') return { stdout: JSON.stringify(rows) };
    if (argv.includes('du')) { if (duFailure) throw new Error('running broker du failed'); return { stdout: duOutput?await duOutput(argv,rows):'1048576\t/var/lib/redpanda/data\n' }; }
    if (argv.includes('df')) return { stdout: 'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk 50000000 1000000 40000000 2% /var/lib/redpanda/data\n' };
    return { stdout: '' };
  };
  const deps = dockerDependencies(validatePreflight(p), { execute, now, docker: '/test/docker' });
  if (prepare) await deps.prepare(p); return { p, deps, calls, rows };
}
test('Attempt2 cycle2 preflight requires exactly one adjacent literal phase flag and directory',async()=>{
  const {p,deps}=await adapterFixture({prepare:false});
  try {
    const directory=p.externalBrokerFault.phaseProtocol.directory;
    assert.doesNotThrow(()=>validatePreflight(p));
    for(const argv of [['/usr/bin/true',directory],['/usr/bin/true','--fault-phase-directory'],
      ['/usr/bin/true','--fault-phase-directory','/tmp/wrong',directory],
      ['/usr/bin/true','--fault-phase-directory',directory,'--fault-phase-directory',directory],
      ['/usr/bin/true','--fault-phase-directory',directory,'--fault-phase-directory','/tmp/wrong']]) {
      assert.throws(()=>validatePreflight({...p,wrapper:{...p.wrapper,argv}}),/phase.*flag|flag.*phase/);
    }
  } finally {await deps.close();}
});
test('Attempt2 cycle2 three-second recovery records multiple measured starting samples; other phases keep five-second cadence',async()=>{
  const {p,deps,rows}=await adapterFixture({prepare:false});
  let clock=10000,status=null,task,grant;const duReceipts=[];
  const realDeps=dockerDependencies(validatePreflight(p),{now:()=>clock,execute:async(file,argv)=>{
    if(grant&&clock>=grant.grantedAtMs+3000)rows[0].State.Health={Status:'healthy',Log:[{
      Start:'2026-10-05T02:00:03.000Z',End:'2026-10-05T02:00:03.100Z',ExitCode:0}]};
    if(argv[0]==='inspect')return {stdout:JSON.stringify(rows)};
    if(argv.includes('du')){duReceipts.push({at:clock,id:argv[1],health:rows[0].State.Health.Status});return {stdout:'1048576\t/var/lib/redpanda/data\n'};}
    if(argv.includes('df'))return {stdout:'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk 50000000 1000000 40000000 2% /var/lib/redpanda/data\n'};
    return {stdout:''};
  }});
  realDeps.sleep=async ms=>{clock+=ms;await new Promise(r=>setTimeout(r,1));};
  const until=async at=>{while(clock<at)await new Promise(r=>setTimeout(r,1));};
  realDeps.launch=async()=>{
    task=(async()=>{
      const client=createPhaseClient(p.externalBrokerFault,{now:()=>clock,sleep:async()=>new Promise(r=>setTimeout(r,1)),
        runtime:{platform:'darwin',versions:{node:'22.22.1',uv:'1.51.0'}}});
      await until(clock+6000);await client.request('stop');Object.assign(rows[0].State,{Running:false,Status:'exited'});
      await client.request('stopped');await until(clock+10000);grant=await client.request('start');
      Object.assign(rows[0].State,{Running:true,Status:'running',StartedAt:'2026-10-05T02:00:00.000Z',Health:{Status:'starting',Log:[]}});
      await until(grant.grantedAtMs+3000);await client.request('recovered');await until(clock+10000);status={code:0,signal:null};
    })().catch(error=>{status={code:1,signal:null,error:String(error)};});
    return {status:()=>status,stop:async()=>{}};
  };
  try {
    const result=await superviseProof(p,realDeps);await task;assert.equal(result.ok,true,result.error);
    const journal=(await readFile(join(p.wrapper.outputDir,'resource-samples.jsonl'),'utf8')).split('\n').filter(Boolean).map(JSON.parse);
    const samples=journal.filter(row=>row.containers);
    const starting=samples.filter(row=>row.fault.phase==='RECOVERING'&&row.containers[0].lifecycle.Health.Status==='starting');
    assert.ok(starting.length>=2,`parent starting samples=${starting.length}; three-second recovery must be sampled`);
    for(const row of starting){const target=row.containers[0];assert.equal(target.healthy,false);assert.equal(target.allocationMode,'reserved-fault-target');
      assert.equal(target.allocatedBytes,3*GiB);assert.equal(target.measuredAllocatedBytes,GiB);
      assert.equal(row.fault.recoveryDeadlineMs,grant.grantedAtMs+30000);
      assert.ok(duReceipts.some(receipt=>receipt.at===row.sampledAtMs&&receipt.id===target.id&&receipt.health==='starting'));
    }
    assert.equal(starting[1].sampledAtMs-starting[0].sampledAtMs,800);
    for(const [phase,seq] of [['HEALTHY',0],['STOPPED',2],['RECOVERED',4]]) {
      const stamps=samples.filter(row=>row.fault.phase===phase&&row.fault.seq===seq).map(row=>row.sampledAtMs);
      assert.ok(stamps.some((stamp,n)=>n>0&&stamp-stamps[n-1]===4800),`${phase} retains nominal five-second cadence with200ms lead`);
    }
    assert.equal(result.fault.recoveryDeadlineMs,grant.grantedAtMs+30000);
    console.log(JSON.stringify({control:'three-second-parent-recovery',startingSamples:starting.map(row=>row.sampledAtMs),
      grantAtMs:grant.grantedAtMs,recoveryDeadlineMs:grant.recoveryDeadlineMs,scope:'actual adapter and file protocol; injected Docker and monotonic clock'}));
  } finally {await realDeps.close();await deps.close();}
});
test('Attempt2 group2 actual adapter and file protocol accept one STOP starting healthy cycle with stable reservation',async()=>{
  const {p,deps,rows,calls}=await adapterFixture({prepare:false,protocol:true});
  let clock=10000,status=null,task;deps.now=()=>clock;
  // Actual adapter timestamps must use same injected monotonic clock.
  const realDeps=dockerDependencies(validatePreflight(p),{now:()=>clock,execute:async(file,argv,options)=>{
    calls.push({file,argv,options});
    if(argv[0]==='inspect')return {stdout:JSON.stringify(rows)};
    if(argv.includes('du'))return {stdout:'1048576\t/var/lib/redpanda/data\n'};
    if(argv.includes('df'))return {stdout:'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk 50000000 1000000 40000000 2% /var/lib/redpanda/data\n'};
    return {stdout:''};
  }});
  realDeps.sleep=async ms=>{clock+=ms;await new Promise(r=>setTimeout(r,1));};
  realDeps.launch=async()=>{
    task=(async()=>{
      const client=createPhaseClient(p.externalBrokerFault,{now:()=>clock,sleep:async()=>new Promise(r=>setTimeout(r,1)),runtime:{platform:'darwin',versions:{node:'22.22.1',uv:'1.51.0'}}});
      await client.request('stop');Object.assign(rows[0].State,{Running:false,Status:'exited'});
      await client.request('stopped');const grant=await client.request('start');
      Object.assign(rows[0].State,{Running:true,Status:'running',StartedAt:'2026-10-05T02:00:00.000Z',Health:{Status:'starting',Log:[]}});
      while(clock<grant.grantedAtMs+10000)await new Promise(r=>setTimeout(r,1));
      rows[0].State.Health={Status:'healthy',Log:[{Start:'2026-10-05T02:00:03.000Z',End:'2026-10-05T02:00:03.100Z',ExitCode:0}]};
      await client.request('recovered');assert.ok(clock<grant.recoveryDeadlineMs);status={code:0,signal:null};
    })().catch(error=>{status={code:1,signal:null,error:String(error)};});
    return {status:()=>status,stop:async()=>{}};
  };
  try {
    const result=await superviseProof(p,realDeps);await task;assert.equal(result.ok,true,result.error);
    assert.equal(result.finalSample.fault.phase,'RECOVERED');
    const journal=(await readFile(join(p.wrapper.outputDir,'resource-samples.jsonl'),'utf8')).split('\n').filter(Boolean).map(JSON.parse);
    const starting=journal.find(row=>row.containers?.[0].lifecycle?.Health?.Status==='starting');
    assert.ok(starting);assert.equal(starting.containers[0].healthy,false);assert.equal(starting.containers[0].allocatedBytes,3*GiB);
    assert.equal(starting.containers[0].measuredAllocatedBytes,GiB);
    assert.ok(calls.some(c=>c.argv.includes('du')&&c.argv[1]===p.externalBrokerFault.targetContainer));
  } finally {await realDeps.close();await deps.close();}
});
test('Attempt2 healthy-only restriction mutant reaches STOP then rejects actual adapter starting observation',async()=>{
  const {p,rows}=await adapterFixture({prepare:false});
  const source=await readFile(new URL('./proof-supervisor.mjs',import.meta.url),'utf8');
  const changed=source.replace(/row\.State\.Health\?\.Status==='healthy'\|\|protocolTarget&&phase==='RECOVERING'&&\s*row\.State\.Health\?\.Status==='starting'&&now\(\)<fault\.recoveryDeadlineMs/,
    "row.State.Health?.Status==='healthy'");assert.notEqual(changed,source);
  const path=join(await mkdtemp(join(tmpdir(),'calcify-health-restriction-')),'guard-mutant.mjs');
  await fsAsync.writeFile(path,changed.replace("'./lib/external-faults.mjs'",JSON.stringify(new URL('./lib/external-faults.mjs',import.meta.url).href)));
  const {dockerDependencies:mutant}=await import(pathToFileURL(path).href);
  const deps=mutant(validatePreflight(p),{now:()=>10000,execute:async(_,argv)=>({stdout:argv[0]==='inspect'?JSON.stringify(rows):argv.includes('du')?
    '1048576\t/var/lib/redpanda/data\n':argv.includes('df')?'Filesystem 1024-blocks Used Available Capacity Mounted on\n/dev/disk 50000000 1000000 40000000 2% /var/lib/redpanda/data\n':''})});
  try {
    await deps.prepare(p);await deps.monitor(p,{phase:'HEALTHY'});
    Object.assign(rows[0].State,{Running:false,Status:'exited'});await deps.monitor(p,{phase:'STOPPED'});
    Object.assign(rows[0].State,{Running:true,Status:'running',StartedAt:'2026-10-05T02:00:00.000Z',Health:{Status:'starting',Log:[]}});
    await assert.rejects(deps.monitor(p,{phase:'RECOVERING',recoveryDeadlineMs:40000}),error=>{
      assert.match(error.message,/not healthy/);assert.equal(error.observation.rawInspection[0].State.Health.Status,'starting');return true;
    });
    console.log(JSON.stringify({control:'healthy-only-restriction-mutant',orderedObservations:['healthy','stopped','starting'],rejectedAt:'starting',scope:'single predicate restriction mutation; not complete old source'}));
  }finally{await deps.close();}
});
async function recoveryHarness(phase='HEALTHY') {
  const time={now:10000},fixture=await adapterFixture({now:()=>time.now});
  const {p,deps,rows}=fixture,cycle=createFaultCycle(validatePreflight(p),time.now),requests=[],acks=[];
  const observe=async(persistMs=0)=>{
    cycle.checkDeadline(time.now);
    let row=validateSample(p,await deps.monitor(p,cycle.snapshot()),time.now);cycle.accept(row,time.now);
    row={...row,fault:cycle.snapshot()};time.now+=persistMs;validateSample(p,row,time.now);cycle.commitObservation(time.now);return row;
  };
  const step=async action=>{
    const request={schema:p.externalBrokerFault.phaseProtocol.schema,runId:'guard-control',cycle:1,seq:requests.length+1,action,
      targetContainer:p.externalBrokerFault.targetContainer,issuedAtMs:time.now};
    assert.equal(cycle.request(request),true);await observe();const ack=cycle.authorize(request,time.now);
    requests.push(request);acks.push(ack);cycle.commitAck(ack);return ack;
  };
  await observe();await observe();
  if(phase!=='HEALTHY') {
    await step('stop');Object.assign(rows[0].State,{Running:false,Status:'exited'});await step('stopped');
  }
  if(['RECOVERING','RECOVERED'].includes(phase)) {
    await step('start');Object.assign(rows[0].State,{Running:true,Status:'running',StartedAt:'2026-10-05T02:00:00.000Z',Health:{Status:'starting',Log:[]}});await observe();
  }
  const healthy=()=>rows[0].State.Health={Status:'healthy',Log:[{Start:'2026-10-05T02:00:03.000Z',End:'2026-10-05T02:00:03.100Z',ExitCode:0}]};
  if(phase==='RECOVERED'){healthy();await step('recovered');}
  return {...fixture,time,cycle,observe,step,healthy,requests,acks,close:()=>deps.close()};
}
test('Attempt2 group1 initial stopped starting unhealthy and unconfigured fault reject before launch',async()=>{
  for(const mutate of [row=>Object.assign(row.State,{Running:false,Status:'exited'}),row=>row.State.Health.Status='starting',row=>row.State.Health.Status='unhealthy']) {
    const h=await adapterFixture({prepare:false});mutate(h.rows[0]);let launched=false;
    h.deps.launch=async()=>{launched=true;throw Error('must not launch');};
    try {const result=await superviseProof(h.p,h.deps);assert.equal(result.ok,false);assert.equal(launched,false);assert.equal(result.rejectedObservation.rawInspection[0].Id,h.rows[0].Id);}
    finally{await h.deps.close();}
  }
  const h=await adapterFixture();try{delete h.p.externalBrokerFault.phaseProtocol;assert.throws(()=>validatePreflight(h.p),/protocol/);}finally{await h.deps.close();}
});
test('Attempt2 group3 START requires authorized directly observed stopped barrier even without periodic STOP sample',async()=>{
  const h=await recoveryHarness();try {
    const first=await h.step('stop');assert.equal(first.phase,'STOP_REQUESTED');
    const request={...h.requests[0],seq:2,action:'stopped'};assert.equal(h.cycle.request(request),true);
    assert.throws(()=>h.cycle.authorize(request,h.time.now),/barrier/);
    assert.throws(()=>h.cycle.request({...request,seq:3,action:'start'}),/skipped/);
    Object.assign(h.rows[0].State,{Running:false,Status:'exited'});const stopped=await h.step('stopped');
    const start=await h.step('start');assert.equal(start.phase,'RECOVERING');
    assert.ok(start.grantedAtMs-first.grantedAtMs<4800);assert.equal(stopped.phase,'STOPPED');
  }finally{await h.close();}
});
test('Attempt2 group4 exact identity artifacts immutable replay path and missing ACK fail closed',async()=>{
  const h=await recoveryHarness();try {
    const request={schema:h.p.externalBrokerFault.phaseProtocol.schema,runId:'guard-control',cycle:1,seq:1,action:'stop',targetContainer:h.rows[0].Id,issuedAtMs:h.time.now};
    for(const change of [{runId:'other'},{targetContainer:'9'.repeat(64)},{cycle:2},{seq:3,action:'start'},{schema:'other'},{issuedAtMs:NaN},{extra:'bad'}])assert.throws(()=>h.cycle.request({...request,...change}));
    const ack=await h.step('stop'),deadline=ack.stopDeadlineMs;h.time.now+=1000;
    assert.equal(h.cycle.request(h.requests[0]),false);assert.equal(h.cycle.snapshot().stopDeadlineMs,deadline);
    assert.throws(()=>h.cycle.request({...h.requests[0],issuedAtMs:h.time.now}),/mutated/);
    const artifacts=[h.requests[0]];artifacts.acknowledgements=[{...ack,runId:'wrong'}];assert.throws(()=>h.cycle.verifyArtifacts(artifacts),/ACK/);
  }finally{await h.close();}
  for(const mutate of [rows=>rows[1].Id='9'.repeat(64),rows=>rows[1].Config.Labels['com.docker.compose.service']='wrong',
    rows=>rows[1].Image=`sha256:${'b'.repeat(64)}`,rows=>rows[1].Mounts[0].Name='unregistered-volume']) {
    const f=await adapterFixture();try{mutate(f.rows);await assert.rejects(f.deps.monitor(f.p,{phase:'HEALTHY'}));}finally{await f.deps.close();}
  }
  const f=await adapterFixture();try {
    const protocol=f.p.externalBrokerFault.phaseProtocol;
    await fsAsync.writeFile(join(protocol.directory,'request-1.json'),'{');await assert.rejects(readPhaseRequests(protocol),SyntaxError);
    const p=structuredClone(f.p);p.externalBrokerFault.phaseProtocol.directory='/tmp/outside/broker-fault';assert.throws(()=>validatePreflight(p),/directory/);
  }finally{await f.deps.close();}
  const symlink=await adapterFixture();try{fs.symlinkSync('/etc/hosts',join(symlink.p.externalBrokerFault.phaseProtocol.directory,'request-1.json'));
    await assert.rejects(readPhaseRequests(symlink.p.externalBrokerFault.phaseProtocol));}finally{await symlink.deps.close();}
  const missing=await adapterFixture();try {
    let now=10000;const client=createPhaseClient(missing.p.externalBrokerFault,{now:()=>now,sleep:async()=>{now+=5000;},runtime:{platform:'darwin',versions:{node:'22.22.1',uv:'1.51.0'}}});
    await assert.rejects(client.request('stop'),/ACK missing/);await assert.rejects(client.request('stop'),/duplicate/);
  }finally{await missing.deps.close();}
});
test('Attempt2 group5 either peer lifecycle health and generation fail in every phase',async()=>{
  const mutations=[row=>row.State.Health.Status='starting',row=>row.State.Health.Status='unhealthy',row=>Object.assign(row.State,{Running:false,Status:'exited'}),
    row=>row.State.Paused=true,row=>row.State.Restarting=true,row=>row.State.Dead=true,row=>delete row.State.Health,
    row=>row.State.StartedAt='2026-10-05T03:00:00.000Z',row=>row.RestartCount=1];
  for(const phase of ['HEALTHY','STOPPED','RECOVERING','RECOVERED'])for(const mutate of mutations) {
    const h=await recoveryHarness(phase);try{mutate(h.rows[1]);await assert.rejects(h.observe());}finally{await h.close();}
  }
});
test('Attempt2 group6 target invalid grace second generation stopped-after-running and old probe fail',async()=>{
  for(const mutate of [row=>row.State.Health.Status='unhealthy',row=>row.State.Paused=true,row=>row.State.Restarting=true,row=>row.State.Dead=true,
    row=>delete row.State.Health,row=>row.State.StartedAt='2026-10-05T03:00:00.000Z',row=>Object.assign(row.State,{Running:false,Status:'exited'}),row=>row.RestartCount=1]) {
    const h=await recoveryHarness('RECOVERING');try{mutate(h.rows[0]);await assert.rejects(h.observe());}finally{await h.close();}
  }
  const old=await recoveryHarness('RECOVERING');try {
    old.rows[0].State.Health={Status:'healthy',Log:[{Start:'2026-10-05T01:00:01Z',End:'2026-10-05T01:00:02Z',ExitCode:0}]};await assert.rejects(old.observe(),/probe/);
  }finally{await old.close();}
  const done=await recoveryHarness('RECOVERED');try{done.rows[0].State.Health.Status='starting';await assert.rejects(done.observe());
    assert.throws(()=>done.cycle.request({...done.requests[0],cycle:2}),/request/);}finally{await done.close();}
});
test('Attempt2 group7 fixed recovery deadline boundaries persistence and starting never renew anchor',async()=>{
  const good=await recoveryHarness('RECOVERING');try {
    const deadline=good.cycle.snapshot().recoveryDeadlineMs;good.time.now=deadline-1;good.healthy();await good.step('recovered');good.cycle.complete();
    assert.equal(good.cycle.snapshot().recoveryDeadlineMs,deadline);
  }finally{await good.close();}
  for(const delay of [0,1]) {
    const h=await recoveryHarness('RECOVERING');try {
      const deadline=h.cycle.snapshot().recoveryDeadlineMs;h.time.now=deadline-(delay?1:0);if(delay)h.healthy();
      await assert.rejects(h.observe(delay),/deadline/);
    }finally{await h.close();}
  }
  const h=await recoveryHarness('RECOVERING');try {
    const deadline=h.cycle.snapshot().recoveryDeadlineMs;
    for(let n=1;n<=5;n++){h.time.now+=4000;await h.observe();assert.equal(h.cycle.snapshot().recoveryDeadlineMs,deadline);}
    h.time.now=deadline;assert.throws(()=>h.cycle.checkDeadline(h.time.now),/deadline/);
  }finally{await h.close();}
});
test('Attempt2 group8 target du bound strict malformed and observer hung retain fixed charged bytes',async()=>{
  let actual=3*GiB;const f=await adapterFixture({duOutput:async argv=>`${argv[1]==='1'.repeat(64)?actual/1024:1048576}\t/var/lib/redpanda/data\n`});
  try {
    const row=validateSample(f.p,await f.deps.monitor(f.p,{phase:'HEALTHY'}),10000);assert.equal(row.containers[0].measuredAllocatedBytes,3*GiB);assert.equal(row.projectAllocatedBytes,5*GiB);
    row.containers[0].measuredAllocatedBytes=3*GiB+1;assert.throws(()=>validateSample(f.p,row,10000));
    actual=3*GiB+1024;await assert.rejects(f.deps.monitor(f.p,{phase:'HEALTHY'}),/reservation/);
  }finally{await f.deps.close();}
  for(const value of ['-1\t/var/lib/redpanda/data\n','NaN\t/var/lib/redpanda/data\n','Infinity\t/var/lib/redpanda/data\n','1 wrong\n']) {
    const h=await adapterFixture({duOutput:async()=>value});try{await assert.rejects(h.deps.monitor(h.p,{phase:'HEALTHY'}),/du output/);}finally{await h.deps.close();}
  }
  const hang=await adapterFixture({prepare:false,duOutput:async()=>new Promise(()=>{})});hang.deps.sampleTimeoutMs=20;
  try{const result=await superviseProof(hang.p,hang.deps);assert.equal(result.ok,false);assert.match(result.error,/timeout/);assert.ok(result.rejectedObservation.rawInspection);assert.ok(hang.calls.some(c=>c.argv[0]==='stop'));}
  finally{await hang.deps.close();}
});
test('Attempt2 group9 protocol read ACK and authorization evidence cannot suspend strict observer gate',async()=>{
  for(const mode of ['read-stale','ack-hang','record-hang']) {
    let now=10000;const h=await adapterFixture({prepare:false,now:()=>now});let groupStopped=false;
    h.deps.recordTimeoutMs=100;h.deps.sleep=async ms=>{now+=ms;await new Promise(r=>setTimeout(r,1));};
    h.deps.launch=async()=>{if(mode!=='read-stale')await publishPhaseArtifact(h.p.externalBrokerFault.phaseProtocol.directory,'request-1.json',{
      schema:h.p.externalBrokerFault.phaseProtocol.schema,runId:'guard-control',cycle:1,seq:1,action:'stop',targetContainer:h.rows[0].Id,issuedAtMs:now});
      return {status:()=>null,stop:async()=>{groupStopped=true;}};};
    if(mode==='read-stale')h.deps.readPhaseRequests=async()=>{now+=5001;const rows=[];rows.acknowledgements=[];return rows;};
    if(mode==='ack-hang')h.deps.ackPhase=async()=>new Promise(()=>{});
    if(mode==='record-hang'){const record=h.deps.record;h.deps.record=row=>row.result==='FAULT_AUTHORIZATION'?new Promise(()=>{}):record(row);}
    try{const result=await superviseProof(h.p,h.deps);assert.equal(result.ok,false);assert.ok(groupStopped);assert.ok(h.calls.some(c=>c.argv[0]==='stop'));}
    finally{await h.deps.close();}
  }
});
test('Attempt2 group10 incomplete cycle exit cannot complete and rejected raw inspect survives diagnostics failure',async()=>{
  for(const phase of ['STOPPED','RECOVERING']) {
    const h=await recoveryHarness(phase);try{assert.throws(()=>h.cycle.complete(),/incomplete/);}finally{await h.close();}
  }
  const h=await adapterFixture({prepare:false});h.rows[1].State.Paused=true;const record=h.deps.record;
  h.deps.record=row=>row.result==='REJECTED_OBSERVATION'?Promise.reject(Error('diagnostic disk failure')):record(row);
  try{const result=await superviseProof(h.p,h.deps);assert.equal(result.ok,false);assert.ok(result.rejectedObservation.rawInspection[1].State.Paused);
    assert.ok(h.calls.some(c=>c.argv[0]==='stop'));await assert.rejects(readFile(join(h.p.wrapper.outputDir,'supervisor-finished.json')),{code:'ENOENT'});}
  finally{await h.deps.close();}
});
test('Docker adapter samples running peers, reserves stopped target and retains registered volumes', async () => {
  const { p, deps, calls } = await adapterFixture({ stopped: true });
  try {
    const row = validateSample(p, await deps.monitor(p,{phase:'STOPPED'}), 10000);
    assert.equal(row.projectAllocatedBytes, 5 * GiB);
    assert.equal(calls.filter(c => c.argv.includes('du')).length, 2);
    assert.equal(calls.find(c => c.argv.includes('df')).argv[1], p.registeredResources.containers[1].id);
    await deps.stopBrokers();
    assert.deepEqual(calls.find(c => c.argv[0] === 'stop').argv.slice(3), p.registeredResources.containers.map(c => c.id));
    assert.equal(calls.some(c => c.argv.some(a => ['rm', 'prune', 'down'].includes(a))), false);
    assert.ok(calls.every(c => c.options.timeout <= 20000));
  } finally { await deps.close(); }
});
test('Docker adapter does not skip running du failure or accept changed image metadata', async () => {
  for (const options of [{ duFailure: true }, { wrongImage: true }]) {
    const { deps } = await adapterFixture(options);
    try { await assert.rejects(deps.monitor(), /du failed|image differs/); }
    finally { await deps.close(); }
  }
});
test('adapter completion uses atomic synchronous commit rather than uncancellable asynchronous rename', async () => {
  const { p, deps } = await adapterFixture({ prepare: false }); let asyncRenameStarted = false;
  const originalRename = fsAsync.rename;
  deps.launch = async () => ({ status: () => ({ code: 0, signal: null }), stop: async () => {} });
  fsAsync.rename = async (...args) => { asyncRenameStarted = true; await new Promise(resolve => setTimeout(resolve, 1250)); return originalRename(...args); };
  syncBuiltinESMExports();
  try {
    const result = await superviseProof(p, deps); assert.equal(result.ok, true); assert.equal(asyncRenameStarted, false);
    assert.equal(JSON.parse(await readFile(join(p.wrapper.outputDir, 'supervisor-finished.json'), 'utf8')).ok, true);
  } finally { fsAsync.rename = originalRename; syncBuiltinESMExports(); await deps.close(); }
});
test('adapter timed-out temporary data write cannot publish late successful completion', async () => {
  const { p, deps } = await adapterFixture({ prepare: false }), originalWrite = fsAsync.writeFile;
  let dataWriteStarted = false;
  deps.recordTimeoutMs = 100; deps.launch = async () => ({ status: () => ({ code: 0, signal: null }), stop: async () => {} });
  fsAsync.writeFile = async (file, ...args) => {
    if (String(file).endsWith('supervisor-finished.json.tmp')) { dataWriteStarted = true; await new Promise(resolve => setTimeout(resolve, 150)); }
    return originalWrite(file, ...args);
  }; syncBuiltinESMExports();
  try {
    const result = await superviseProof(p, deps); assert.equal(result.ok, false); assert.equal(dataWriteStarted, true);
    await new Promise(resolve => setTimeout(resolve, 100));
    await assert.rejects(readFile(join(p.wrapper.outputDir, 'supervisor-finished.json')), { code: 'ENOENT' });
    const journal = await readFile(join(p.wrapper.outputDir, 'resource-samples.jsonl'), 'utf8');
    assert.equal(journal.includes('PROOF_COMPLETE'), false); assert.equal(journal.includes('PROOF_ABORT'), true);
  } finally { fsAsync.writeFile = originalWrite; syncBuiltinESMExports(); await deps.close(); }
});
test('adapter checks elapsed deadline before commit when write continuation delays timer callback', async () => {
  const { p, deps } = await adapterFixture({ prepare: false }), originalWrite = fsAsync.writeFile;
  deps.recordTimeoutMs = 100; deps.launch = async () => ({ status: () => ({ code: 0, signal: null }), stop: async () => {} });
  fsAsync.writeFile = async (file, ...args) => {
    await originalWrite(file, ...args);
    if (String(file).endsWith('supervisor-finished.json.tmp')) {
      const stop = performance.now() + 125; while (performance.now() < stop) { /* queued timeout cannot execute yet */ }
    }
  }; syncBuiltinESMExports();
  try {
    const result = await superviseProof(p, deps); assert.equal(result.ok, false); assert.match(result.error, /deadline/);
    await new Promise(resolve => setTimeout(resolve, 50));
    await assert.rejects(readFile(join(p.wrapper.outputDir, 'supervisor-finished.json')), { code: 'ENOENT' });
  } finally { fsAsync.writeFile = originalWrite; syncBuiltinESMExports(); await deps.close(); }
});
test('synchronous adapter commit cannot interleave with timeout callback at publication boundary', async () => {
  const { p, deps } = await adapterFixture({ prepare: false }), originalRename = fs.renameSync;
  let callbackRan = false, commitStarted = false;
  deps.launch = async () => ({ status: () => ({ code: 0, signal: null }), stop: async () => {} });
  fs.renameSync = (...args) => {
    commitStarted = true; setTimeout(() => { callbackRan = true; }, 0);
    const stop = performance.now() + 1050; while (performance.now() < stop) { /* synchronous commit phase */ }
    assert.equal(callbackRan, false); return originalRename(...args);
  }; syncBuiltinESMExports();
  try {
    const result = await superviseProof(p, deps); assert.equal(result.ok, true); assert.equal(commitStarted, true);
    assert.equal(JSON.parse(await readFile(join(p.wrapper.outputDir, 'supervisor-finished.json'), 'utf8')).ok, true);
    await new Promise(resolve => setTimeout(resolve, 5)); assert.equal(callbackRan, true);
  } finally { fs.renameSync = originalRename; syncBuiltinESMExports(); await deps.close(); }
});

test('observer failure stops active owned wrapper/STOP leaf while sentinel survives', async () => {
  const root = await mkdtemp(join(tmpdir(), 'calcify-proof-observer-tree-'));
  const sentinel = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)']);
  const p = preflight(); p.wrapper.cwd = '/tmp'; p.wrapper.outputDir = root;
  p.wrapper.argv = [process.execPath, '-e', `const{spawn}=require('node:child_process');const{writeFileSync}=require('node:fs');
    const leaf=spawn(process.execPath,['-e',"process.on('SIGTERM',()=>{});setInterval(()=>{},1000);process.kill(process.pid,'SIGSTOP')"],{stdio:'ignore'});
    writeFileSync(${JSON.stringify(join(root, 'leaf.pid'))},String(leaf.pid));setInterval(()=>{},1000);`];
  const d = fakeDeps(); let n = 0, owned;
  d.monitor = async () => { if (n++ > 0) throw new Error('observer unavailable'); return sample(p); };
  d.launch = async w => { owned = await launchOwnedWrapper(w); await new Promise(r => setTimeout(r, 200)); return owned; };
  try {
    const result = await superviseProof(p, d); assert.equal(result.ok, false); assert.match(result.error, /observer unavailable/);
    const leaf = Number(await readFile(join(root, 'leaf.pid'), 'utf8'));
    for (let n = 0; n < 50; n++) {
      try { process.kill(leaf, 0); await new Promise(r => setTimeout(r, 20)); }
      catch (error) { if (error.code === 'ESRCH') break; throw error; }
    }
    assert.throws(() => process.kill(leaf, 0), { code: 'ESRCH' });
    assert.throws(() => process.kill(owned.pid, 0), { code: 'ESRCH' });
    assert.doesNotThrow(() => process.kill(sentinel.pid, 0)); assert.ok(d.calls.includes('broker-stop'));
  } finally {
    try { if (owned) await owned.stop(); }
    finally { sentinel.kill('SIGKILL'); if (sentinel.exitCode === null && sentinel.signalCode === null) await once(sentinel, 'exit'); }
  }
});

test('owned group kills stopped TERM-resistant leaf after leader exits; unrelated sentinel survives', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'calcify-proof-group-'));
  const sentinel = spawn(process.execPath, ['-e', 'setInterval(()=>{},1000)']);
  const code = `const{spawn}=require('node:child_process');const{writeFileSync}=require('node:fs');
    const leaf=spawn(process.execPath,['-e',"process.on('SIGTERM',()=>{});setInterval(()=>{},1000);process.kill(process.pid,'SIGSTOP')"],{stdio:'ignore'});
    writeFileSync(${JSON.stringify(join(dir, 'leaf.pid'))},String(leaf.pid));setTimeout(()=>process.exit(0),200);`;
  const p = preflight(); p.wrapper = { ...p.wrapper, argv: [process.execPath, '-e', code], cwd: dir, outputDir: dir };
  let owned;
  try {
    owned = await launchOwnedWrapper(p.wrapper); await new Promise(r => setTimeout(r, 500));
    const pid = Number(await readFile(join(dir, 'leaf.pid'), 'utf8'));
    assert.equal(owned.status().code, 0); await owned.stop();
    // SIGKILL delivery and launchd reap are asynchronous after leader exits.
    for (let n = 0; n < 50; n++) {
      try { process.kill(pid, 0); await new Promise(r => setTimeout(r, 20)); }
      catch (error) { if (error.code === 'ESRCH') break; throw error; }
    }
    assert.throws(() => process.kill(pid, 0), { code: 'ESRCH' });
    assert.doesNotThrow(() => process.kill(sentinel.pid, 0));
  } finally {
    try { if (owned) await owned.stop(); }
    finally { sentinel.kill('SIGKILL'); if (sentinel.exitCode === null && sentinel.signalCode === null) await once(sentinel, 'exit'); }
  }
});
