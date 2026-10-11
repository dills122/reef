#!/usr/bin/env bun
// Actual isolated engine gates. Requires manager full lease and frozen context;
// never releases admissions or resets retained canonical history.
import {readFile,writeFile,lstat} from 'node:fs/promises';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {Supervisor,ROOT,sha256,noSecretSpill,validateFlow} from './finite-o2-proof.mjs';

export const CASES=Object.freeze(['topic-identity','command-history','scope','published-suffix']);
const check=(ok,why)=>{if(!ok)throw Error(why);};
const decimal=/^(0|[1-9][0-9]*)$/;
const scriptPath='scripts/dev/calcify-financial/finite-o2-engine-adverse.mjs';

export function validateCaseState(kind,lease,snapshot){
  check(CASES.includes(kind)&&lease.scope==='full'&&lease.adverse===kind,'dedicated fresh case lease');
  const r=snapshot.run,j=snapshot.journal;
  check(r.admissionState==='STOPPED'&&r.unresolvedPublications==='0','stopped resolved admission required');
  const count=kind==='command-history'?'1':kind==='published-suffix'?'12':'0';
  check(r.possiblePublications===count&&j.length===Number(count),'exact finite case publication count');
  check(j.every(e=>e.state==='PUBLISHED'&&decimal.test(e.offset)&&decimal.test(e.streamSequence)&&BigInt(e.streamSequence)===BigInt(e.offset)+1n),'actual published journal required');
  check(new Set(j.map(e=>e.offset)).size===j.length,'duplicate published offsets');
  return count;
}
export function assertObserver(receipt,{refusal}={}){
  check(receipt.schema==='calcify-finite-probe-result-v1'&&receipt.mode==='matcher-observe','real matcher observer receipt');
  if(refusal){check(receipt.status==='refused'&&receipt.code===refusal,'actual production refusal required');return;}
  check(receipt.status==='ok'&&receipt.data?.ClusterID&&receipt.data?.Topics?.length===5&&receipt.data?.Cuts,'positive actual matcher observation required');
}
export function assertExited(state){check(state.Running===false&&state.Status==='exited'&&Number.isInteger(state.ExitCode)&&state.ExitCode!==0,'nonzero actual matching process exit required');}
export function assertUnreachable(receipt){check(receipt.schema==='calcify-finite-probe-result-v1'&&receipt.mode==='http'&&receipt.status==='error'&&receipt.code==='operation_failed','closed engine listener transport required; HTTP denial/deadline insufficient');}
export function semanticRecords(history){
  check(Array.isArray(history.records),'actual committed history records');
  return history.records.map(r=>({offset:r.offset,payloadSha256:r.payloadSha256,keyBase64:r.keyBase64,valueBase64:r.valueBase64,headers:r.headers}));
}
export function assertNoNewRecords(before,after){check(JSON.stringify(before.map(semanticRecords))===JSON.stringify(after.map(semanticRecords)),'startup refusal changed committed canonical data');}
export function assertLostGenesis(receipt){
  const d=receipt.data;
  check(receipt.schema==='calcify-finite-probe-result-v1'&&receipt.mode==='history'&&receipt.status==='error'&&receipt.code==='history_lost_genesis'&&d?.phase==='lost_genesis'&&d.codec===-1&&d.fetchCount===0&&d.payloadBytes===0&&d.position==='0'&&d.readCommittedEndOffset==='-1'&&d.recordCount===0,'exact COMMAND lost-genesis history refusal required');
}
export function assertCommandHistoryCut(opening,mutation,before,afterMutation,after){
  const t=opening.topics[0];
  check(t.role==='COMMAND'&&mutation.adverse==='command-history'&&mutation.topic===t.name&&mutation.oldUuid===t.topicId&&mutation.newUuid===t.topicId&&mutation.beginBefore==='0'&&mutation.endBefore==='1'&&mutation.beginAfter==='1'&&mutation.endAfter==='1','exact registered COMMAND genesis cut required');
  const range=(r,begin)=>r.topic===t.name&&r.beginningOffset===begin&&r.endOffset==='1'&&r.readCommittedEndOffset==='1';
  check(range(before.ranges[0],'0'),'actual COMMAND pre-cut range required');
  for(const observation of [afterMutation,after]){
    assertLostGenesis(observation.commandHistoryRefusal);
    const m=observation.commandMetadata?.topics;
    check(range(observation.ranges[0],'1')&&m?.length===1&&m[0].name===t.name&&m[0].uuid===t.topicId&&m[0].partitions===1&&m[0].lowOffset==='1'&&m[0].highWatermark==='1','actual unchanged UUID and COMMAND post-cut range required');
    assertNoNewRecords(before.histories.slice(1),observation.histories.slice(1));
    check(JSON.stringify(before.ranges.slice(1))===JSON.stringify(observation.ranges.slice(1))&&JSON.stringify(before.command)===JSON.stringify(observation.command)&&JSON.stringify(before.capture)===JSON.stringify(observation.capture),'startup refusal changed SOURCE/CAPTURE ranges or group cuts');
  }
}
export function assertSuffixMembership(commands,source,beforeSource){
  const commandIDs=commands.records.map(r=>JSON.parse(Buffer.from(r.valueBase64,'base64')).commandId);
  const batches=source.records.map(r=>JSON.parse(Buffer.from(r.valueBase64,'base64')));
  check(commandIDs.length===12&&new Set(commandIDs).size===12,'exact retained canonical command membership');
  const outcomes=batches.flatMap(b=>b.outcomes??[]).map(o=>o.commandId);
  check(batches.length===12&&batches.every((b,i)=>b.outcomes?.length===1&&b.outcomes[0].commandId===commandIDs[i])&&outcomes.length===12&&new Set(outcomes).size===12,'suffix reordered, duplicated or missing lifecycle outcome');
  check(beforeSource.records.every(r=>source.records.some(a=>JSON.stringify(a)===JSON.stringify(r))),'committed source prefix lost/rewritten');
  check(batches.flatMap(b=>b.outcomes.flatMap(o=>o.result.trades??[])).length===3,'finite fixture trade cardinality');
}
async function observed(s,{lostCommandGenesis=false}={}){
  const histories=[],ranges=[];
  let commandHistoryRefusal=null,commandMetadata=null;
  for(const t of s.opening.topics.slice(0,3)){
    const q={mode:'history',auth:await s.auth('observer'),topic:t.name};
    if(lostCommandGenesis&&t===s.opening.topics[0]){commandHistoryRefusal=await s.probeRaw(q);assertLostGenesis(commandHistoryRefusal);histories.push(null);}
    else histories.push(await s.probe(q));
    ranges.push(await s.probe({mode:'range',auth:await s.auth('operator'),topic:t.name}));
  }
  if(lostCommandGenesis)commandMetadata=await s.probe({mode:'metadata',auth:await s.auth('operator'),topics:[s.opening.topics[0].name]});
  const command=await s.probe({mode:'group-offset',auth:await s.auth('operator'),topic:s.opening.topics[0].name,group:s.opening.commandConsumerGroup});
  const capture=await s.probe({mode:'group-offset',auth:await s.auth('operator'),topic:s.opening.topics[1].name,group:s.opening.captureApplicationId});
  return {histories,ranges,command,capture,...(lostCommandGenesis?{commandHistoryRefusal,commandMetadata}:{})};
}
async function matcherObserve(s,refusal){
  const result=await s.probeRaw({mode:'matcher-observe',opening:s.opening,digest:s.digest,auth:await s.auth('matcher')});
  assertObserver(result,{refusal});return result;
}
async function ownedState(s,service){return s.inspectOwned(service,s.lease.images[service]);}
async function stopMatcher(s){
  const before=await ownedState(s,'matcher');
  if(before.State.Running)await s.command(['stop','--time','10',s.ids.matcher],{timeoutMs:20000,record:'adverse-matcher-stop'});
  const after=await ownedState(s,'matcher');check(!after.State.Running&&after.State.Status==='exited','owned matcher not stopped');
  await s.appLifecycleWitness('matcher',{running:false});return after;
}
async function containRuntime(s){
  const before=await ownedState(s,'runtime');
  if(before.State.Running)return s.containRuntime();
  check(before.State.Status==='exited','owned runtime not contained');
  await s.appLifecycleWitness('runtime',{running:false});
  return s.writeReceipt('adverse-already-contained-runtime',{id:s.ids.runtime,image:s.lease.images.runtime,state:before.State,sourceManifestSha256:s.artifactManifestSha256});
}
async function refusal(s,kind){
  await s.startServices('matcher',{appStartup:'expected-refusal'});
  const exited=await s.waitFor('actual-matcher-refusal',()=>ownedState(s,'matcher'),r=>!r.State.Running&&r.State.Status==='exited',45000);
  assertExited(exited.State);
  const logs=await s.command(['logs','--tail','100',s.ids.matcher],{maxBytes:262144,record:'adverse-matcher-refusal-log'});
  const text=logs.stdout+logs.stderr;
  check(/finite|Calcify/.test(text)&&/failed to start matching-engine direct stream runner/i.test(text),'finite startup failure evidence missing');
  if(kind==='scope')check(text.includes('finite effective routing/config mismatch'),'deliberate effective routing refusal missing');
  await s.writeReceipt('adverse-process-refusal-state',{case:kind,matcherId:s.ids.matcher,state:exited.State,logSHA256:sha256(text)});
  const health=await s.probeRaw({mode:'http',host:'matcher:8081',path:'/health',method:'GET',bearer:''});assertUnreachable(health);
  const lifecycleWitness=await s.appLifecycleWitness('matcher',{running:false});
  return {matcherState:exited.State,matcherId:s.ids.matcher,logSHA256:sha256(text),health,lifecycleWitness,boundary:kind==='scope'?'Go effective config':'System startup may refuse JVM authoritative witness before Go broker re-observation'};
}
async function scopeEnv(s){
  const original=s.env.FINITE_MATCHER_ENV_FILE,stat=await lstat(original);
  check(stat.isFile()&&!stat.isSymbolicLink(),'owned nonsecret matcher env regular file');
  const raw=await readFile(original,'utf8');noSecretSpill(raw,s.secrets);check(Buffer.byteLength(raw)<=32768&&raw.includes('MATCHING_ENGINE_SHARD_ID='+s.opening.matcherShardId+'\n'),'original bound shard config');
  const replacement=raw.replace('MATCHING_ENGINE_SHARD_ID='+s.opening.matcherShardId+'\n','MATCHING_ENGINE_SHARD_ID='+s.lease.prefix+'-wrong-shard\n');
  noSecretSpill(replacement,s.secrets);const target=join(s.lease.proofRoot,'matcher.scope-refusal.env');await writeFile(target,replacement,{mode:0o600,flag:'wx'});s.env.FINITE_MATCHER_ENV_FILE=target;
  return {originalSHA256:sha256(raw),overrideSHA256:sha256(replacement),field:'MATCHING_ENGINE_SHARD_ID',value:s.lease.prefix+'-wrong-shard'};
}
export async function runEngineAdverse(s,kind){
  const beforeSnapshot=await s.snapshot();validateCaseState(kind,s.lease,beforeSnapshot);
  const before=await observed(s),positive=await matcherObserve(s);
  const durableCounts=r=>({recordedAttempts:r.recordedAttempts,possiblePublications:r.possiblePublications,commandPayloadBytes:r.commandPayloadBytes,attemptRecordBytes:r.attemptRecordBytes});
  let mutation=null,direct=null,system=null;
  if(kind==='published-suffix'){
    check(decimal.test(before.command.offset)&&BigInt(before.command.offset)<12n,'known published suffix absent');
    check(!(await ownedState(s,'matcher')).State.Running,'published suffix requires manager-contained matcher');
    await s.startServices('matcher');
    const ready=await s.waitFor('published-suffix-drained',()=>s.readiness(),r=>r.cuts.commandGroup.nextOffset==='12',60000);
    const after=await observed(s);assertSuffixMembership(after.histories[0],after.histories[1],before.histories[1]);
    const afterSnapshot=await s.snapshot(),template=JSON.parse(await readFile(join(ROOT,'scripts/dev/calcify-financial/finite-o2/fixture.json')));
    const canonicalProof=validateFlow(template,s.opening,s.digest,...after.histories,afterSnapshot.journal);
    check(JSON.stringify(durableCounts(afterSnapshot.run))===JSON.stringify(durableCounts(beforeSnapshot.run)),'drain rewrote durable admission accounting');
    return s.writeReceipt('actual-engine-suffix-proof',{schema:'calcify-finite-o2-engine-adverse-v1',case:kind,positive,before,after,canonicalProof,readiness:ready,sourceManifestSha256:s.artifactManifestSha256,observed:'Actual published suffix drained; independent same-history snapshot reference belongs canonical Supervisor.restart receipt; not asserted here'});
  }
  await stopMatcher(s);
  if(kind==='scope')mutation=await scopeEnv(s);
  else{
    await containRuntime(s);
    mutation=await s.adverseMutation(kind==='topic-identity'?'recreate-source':'truncate-command-genesis');
    direct=await matcherObserve(s,kind==='topic-identity'?'topic_identity_mismatch':'history_missing_prefix');
    // Real JVM may itself reject changed UUID/history. Never replace its
    // authoritative endpoint with a fake witness to force a Go-only branch.
    s.runtimeFaultActive=false;s.env.FINITE_RUNTIME_ENTRYPOINT='/fixture/platform-runtime/bin/platform-runtime';s.env.FINITE_RUNTIME_COMMAND='';
    await s.startServices('runtime',{appStartup:'expected-refusal'});
    const runtime=await s.waitFor('actual-runtime-refusal',()=>ownedState(s,'runtime'),r=>!r.State.Running&&r.State.Status==='exited',45000);
    assertExited(runtime.State);check(runtime.State.OOMKilled===false,'runtime startup OOM cannot prove refusal');
    const logs=await s.command(['logs','--tail','100',s.ids.runtime],{maxBytes:262144,record:'adverse-runtime-startup-log'});
    const lifecycleWitness=await s.appLifecycleWitness('runtime',{running:false});
    await s.writeReceipt('adverse-runtime-startup-state',{runtimeId:s.ids.runtime,state:runtime.State,logSHA256:sha256(logs.stdout+logs.stderr),lifecycleWitness});
  }
  const observationOptions={lostCommandGenesis:kind==='command-history'};
  const afterMutation=await observed(s,observationOptions);system=await refusal(s,kind);const after=await observed(s,observationOptions);
  if(kind==='command-history')assertCommandHistoryCut(s.opening,mutation,before,afterMutation,after);
  else assertNoNewRecords(afterMutation.histories,after.histories);
  const afterSnapshot=await s.snapshot();check(JSON.stringify(durableCounts(afterSnapshot.run))===JSON.stringify(durableCounts(beforeSnapshot.run)),'startup refusal changed charged admission accounting');
  await s.sample();
  return s.writeReceipt('actual-engine-refusal-proof',{schema:'calcify-finite-o2-engine-adverse-v1',case:kind,positive,direct,mutation,system,before,afterMutation,after,beforeSnapshot,afterSnapshot,sourceManifestSha256:s.artifactManifestSha256,coverage:'Direct real Go observer UUID/genesis check plus system startup refusal; private journal/hash verifier covered by focused tests, not this direct probe'});
}
async function main(){
  const args=process.argv.slice(2);check(args.length===3,'Usage: finite-o2-engine-adverse.mjs LEASE_JSON CONTEXT_JSON CASE');
  const [leasePath,contextPath,kind]=args;const raw=await readFile(resolve(leasePath));check(raw.length<=32768,'lease byte cap');
  const lease=JSON.parse(raw),s=new Supervisor(lease);await s.restoreContext(resolve(contextPath));
  const manifest=JSON.parse(await readFile(lease.artifacts.sourceManifest));const self=await readFile(join(ROOT,scriptPath));
  check(manifest.sources.some(f=>f.path===scriptPath&&f.bytes===self.length&&f.sha256===sha256(self)),'adverse script absent/drifted frozen manifest');
  const result=await runEngineAdverse(s,kind);console.log(JSON.stringify(result));
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{console.error('FINITE_O2_ENGINE_ADVERSE_FAILED; retained bounded receipts require research before retry');process.exitCode=1;});
