import assert from 'node:assert/strict';
import {constants, renameSync} from 'node:fs';
import {open, lstat, realpath, writeFile, readdir} from 'node:fs/promises';
import {join, resolve,isAbsolute} from 'node:path';
import {performance} from 'node:perf_hooks';

export const FAULT_PROTOCOL_SCHEMA='calcify-broker-fault-phase-v1';
export const FAULT_ACTIONS=Object.freeze(['stop','stopped','start','recovered']);
// Pinned same-host Node/libuv uses host monotonic time, not process startup time.
export const hostMonotonicMs=()=>Number(process.hrtime.bigint()/1000000n);
export function validatePhaseProtocol(fault,outputDir) {
 const p=fault?.phaseProtocol;
 assert.equal(p?.schema,FAULT_PROTOCOL_SCHEMA,'fault phase protocol required');
 assert.match(p.runId,/^[a-z0-9-]{1,80}$/);assert.equal(p.maxCycles,1);assert.equal(p.recoveryTimeoutMs,30000);
 assert.ok(Number.isSafeInteger(p.stopTimeoutMs)&&p.stopTimeoutMs>0&&p.stopTimeoutMs<=30000);
 assert.ok(typeof p.directory==='string'&&isAbsolute(p.directory),'protocol directory must be absolute');
 assert.equal(resolve(p.directory),join(resolve(outputDir),'broker-fault'),'protocol directory must be frozen output child');
 assert.equal(p.clock?.platform,'darwin');assert.equal(p.clock?.node,'22.22.1');assert.equal(p.clock?.uv,'1.51.0');
 return p;
}
export function assertPhaseClock(p,runtime=process) {
 assert.equal(runtime.platform,p.clock.platform,'fault clock host mismatch');
 assert.equal(runtime.versions.node,p.clock.node,'fault clock Node mismatch');assert.equal(runtime.versions.uv,p.clock.uv,'fault clock libuv mismatch');
}
export async function readPhaseArtifact(directory,name) {
 const dir=await lstat(directory);assert.ok(dir.isDirectory()&&!dir.isSymbolicLink(),'fault protocol directory is not owned regular directory');
 const path=join(directory,name);let handle;
 try {
  handle=await open(path,constants.O_RDONLY|constants.O_NOFOLLOW);
  const stat=await handle.stat();assert.ok(stat.isFile()&&stat.size<=16384,'invalid/oversized phase artifact');
  return JSON.parse(await handle.readFile('utf8'));
 } catch(error) {if(error.code==='ENOENT')return null;throw error;} finally {await handle?.close();}
}
export async function publishPhaseArtifact(directory,name,value,signal,deadlineMs=performance.now()+1000) {
 assert.ok((await lstat(directory)).isDirectory()&&!(await lstat(directory)).isSymbolicLink(),'invalid phase directory');
 try {await lstat(join(directory,name));throw Error('immutable phase artifact already exists');}catch(error){if(error.code!=='ENOENT')throw error;}
 const path=join(directory,name);
 await writeFile(path+'.tmp',JSON.stringify(value),{flag:'wx',signal});
 assert.ok(!signal?.aborted&&performance.now()<deadlineMs,'phase artifact publication cancelled/deadline');
 renameSync(path+'.tmp',path); // same local atomic-publication limit as completion
}
export async function readPhaseRequests(p) {
 assert.equal(await realpath(p.directory),join(await realpath(resolve(p.directory,'..')),'broker-fault'),'phase directory path escape');
 const names=await readdir(p.directory);
 assert.ok(names.every(name=>/^(?:request|ack)-[1-4]\.json(?:\.tmp)?$/.test(name)),'unexpected phase artifact');
 const rows=[];let gap=false;
 for(let seq=1;seq<=4;seq++) {const row=await readPhaseArtifact(p.directory,`request-${seq}.json`);if(!row)gap=true;else{assert.ok(!gap,'skipped phase request');rows.push(row);}}
 rows.acknowledgements=[];
 for(let seq=1;seq<=4;seq++){const ack=await readPhaseArtifact(p.directory,`ack-${seq}.json`);if(ack)rows.acknowledgements.push(ack);}
 return rows;
}
export function createPhaseClient(fault,{now=hostMonotonicMs,sleep=ms=>new Promise(r=>setTimeout(r,ms)),runtime=process}={}) {
 const p=fault.phaseProtocol;validatePhaseProtocol(fault,resolve(p.directory,'..'));assertPhaseClock(p,runtime);
 let seq=0,recoveryDeadline=null;const consumed=new Map();
 async function io(operation) {
  const controller=new AbortController(),deadline=performance.now()+1000;let timer;
  try{return await Promise.race([operation(controller.signal,deadline),new Promise((_,reject)=>{
   timer=setTimeout(()=>{controller.abort();reject(Error('fault protocol IO timeout'));},1000);
  })]);}finally{clearTimeout(timer);}
 }
 return {request:async action=>{
  assert.equal(action,FAULT_ACTIONS[seq],'duplicate/skipped fault mutation');const next=++seq;
  const request={schema:p.schema,runId:p.runId,cycle:1,seq:next,action,targetContainer:fault.targetContainer,issuedAtMs:now()};
  if(action==='recovered')assert.ok(now()<recoveryDeadline,'fixed recovery deadline expired before ACK request');
  await io((signal,deadline)=>publishPhaseArtifact(p.directory,`request-${next}.json`,request,signal,deadline));
  const waitDeadline=Math.min(now()+p.stopTimeoutMs,action==='recovered'?recoveryDeadline:Infinity);
  while(now()<waitDeadline) {
   const ack=await io(()=>readPhaseArtifact(p.directory,`ack-${next}.json`));
   if(ack) {
    for(const key of ['schema','runId','cycle','seq','action','targetContainer'])assert.equal(ack[key],request[key],`fault ACK ${key} mismatch`);
    assert.equal(ack.authorized,true,'fault ACK not authorized');
    assert.equal(ack.phase,['STOP_REQUESTED','STOPPED','RECOVERING','RECOVERED'][next-1],'wrong phase authorization');
    assert.ok(Number.isFinite(ack.grantedAtMs)&&ack.grantedAtMs>=request.issuedAtMs&&ack.grantedAtMs<=now(),'stale/future fault ACK');
    if(action==='start'){assert.equal(ack.recoveryDeadlineMs-ack.grantedAtMs,p.recoveryTimeoutMs,'fault recovery deadline changed');recoveryDeadline=ack.recoveryDeadlineMs;}
    if(ack.recoveryDeadlineMs)assert.ok(now()<ack.recoveryDeadlineMs,'fault recovery deadline expired');
    consumed.set(next,JSON.stringify(ack));return ack;
   }
   await sleep(50);
  }
  throw Error('fault authorization ACK missing/timeout');
 },consumed};
}

export const EXTERNAL_ARMS = Object.freeze([
 {name:'producer-failure',trigger:'B SETTLED output exceeds producer max.request.size after prior forwards',barrier:'RecordTooLargeException from actual producer; worker shutdown; read_committed abort observation',assertions:['zero visible aborted outputs','fresh cache restores all accepted inputs']},
 {name:'committed-output-before-controller-ack',trigger:'SIGKILL after independent prefix observer succeeds, before controller ACK file exists',barrier:'read_committed prefix4 and exact output end offset',assertions:['restart retains identical prefix and end offset','only appended fresh suffix emits new outputs']},
 {name:'stale-owner-resume-after-takeover',trigger:'before-forward ordinal4 semaphore, SIGSTOP whole owner, replacement takeover, SIGCONT and resume',barrier:'replacement read_committed full8 before old owner resumes; real producer/group fencing exception',assertions:['old owner broker write rejected','unique ordered output prefix despite aborted physical frames','idle restart retains exact final end offset']},
 {name:'majority-one-broker-unavailable',trigger:'docker stop exact preflight-registered container ID after committed prefix4',barrier:'daemon target stopped, two registered peers running; full8 oracle completes while target remains stopped',assertions:['healthy majority commits fresh work','one authorized target recovery; parent cleanup on mutation failure','restart preserves prefix and stable final end offset']},
]);

export function validateRegistration(value) {
 assert.ok(value && /^[a-z0-9][a-z0-9_-]{0,62}$/.test(value.composeProject),'owned compose project required');
 assert.ok(Array.isArray(value.containers) && value.containers.length===3,'exact three registered broker IDs required');
 assert.ok(value.containers.every(id=>/^[a-f0-9]{64}$/.test(id)) && new Set(value.containers).size===3,'unique full container IDs required');
 assert.ok(value.containers.includes(value.targetContainer),'outage target must be registered full ID');
 if(value.phaseProtocol) {
  validatePhaseProtocol(value,resolve(value.phaseProtocol.directory,'..'));
  assert.ok(Array.isArray(value.resources)&&value.resources.length===3,'fault wrapper requires pinned resource metadata');
  assert.deepEqual(value.resources.map(c=>c.id).sort(),[...value.containers].sort());
  assert.ok(value.resources.every(c=>/^sha256:[a-f0-9]{64}$/.test(c.imageId)&&c.labels?.['com.docker.compose.project']===value.composeProject&&
    c.labels['reef.test']==='calcify-financial-sprint1'&&Array.isArray(c.volumes)&&c.volumes.some(v=>typeof v.name==='string'&&v.name.length>0&&v.destination==='/var/lib/redpanda/data')),'fault resource image/volume/test label pins missing');
 }
 return value;
}
export function validateInspection(registration,rows,{targetStopped=false,ownershipOnly=false,recoveryGrant,allowTargetStarting=false,now=hostMonotonicMs(),generations}={}) {
 validateRegistration(registration);
 assert.equal(rows.length,3,'exact three daemon observations required');
 assert.deepEqual(rows.map(row=>row.Id).sort(),[...registration.containers].sort(),'daemon IDs disagree with registration');
 const services=[];
 for(const row of rows) {
  const labels=row.Config?.Labels;
  assert.equal(labels?.['com.docker.compose.project'],registration.composeProject,'unowned compose project');
  assert.equal(labels?.['reef.test'],'calcify-financial-sprint1','unregistered experiment label');
  const service=labels?.['com.docker.compose.service'];assert.ok(typeof service==='string' && service.length>0,'missing broker service label');services.push(service);
  const resource=registration.resources?.find(c=>c.id===row.Id);
  if(resource) {
   assert.ok(Object.entries(resource.labels).every(([key,value])=>labels[key]===value),'frozen broker labels changed');assert.equal(row.Image,resource.imageId,'frozen broker image changed');
   for(const volume of resource.volumes)assert.ok(row.Mounts?.some(m=>m.Type==='volume'&&m.Name===volume.name&&m.Destination===volume.destination),'frozen broker mount changed');
  }
  const stopped=targetStopped && row.Id===registration.targetContainer;
  if(!ownershipOnly) {
   const s=row.State;assert.ok(s&&!s.Paused&&!s.Restarting&&!s.Dead,'broker paused/restarting/dead');
   if(generations&&row.Id!==registration.targetContainer||generations&&!recoveryGrant) {
    assert.equal(s.StartedAt,generations[row.Id].startedAt,'broker generation changed');assert.equal(row.RestartCount,generations[row.Id].restartCount,'broker restart-count changed');
   }
   assert.equal(s.Running,!stopped,'broker state disagrees with fault barrier');assert.equal(s.Status,stopped?'exited':'running','broker lifecycle disagrees with barrier');
   if(!stopped) {
    if(recoveryGrant&&row.Id===registration.targetContainer) {
     assert.ok(now<recoveryGrant.recoveryDeadlineMs,'fixed broker recovery deadline expired');
     assert.notEqual(s.StartedAt,recoveryGrant.generations[row.Id].startedAt,'target START generation missing');
     assert.equal(row.RestartCount,recoveryGrant.generations[row.Id].restartCount,'target restart-count changed');
     assert.ok(s.Health?.Status==='healthy'||allowTargetStarting&&s.Health?.Status==='starting','target recovery health invalid');
     if(s.Health.Status==='healthy')assert.ok(s.Health.Log?.some(log=>log.ExitCode===0&&Date.parse(log.Start)>=Date.parse(s.StartedAt)&&Date.parse(log.End)>=Date.parse(log.Start)),'new-generation successful health probe absent');
    } else assert.equal(s.Health?.Status,'healthy','registered broker healthcheck missing or unhealthy');
   }
  }
 }
 assert.equal(new Set(services).size,3,'broker service labels must be distinct');
 return {targetContainer:registration.targetContainer,running:!targetStopped,healthyPeers:2,
  targetHealthy:rows.find(row=>row.Id===registration.targetContainer)?.State?.Health?.Status==='healthy',inspections:rows};
}
export function assertSamePrefix(before,after,allowFresh=false,allowAbortedFrames=false) {
 assert.equal(after.pass,true,'read_committed oracle failed');assert.equal(after.readCommittedOracle,true);
 assert.ok(after.coveredInputs>=before.coveredInputs,'restored output prefix regressed');
 assert.deepEqual(after.resultOffsets.slice(0,before.resultOffsets.length),before.resultOffsets,'old physical output prefix was replaced');
 if(!allowFresh) {
  assert.equal(after.coveredInputs,before.coveredInputs,'idle restart republished old outputs');
  if(!allowAbortedFrames)assert.equal(after.finalCommittedEndOffset,before.finalCommittedEndOffset,'idle restart changed exact committed end offset');
  else assert.ok(after.finalCommittedEndOffset>=before.finalCommittedEndOffset,'aborted physical frames cannot regress end offset');
  assert.deepEqual(after.ownerCuts,before.ownerCuts,'idle restart changed owner heads');
  assert.equal(after.lastInputOffset,before.lastInputOffset,'idle restart changed certified input source cut');
  assert.equal(after.inputGroupCheckpoint,before.inputGroupCheckpoint,'idle restart changed committed input checkpoint');
  assert.equal(after.inputReadCommittedEndOffset,before.inputReadCommittedEndOffset,'idle restart changed exact committed input end offset');
 }
}
export function assertFencing(rows) {
 assert.ok(rows.some(row=>['sendOffsetsToTransaction','commitTransaction','send'].includes(row.producerBoundary) && [row.failureClass,...(row.causeClasses??[])].some(name=>/(?:ProducerFencedException|InvalidProducerEpochException|CommitFailedException|IllegalGenerationException|UnknownMemberIdException|FencedInstanceIdException|InvalidTxnStateException|InvalidPidMappingException)$/.test(name))),'actual broker producer/group fencing evidence missing');
}
function committed(view,count) {
 assert.equal(view.pass,true,'incomplete oracle barrier');assert.equal(view.readCommittedOracle,true);assert.equal(view.coveredInputs,count,'wrong committed input barrier');
}

/** Dependency adapters execute real broker/process operations; tests freeze order and fail-closed controls. */
export async function runExternalArm(name,d) {
 assert.ok(EXTERNAL_ARMS.some(row=>row.name===name),'unknown external fault arm');
 await d.init();
 if(name==='producer-failure') {
  await d.seed();const first=await d.start('production');
  const rejection=await d.marker(first,'producerBoundary');
  assert.ok([rejection.failureClass,...(rejection.causeClasses??[])].some(type=>type.endsWith('RecordTooLargeException')),'production seam did not reject actual oversized output');
  await d.crash(first,1);const aborted=await d.observe(null,1000);assert.equal(aborted.coveredInputs,0,'aborted transaction exposed financial outputs');
  const restored=await d.start('');await d.ready(restored);const after=await d.observe();committed(after,8);
  await d.stop(restored,true);const again=await d.start('');await d.ready(again);const idle=await d.observe();assertSamePrefix(after,idle);await d.stop(again);
  return {name,rejection,aborted,after,idle};
 }
 await d.seed(4);const first=await d.start(name==='stale-owner-resume-after-takeover'?'stale-owner':'');await d.ready(first);
 const before=await d.observe(4);committed(before,4);
 if(name==='committed-output-before-controller-ack') {
  await d.boundary({name,before,controllerAckPersisted:false});await d.stop(first,true);
  const restored=await d.start('');await d.ready(restored);const idle=await d.observe(4);assertSamePrefix(before,idle);
  await d.append();const after=await d.observe();committed(after,8);assertSamePrefix(before,after,true);await d.ack({before,idle,after});await d.stop(restored);
  return {name,before,idle,after,controllerAckPersisted:true};
 }
 if(name==='stale-owner-resume-after-takeover') {
  await d.append();const paused=await d.marker(first,'before-forward');await d.signal(first,'SIGSTOP');
  const replacement=await d.start('');await d.ready(replacement);const takeover=await d.observe();committed(takeover,8);assertSamePrefix(before,takeover,true);
  await d.signal(first,'SIGCONT');await d.resume(first);const fencing=await d.fencing(first);assertFencing(fencing);
  await d.stop(first,true);const after=await d.observe();assertSamePrefix(takeover,after,false,true);await d.stop(replacement,true);
  const restored=await d.start('');await d.ready(restored);const idle=await d.observe();assertSamePrefix(after,idle);await d.stop(restored);
  return {name,before,paused,takeover,fencing,after,idle};
 }
 let stopped=false,startAttempted=false;
 try {
  // Parent owns failed mutation cleanup; wrapper never retries failed START.
  const outage=await d.outage('stop');stopped=true;assert.equal(outage.running,false);assert.equal(outage.healthyPeers,2);
  await d.append();const during=await d.observe();committed(during,8);assertSamePrefix(before,during,true);
  const stillStopped=await d.outage('inspect-stopped');assert.equal(stillStopped.running,false);
  startAttempted=true;await d.outage('start');stopped=false;
  await d.stop(first,true);const restored=await d.start('');await d.ready(restored);const after=await d.observe();assertSamePrefix(during,after);await d.stop(restored);
  return {name,before,outage,during,stillStopped,after};
 } finally { if(stopped&&!startAttempted)await d.outage('start'); }
}
