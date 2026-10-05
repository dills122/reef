import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {runInNewContext} from 'node:vm';
import {EXTERNAL_ARMS, validateRegistration, validateInspection, assertSamePrefix, assertFencing, runExternalArm} from './lib/external-faults.mjs';

const ids = ['1','2','3'].map(c => c.repeat(64));
const registration = {composeProject:'reef-owned-test',containers:ids,targetContainer:ids[0]};
const compose=await readFile(new URL('../../../docs/evidence/calcify-financial-sprint1/broker.compose.yml',import.meta.url),'utf8');
const experimentLabel=compose.match(/reef\.test:\s*(\S+)/)?.[1];
assert.ok(experimentLabel,'actual financial Compose experiment label available');
const inspections = () => ids.map((Id,i) => ({Id,Config:{Labels:{'com.docker.compose.project':'reef-owned-test','com.docker.compose.service':`redpanda${i}`,'reef.test':experimentLabel}},State:{Running:true,Status:'running',Health:{Status:'healthy'}}}));
const view = (n,end=10) => ({pass:true,coveredInputs:n,expectedInputs:n,finalCommittedEndOffset:end,resultOffsets:Array.from({length:n},(_,i)=>i+1),ownerCuts:{A:{sequence:2,checksum:'a'}},inputGroupCheckpoint:n+2,inputReadCommittedEndOffset:n+2,lastInputOffset:n+1,readCommittedOracle:true});
test('readiness rejects signal-terminated worker despite retained startup receipts', async()=>{
 const source=await readFile(new URL('./broker-proof.mjs',import.meta.url),'utf8');
 const start=source.indexOf('async function ready(worker){'),end=source.indexOf('async function observePrefix',start);
 assert.ok(start>=0&&end>start,'actual runner readiness helper available');
 const ready=runInNewContext(`(${source.slice(start,end).trim()})`,{Date,Error,setTimeout,workerRows:async()=>[{frameworkRunningMs:1},{certifiedCatchupDomains:2}]});
 await assert.rejects(ready({child:{exitCode:null,signalCode:'SIGKILL'}}),/worker failed before ready/);
 await ready({child:{exitCode:null,signalCode:null}});
});
test('external plan freezes four executable trigger/barrier definitions', () => {
 assert.deepEqual(EXTERNAL_ARMS.map(row=>row.name),['producer-failure','committed-output-before-controller-ack','stale-owner-resume-after-takeover','majority-one-broker-unavailable']);
 for (const row of EXTERNAL_ARMS) { assert.ok(row.trigger); assert.ok(row.barrier); assert.ok(row.assertions.length); }
});
test('broker registration refuses missing, malformed, duplicate and unregistered IDs', () => {
 assert.deepEqual(validateRegistration(registration),registration);
 for(const change of [{containers:ids.slice(0,2)},{containers:[ids[0],ids[0],ids[2]]},{targetContainer:'4'.repeat(64)},{targetContainer:'redpanda0'},{composeProject:'wrong project'}]) assert.throws(()=>validateRegistration({...registration,...change}));
});
test('daemon inspection requires exact IDs, owned labels and two live peers', () => {
 validateInspection(registration,inspections());
 for(const mutate of [rows=>rows[1].State.Running=false,rows=>delete rows[1].State.Health,rows=>rows[0].Id='4'.repeat(64),rows=>rows[0].Config.Labels['reef.test']='unrelated',rows=>rows[0].Config.Labels['com.docker.compose.project']='other',rows=>rows[0].Config.Labels['com.docker.compose.service']='']) {
 const rows=inspections();mutate(rows);assert.throws(()=>validateInspection(registration,rows));
 }
});
test('restoration validates ownership even when peer health fails after outage', () => {
 const rows=inspections();rows[0].State.Running=false;rows[1].State.Running=false;
 validateInspection(registration,rows,{ownershipOnly:true});
 rows[0].Config.Labels['com.docker.compose.project']='unrelated';assert.throws(()=>validateInspection(registration,rows,{ownershipOnly:true}));
});
test('recovery requires identical physical prefix and exact stable end for idle restart', () => {
 assertSamePrefix(view(4),view(4));assertSamePrefix(view(4),view(8,20),true);
 assert.throws(()=>assertSamePrefix(view(4),view(4,11)));
 assertSamePrefix(view(4),view(4,11),false,true);
 assert.throws(()=>assertSamePrefix(view(4),view(4,9),false,true));
 const duplicate=view(8,20);duplicate.resultOffsets[0]=8;assert.throws(()=>assertSamePrefix(view(4),duplicate,true));
 assert.throws(()=>assertSamePrefix(view(4),{...view(4),pass:false}));
});
test('idle restart refuses checkpoint divergence despite unchanged economic output', () => {
 assert.throws(()=>assertSamePrefix(view(4),{...view(4),inputGroupCheckpoint:99}));
 assert.throws(()=>assertSamePrefix(view(4),{...view(4),lastInputOffset:99}));
});
test('stale owner requires observed broker fencing, never generic error or elapsed time', () => {
 assertFencing([{producerBoundary:'sendOffsetsToTransaction',failureClass:'org.apache.kafka.common.errors.ProducerFencedException'}]);
 assertFencing([{producerBoundary:'commitTransaction',failureClass:'org.apache.kafka.common.errors.CommitFailedException',causeClasses:['org.apache.kafka.clients.consumer.CommitFailedException']}]);
 for(const rows of [[],[{halted:'timeout'}],[{producerBoundary:'commitTransaction',failureClass:'java.lang.IllegalStateException'}]])assert.throws(()=>assertFencing(rows));
});
function harness({failDuringOutage=false}={}) {
 const calls=[],workers=[],boundaries=[];let prefixCount=0,full=false;
 const deps={
  init:async()=>calls.push('init'),seed:async n=>{prefixCount=n??8;calls.push(`seed:${prefixCount}`);},
  start:async fault=>{const worker={fault,index:workers.length};workers.push(worker);calls.push(`start:${fault}`);return worker;},
  ready:async()=>calls.push('ready'),stop:async(worker,hard)=>calls.push(`stop:${worker.index}:${hard}`),
  observe:async n=>{calls.push(`observe:${n??8}`);if(failDuringOutage&&calls.includes('outage:stop'))throw Error('oracle failure');return view(n??8,n===4?10:20);},
  append:async()=>{full=true;calls.push('append');},
  marker:async(worker,name)=>{calls.push(`marker:${name}`);return name==='producerBoundary'?{producerBoundary:'send',failureClass:'org.apache.kafka.common.errors.RecordTooLargeException'}:{faultBoundary:name};},
  crash:async()=>calls.push('crash'),signal:async(_,signal)=>calls.push(signal),resume:async()=>calls.push('resume'),
  fencing:async()=>{calls.push('fencing');return [{producerBoundary:'commitTransaction',failureClass:'org.apache.kafka.common.errors.ProducerFencedException'}];},
  boundary:async(row)=>{boundaries.push(row);calls.push(`boundary:${row.name}`);},ack:async()=>calls.push('controller-ack'),
  outage:async action=>{calls.push(`outage:${action}`);return {targetContainer:ids[0],running:action==='start',healthyPeers:2};},
 };
 return {deps,calls,boundaries};
}
test('producer failure arm invokes actual producer seam, observes zero abort outputs then restores',async()=>{
 const h=harness();h.deps.observe=async n=>{h.calls.push(`observe:${n??8}`);return h.calls.includes('crash')&&!h.calls.includes('start:')?{...view(0,0),pass:false}:view(8,20);};
 const result=await runExternalArm('producer-failure',h.deps);
 assert.equal(result.name,'producer-failure');assert.ok(h.calls.includes('start:production'));assert.ok(h.calls.indexOf('crash')<h.calls.indexOf('start:'));
});
test('committed-before-ACK arm kills after read-committed barrier and persists ACK only after stable recovery',async()=>{
 const h=harness();await runExternalArm('committed-output-before-controller-ack',h.deps);
 assert.ok(h.calls.indexOf('observe:4')<h.calls.indexOf('stop:0:true'));
 assert.ok(h.calls.indexOf('controller-ack')>h.calls.lastIndexOf('observe:8'));
 assert.equal(h.boundaries[0].controllerAckPersisted,false);
});
test('stale-owner arm freezes before forward, takes over, resumes and demands fencing before end-offset proof',async()=>{
 const h=harness();await runExternalArm('stale-owner-resume-after-takeover',h.deps);
 for(const [left,right] of [['marker:before-forward','SIGSTOP'],['SIGSTOP','start:'],['observe:8','SIGCONT'],['resume','fencing']])assert.ok(h.calls.indexOf(left)<h.calls.indexOf(right),`${left} precedes ${right}`);
 assert.equal(h.calls.filter(row=>row==='observe:8').length,3);
});
test('majority outage proves fresh outputs while target stopped and restores target on failure',async()=>{
 const h=harness();await runExternalArm('majority-one-broker-unavailable',h.deps);
 assert.ok(h.calls.indexOf('outage:stop')<h.calls.indexOf('append'));assert.ok(h.calls.indexOf('observe:8')<h.calls.indexOf('outage:start'));
 const failed=harness({failDuringOutage:true});await assert.rejects(runExternalArm('majority-one-broker-unavailable',failed.deps),/oracle failure/);
 assert.ok(failed.calls.includes('outage:start'));
});
test('failed START is not retried by majority-arm finally or granted new recovery time',async()=>{
 const h=harness();let starts=0;const outage=h.deps.outage;
 h.deps.outage=async action=>{if(action==='start'){starts++;throw Error('START failed');}return outage(action);};
 await assert.rejects(runExternalArm('majority-one-broker-unavailable',h.deps),/START failed/);assert.equal(starts,1);
});
async function runnerOutageHarness({peerFailure=false,slowStart=false}={}) {
 const source=await readFile(new URL('./broker-proof.mjs',import.meta.url),'utf8');
 const start=source.indexOf('async function docker(registration,action){'),end=source.indexOf('async function externalArm',start);
 const rows=inspections();rows.forEach(row=>Object.assign(row,{RestartCount:0,Image:`sha256:${'a'.repeat(64)}`,Mounts:[{Type:'volume',Name:`volume-${row.Id[0]}`,Destination:'/var/lib/redpanda/data'}]}));
 rows.forEach(row=>Object.assign(row.State,{Paused:false,Restarting:false,Dead:false,StartedAt:'2026-10-05T01:00:00Z'}));
 const resources=rows.map(row=>({id:row.Id,labels:row.Config.Labels,imageId:row.Image,volumes:[{name:row.Mounts[0].Name,destination:row.Mounts[0].Destination}]}));
 const registered={...registration,resources,phaseProtocol:{schema:'calcify-broker-fault-phase-v1',runId:'runner-control',maxCycles:1,recoveryTimeoutMs:30000,stopTimeoutMs:30000,
   directory:'/tmp/runner-proof/broker-fault',clock:{platform:'darwin',node:'22.22.1',uv:'1.51.0'}}};
 let now=10000,seq=0,recoveryDeadline;const calls=[],generations=Object.fromEntries(rows.map(row=>[row.Id,{startedAt:row.State.StartedAt,restartCount:0}]));
 const client={request:async action=>{calls.push(`grant:${action}`);seq++;if(action==='start')recoveryDeadline=now+30000;
  return {authorized:true,seq,action,generations,stopDeadlineMs:40000,recoveryDeadlineMs:recoveryDeadline,targetGeneration:action==='recovered'?rows[0].State.StartedAt:null};}};
 const executeFile=async(_,args,options)=>{
  calls.push(args[0]);assert.ok(options.timeout<=30000);
  if(args[0]==='stop')Object.assign(rows[0].State,{Running:false,Status:'exited'});
  if(args[0]==='start'){Object.assign(rows[0].State,{Running:true,Status:'running',StartedAt:'2026-10-05T02:00:00Z',Health:{Status:'starting',Log:[]}});if(peerFailure)rows[1].State.Health.Status='unhealthy';if(slowStart)now+=30001;}
  return {stdout:args[0]==='inspect'?JSON.stringify(rows):'',stderr:''};
 };
 const docker=runInNewContext(`let faultClient,lastFaultGrant;(${source.slice(start,end).trim()})`,{
  validateRegistration,validateInspection:(reg,value,options)=>validateInspection(reg,value,{...options,now}),createPhaseClient:()=>client,phaseDirectory:registered.phaseProtocol.directory,
  hostMonotonicMs:()=>now,Date,Error,Math,JSON,executeFile,proof:'/tmp/runner-proof',join:(...parts)=>parts.join('/'),appendFile:async()=>{},
  setTimeout:fn=>{now+=250;rows[0].State.Health={Status:'healthy',Log:[{Start:'2026-10-05T02:00:03Z',End:'2026-10-05T02:00:04Z',ExitCode:0}]};queueMicrotask(fn);},
 });
 return {docker,registration:registered,calls,deadline:()=>recoveryDeadline};
}
test('actual runner outage adapter waits for STOP and stopped grants then uses fixed START generation deadline',async()=>{
 const h=await runnerOutageHarness();await h.docker(h.registration,'stop');const result=await h.docker(h.registration,'start');
 assert.equal(result.targetHealthy,true);assert.equal(h.deadline(),40000);
 assert.ok(h.calls.indexOf('grant:stop')<h.calls.indexOf('stop'));assert.ok(h.calls.indexOf('grant:stopped')<h.calls.indexOf('grant:start'));
 assert.ok(h.calls.indexOf('grant:start')<h.calls.indexOf('start'));assert.ok(h.calls.includes('grant:recovered'));
});
test('actual runner recovery rejects peer failure immediately and slow START cannot reset fixed deadline',async()=>{
 for(const options of [{peerFailure:true},{slowStart:true}]) {
  const h=await runnerOutageHarness(options);await h.docker(h.registration,'stop');
  await assert.rejects(h.docker(h.registration,'start'),/healthcheck|deadline/);assert.equal(h.calls.filter(action=>action==='start').length,1);
  assert.equal(h.calls.includes('grant:recovered'),false);assert.equal(h.deadline(),40000);
 }
});
