import test from 'node:test';
import assert from 'node:assert/strict';
import { validateCohort, validateCanonical, containerFingerprint, assertIdentity, buildRate, validateHealth } from './full-projection-headroom.mjs';
const cohort = {pass:true,checks:{a:true},totals:{accepted:'2',sourceOffsetSpan:'2',directAcked:'2',materializedMembership:'2'},partitions:[{partition:0,startOffsetInclusive:'0',endOffsetExclusive:'2',accepted:'2',sourceOffsetSpan:'2',directAcked:'2',materializedMembership:'2',exclusive:true,joined:true,exactCanonicalMembership:true}]};
test('exact command accounting rejects dirty-row substitution, bad source checks and numeric strings',()=>{
 assert.equal(validateCohort(cohort,'2').count,2n);
 for(const [c,n] of [[cohort,'1'],[{...cohort,checks:{a:false}},'2'],[{...cohort,totals:{...cohort.totals,accepted:2}},'2']]) assert.throws(()=>validateCohort(c,n));
 assert.throws(()=>validateCohort({...cohort,partitions:[...cohort.partitions,...cohort.partitions]},'2'));
});
test('direct canonical proof requires exact unique interval and all cohort commands',()=>{
 const v=validateCohort(cohort,'2');const rows=[{partition:0,count:'2',distinct:'2',min:'1',max:'2'}];
 validateCanonical({total:'2',rows},v);
 for(const patch of [{count:'1'},{distinct:'1'},{min:'0'},{max:'3'}])assert.throws(()=>validateCanonical({total:'2',rows:[{...rows[0],...patch}]},v));
 assert.throws(()=>validateCanonical({total:'3',rows},v));
});
test('identity includes environment, host resources and mounts; startup transition explicit',()=>{
 const c={Id:'a',Image:'sha256:x',Config:{Env:['A=1']},HostConfig:{Memory:100},Mounts:[],State:{Running:false,StartedAt:'old'},RestartCount:0};
 const before=containerFingerprint(c); assertIdentity(before,c,false);
 for(const changed of [{...c,Config:{Env:['A=2']}},{...c,HostConfig:{Memory:200}},{...c,Id:'b'}])assert.throws(()=>assertIdentity(before,changed,false));
 assert.throws(()=>assertIdentity(before,{...c,State:{Running:true}},false));
});
test('rate clock starts before dispatch and never subtracts startup; thresholds exact',()=>{
 const r=buildRate('150000',0n,1n,25_000_000_000n,5000);assert.equal(r.pass,true);assert.equal(r.sourceCommandsPerFullDrainSecond,6000);
 assert.equal(buildRate('150000',0n,1n,25_000_000_001n,5000).pass,false);
 assert.throws(()=>buildRate('150000',2n,1n,25_000_000_000n,5000));
 assert.throws(()=>buildRate('150000',0n,1n,0n,5000));
});
test('canonical health rejects missing, negative, unsafe or failed evidence',()=>{
 const s={status:'running',projectionName:'p',source:'venue-event-batch',eventStream:'events',projectionStage:'full',metrics:{projected:0,failed:0,retryAttempts:0,retryExhausted:0},lag:2};
 validateHealth(s,{sourceProjectionName:'p',eventStream:'events'});
 for(const metrics of [{...s.metrics,failed:-1},{...s.metrics,failed:1},{...s.metrics,retryAttempts:undefined},{...s.metrics,projected:2**54}])assert.throws(()=>validateHealth({...s,metrics},{sourceProjectionName:'p',eventStream:'events'}));
});

import { validateStage, validateManifest } from './full-projection-headroom.mjs';
const generation='2026-09-24T00:00:00Z';
const stageExpected={pollIntervalMs:250,batchSize:500,callers:['worker','market-nested'],maxConcurrent:2};
function readyStage(){return {enabled:true,pollIntervalMs:250,batchSize:500,metrics:{failed:0,processedRows:2},instrumentation:{enabled:true,stageEnabled:true,sampleIntervalMs:1000,dirtyQueues:{orderLifecyclePending:0,marketDataPending:0,databaseGeneration:generation},callerStats:{calls:2,completed:2,failed:0,active:0,maxConcurrent:1,callerCount:2,callers:{worker:1,'market-nested':1}},coverage:{databaseGeneration:generation,generationGuardFailures:0,clockGuardFailures:0,lastMarker:{markerId:'m',postCommitObserved:true,sourceProjectionName:'p',databaseGeneration:generation,databaseSnapshotAt:generation,postCommitObservedAt:generation,sourceWatermarks:[{partition:0,lastPartitionSequence:'2'}]}}}};}
test('canonical completion alone cannot finish while queues, callers or exact markers incomplete',()=>{
 const v=validateCohort(cohort,'2');validateStage(readyStage(),stageExpected,{sourceProjectionName:'p'},generation,v,true);
 const mutations=[s=>s.instrumentation.dirtyQueues.marketDataPending=1,s=>s.instrumentation.callerStats.active=1,s=>s.instrumentation.coverage.lastMarker.sourceWatermarks[0].lastPartitionSequence='1',s=>s.instrumentation.coverage.lastMarker.sourceProjectionName='wrong',s=>delete s.instrumentation.coverage.lastMarker,s=>s.instrumentation.coverage.databaseGeneration='2026-09-25T00:00:00Z',s=>s.instrumentation.callerStats.callers={foreign:2},s=>s.instrumentation.callerStats.maxConcurrent=3,s=>s.instrumentation.sampleIntervalMs=2000,s=>s.metrics.failed=-1];
 for(const mutate of mutations){const s=readyStage();mutate(s);assert.throws(()=>validateStage(s,stageExpected,{sourceProjectionName:'p'},generation,v,true));}
});
test('manifest refuses guessed isolation, duplicate ownership, absent exact config and client allowlist',()=>{
 const id='a'.repeat(64),db1='b'.repeat(64),db2='c'.repeat(64);const client={address:'172.18.0.2',applicationName:'runtime',user:'reef'};
 const m={schemaVersion:1,project:'test',fixtureId:'fixed',dedicatedFixtureNoExternalWriters:true,sourceCommands:'2',sourceProjectionName:'p',eventStream:'events',commandStream:'commands',marketProjectionName:'market',preloadSha256:'d'.repeat(64),targetCommandsPerSecond:5000,timeoutMs:10000,containers:[{id,configSha256:'e'.repeat(64),runningBefore:false},{id:db1,configSha256:'f'.repeat(64),runningBefore:true},{id:db2,configSha256:'f'.repeat(64),runningBefore:true}],projectors:[{id,port:8080,partitions:[0]}],lifecycle:{id,...stageExpected},market:{id,...stageExpected},canonicalDb:{id:db1,user:'reef',database:'reef',allowedClients:[client]},projectionDb:{id:db2,user:'reef',database:'reef',allowedClients:[client]}};
 validateManifest(m);
 validateManifest({...m,canonicalCaptureTimeoutMs:300000});
 for(const value of [null,0,-1,'300000',900001,NaN])assert.throws(()=>validateManifest({...m,canonicalCaptureTimeoutMs:value}));
 for(const mutate of [x=>delete x.dedicatedFixtureNoExternalWriters,x=>x.projectors.push(x.projectors[0]),x=>delete x.containers[0].configSha256,x=>x.canonicalDb.allowedClients=[]]){const bad=structuredClone(m);mutate(bad);assert.throws(()=>validateManifest(bad));}
});
import { validateJournal } from './full-projection-headroom.mjs';
test('journal binds exact immutable source membership and rejects drops, restart and duplicates',()=>{
 const start={enabled:true,instanceId:'id',lastSequence:'0',dropped:'0',duplicate:'0',invalid:'0',records:[]};
 const row={sequence:'1',batchId:'batch',payloadChecksum:'a'.repeat(64),commandStream:'commands',partition:0,commandCount:'2',streamSequences:['1','2'],workFinishedAt:generation,canonicalCommitObservedAt:generation};
 const timing={before:[start],after:[{...start,lastSequence:'1',records:[row]}]},v=validateCohort(cohort,'2');
 assert.equal(validateJournal(timing,v,'commands').size,1);
 for(const mutate of [t=>t.after[0].instanceId='restart',t=>t.after[0].dropped='1',t=>t.after[0].records[0].streamSequences=['1','1'],t=>t.after[0].records[0].streamSequences=['1','3'],t=>t.after[0].records=[],t=>t.after[0].records[0].commandStream='other']){const bad=structuredClone(timing);mutate(bad);assert.throws(()=>validateJournal(bad,v,'commands'));}
});

import { createCommandRecorder, createCanonicalVerifier, streamCanonicalRows } from './full-projection-headroom.mjs';
import { Readable } from 'node:stream';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

test('inspect success and failure artifacts never expose raw environment',async()=>{
 for(const fails of [false,true]){
  const saved=new Map(),secret='SENSITIVE_VALUE';
  const execute=async()=>{const r={stdout:JSON.stringify([{Config:{Env:[`TOKEN=${secret}`]}}]),stderr:secret};if(fails)throw Object.assign(Error(secret),r);return r;};
  const command=createCommandRecorder((name,value)=>saved.set(name,value),execute);
  if(fails)await assert.rejects(command(['inspect','id']),e=>!String(e).includes(secret));
  else assert.match(await command(['inspect','id']),/SENSITIVE_VALUE/); // private caller still fingerprints full config
  assert.ok(!JSON.stringify([...saved]).includes(secret));
  assert.equal(saved.get('0001.stdout').redacted,true);
 }
});
function canonicalFixture(){
 const v=validateCohort(cohort,'2');
 const journal=new Map([['batch',{partition:0,commandCount:'2',payloadChecksum:'a'.repeat(64),streamSequences:['1','2']}]]);
 const manifest={commandStream:'commands',eventStream:'events'};
 const outcome=seq=>({outcome:{command_id:`cmd-${seq}`,batch_id:'batch',partition_id:0,stream_sequence:seq,command_stream:'commands',event_stream:'events',result_payload:{unchanged:'é full business payload'}}});
 const batch={batch:{batch_id:'batch',partition_id:0,command_count:2,payload_checksum:'a'.repeat(64),command_stream:'commands',event_stream:'events',payload_json:{large:'retained on disk'}}};
 return {v,journal,manifest,rows:[outcome('1'),outcome('2'),batch]};
}
test('canonical stream hashes and retains exact UTF8 bytes while validating memberships',async()=>{
 const f=canonicalFixture(),raw=Buffer.from(f.rows.map(JSON.stringify).join('\n')+'\n');
 const dir=mkdtempSync(join(tmpdir(),'reef-canonical-stream-'));
 try{
  const result=await streamCanonicalRows(Readable.from([...raw].map(x=>Buffer.from([x]))),join(dir,'rows.ndjson'),createCanonicalVerifier(f.v,f.journal,f.manifest));
  assert.equal(result.sha256,createHash('sha256').update(raw).digest('hex'));
  assert.deepEqual(readFileSync(join(dir,'rows.ndjson')),raw);
  assert.deepEqual(result.summary,{total:'2',rows:[{partition:0,count:'2',distinct:'2',min:'1',max:'2'}]});
 }finally{rmSync(dir,{recursive:true});}
});
test('stream verifier rejects missing, foreign, duplicate and conflicting source evidence',()=>{
 const mutations=[r=>r.pop(),r=>r.shift(),r=>r.push(r[0]),r=>r.push(r[2]),r=>r[0].outcome.command_stream='foreign',r=>r[0].outcome.event_stream='foreign',r=>r[0].outcome.stream_sequence='3',r=>r[1].outcome.command_id=r[0].outcome.command_id,r=>r[2].batch.payload_checksum='b'.repeat(64),r=>r[2].batch.command_count=1,r=>r[2].batch.partition_id=1];
 for(const mutate of mutations){const f=canonicalFixture();mutate(f.rows);const verifier=createCanonicalVerifier(f.v,f.journal,f.manifest);assert.throws(()=>{for(const row of f.rows)verifier.accept(row);verifier.finish();});}
});
test('live non-atomic counter samples remain bounded but final reconciliation strict',()=>{
 const s=readyStage();s.instrumentation.callerStats.calls=1;
 validateStage(s,stageExpected,{sourceProjectionName:'p'},generation,validateCohort(cohort,'2'));
 assert.throws(()=>validateStage(s,stageExpected,{sourceProjectionName:'p'},generation,validateCohort(cohort,'2'),true));
 for(const mutate of [x=>x.instrumentation.callerStats.failed=1,x=>x.instrumentation.callerStats.callers.foreign=1,x=>delete x.instrumentation.callerStats.calls]){
  const bad=readyStage();mutate(bad);assert.throws(()=>validateStage(bad,stageExpected,{sourceProjectionName:'p'},generation,validateCohort(cohort,'2')));
 }
});
test('interrupted canonical stream cannot return successful evidence',async()=>{
 const f=canonicalFixture(),dir=mkdtempSync(join(tmpdir(),'reef-canonical-interrupted-'));
 async function* broken(){yield Buffer.from(JSON.stringify(f.rows[0])+'\n');throw Error('source read failed');}
 try{await assert.rejects(streamCanonicalRows(Readable.from(broken()),join(dir,'partial.ndjson'),createCanonicalVerifier(f.v,f.journal,f.manifest)),/source read failed/);assert.ok(readFileSync(join(dir,'partial.ndjson')).length>0);}finally{rmSync(dir,{recursive:true});}
});
