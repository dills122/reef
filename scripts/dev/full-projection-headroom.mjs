#!/usr/bin/env node
// Fixed-backlog diagnostic. Starts/stops ONLY explicit pre-created projector IDs.
// No schema, business SQL, worker configuration, or existing measurement authority changes.
import { execFile, spawn } from 'node:child_process';
import { StringDecoder } from 'node:string_decoder';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync, appendFileSync, mkdirSync, openSync, writeSync, closeSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { buildUpstreamSourceCohort } from './lib/source-cohort.mjs';
import { buildReferenceScript, verifyReferenceOutput } from './downstream-state-reference.mjs';
const exec = promisify(execFile);
const check = (ok, why) => { if (!ok) throw Error(why); };
const hash = text => createHash('sha256').update(text).digest('hex');
const stable = x => JSON.stringify(x, (_,v) => v && typeof v==='object' && !Array.isArray(v) ? Object.fromEntries(Object.entries(v).sort(([a],[b])=>a.localeCompare(b))) : v);
const exact = x => { check(typeof x==='string' && /^(0|[1-9][0-9]*)$/.test(x),'exact decimal string required'); return BigInt(x); };
const number = x => { check(Number.isSafeInteger(x) && x>=0,'missing/negative/unsafe numeric evidence'); return x; };
const zero = x => number(x)===0;
const literal = x => { check(typeof x==='string' && x.length>0 && !x.includes('\0'),'nonempty SQL text required');return `convert_from(decode('${Buffer.from(x).toString('hex')}','hex'),'UTF8')`; };
const gen = x => { check(typeof x==='string' && Number.isFinite(Date.parse(x)),'database generation missing');return x; };

export function validateCohort(c, count) {
 const n=exact(count);check(n>0n && c?.pass===true && Object.keys(c.checks??{}).length>0 && Object.values(c.checks).every(x=>x===true),'source proof failed/missing');
 for(const k of ['accepted','sourceOffsetSpan','directAcked','materializedMembership'])check(exact(c.totals?.[k])===n,'source command denominator mismatch');
 check(Array.isArray(c.partitions)&&c.partitions.length>0,'source partitions absent');const seen=new Set();let sum=0n;
 const partitions=c.partitions.map(p=>{
  number(p.partition);check(p.partition<32768&&!seen.has(p.partition),'duplicate/invalid partition');seen.add(p.partition);
  const start=exact(p.startOffsetInclusive),end=exact(p.endOffsetExclusive),span=end-start;
  check(start===0n&&span>0n&&end<(1n<<48n),'fresh exclusive zero-offset cohort required');
  check(p.exclusive===true&&p.joined===true&&p.exactCanonicalMembership===true,'partition membership proof missing');
  for(const k of ['accepted','sourceOffsetSpan','directAcked','materializedMembership'])check(exact(p[k])===span,'partition count mismatch');
  sum+=span;return {partition:p.partition,count:span.toString(),min:((BigInt(p.partition)<<48n)+1n).toString(),max:((BigInt(p.partition)<<48n)+end).toString()};
 });check(sum===n,'partition sum mismatch');return {count:n,partitions:partitions.sort((a,b)=>a.partition-b.partition)};
}
export function validateCanonical(s,v) {
 check(exact(s.total)===v.count&&Array.isArray(s.rows)&&s.rows.length===v.partitions.length,'canonical total/vector mismatch');
 for(const p of v.partitions){const r=s.rows.filter(r=>r.partition===p.partition);check(r.length===1,'canonical partition missing/duplicate');for(const k of ['count','min','max'])check(r[0][k]===p[k],'canonical interval mismatch');check(r[0].distinct===p.count,'canonical duplicate member');}
}
export function validateJournal(timing,v,stream) {
 check(Array.isArray(timing?.before)&&timing.before.length>0&&Array.isArray(timing.after)&&timing.after.length===timing.before.length,'timing journals missing');
 const starts=new Map(timing.before.map(s=>[s.instanceId,s]));check(starts.size===timing.before.length,'duplicate journal instance');
 const seenInstances=new Set(),records=new Map(),members=new Set();
 for(const end of timing.after){
  const start=starts.get(end.instanceId);check(start&&typeof end.instanceId==='string'&&end.instanceId.length>0&&!seenInstances.has(end.instanceId)&&start.enabled===true&&end.enabled===true,'journal disabled/restarted');seenInstances.add(end.instanceId);
  for(const k of ['dropped','duplicate','invalid'])check(start[k]==='0'&&end[k]==='0',`journal ${k}`);
  const lo=exact(start.lastSequence),hi=exact(end.lastSequence);check(hi>=lo&&Array.isArray(end.records),'journal sequence regressed');
  const selected=end.records.filter(r=>exact(r.sequence)>lo);check(BigInt(selected.length)===hi-lo,'journal lost records');
  selected.forEach((r,index)=>{
   check(exact(r.sequence)===lo+BigInt(index)+1n&&!records.has(r.batchId)&&typeof r.batchId==='string'&&r.batchId.length>0,'journal duplicate/gap');
   check(r.commandStream===stream&&/^[a-f0-9]{64}$/.test(r.payloadChecksum),'journal source/checksum mismatch');
   check(Number.isFinite(Date.parse(r.workFinishedAt))&&Date.parse(r.canonicalCommitObservedAt)>=Date.parse(r.workFinishedAt),'journal clock invalid');
   const p=v.partitions.find(p=>p.partition===r.partition);check(p&&Array.isArray(r.streamSequences)&&BigInt(r.streamSequences.length)===exact(r.commandCount),'journal count/partition');
   for(const value of r.streamSequences){const seq=exact(value);check(seq>=BigInt(p.min)&&seq<=BigInt(p.max)&&!members.has(value),'journal foreign/duplicate member');members.add(value);}
   records.set(r.batchId,r);
  });
 }
 check(BigInt(members.size)===v.count,'journal incomplete source cohort');return records;
}
export function containerFingerprint(c) {return {id:c.Id,hash:hash(stable({image:c.Image,config:c.Config,host:c.HostConfig,mounts:c.Mounts}))};}
export function assertIdentity(f,c,running) {check(c?.Id===f.id&&containerFingerprint(c).hash===f.hash,'container identity/config drift');check(c.State?.Running===running&&c.RestartCount===0,'container running/restart mismatch');}
export function buildRate(commands,start,dispatch,end,target) {
 const n=exact(commands);check([5000,7500,10000].includes(target),'target must be5000/7500/10000');check(typeof start==='bigint'&&typeof dispatch==='bigint'&&typeof end==='bigint'&&start<=dispatch&&end>dispatch,'start clock must precede dispatch and completion');
 const elapsed=end-start;return {sourceCommands:commands,fullDrainWallMs:Number(elapsed)/1e6,sourceCommandsPerFullDrainSecond:Number(n)*1e9/Number(elapsed),requiredCommandsPerSecond:target*1.2,pass:n*1_000_000_000n*5n>=BigInt(target)*6n*elapsed};
}
export function validateHealth(s,m) {
 check(s?.status==='running'&&s.projectionName===m.sourceProjectionName&&s.source==='venue-event-batch'&&s.eventStream===m.eventStream&&s.projectionStage==='full','canonical source/stage mismatch');
 number(s.lag);number(s.metrics?.projected);for(const k of ['failed','retryAttempts','retryExhausted'])check(zero(s.metrics?.[k]),`canonical ${k} nonzero`);
}
export function validateStage(s,expected,m,generation,v,final=false) {
 check(s?.enabled===true&&s.pollIntervalMs===expected.pollIntervalMs&&s.batchSize===expected.batchSize,'stage configuration missing/drift');
 check(zero(s.metrics?.failed),'stage failed');number(s.metrics.processedRows);
 const i=s.instrumentation;check(i?.enabled===true&&i.stageEnabled===true&&i.sampleIntervalMs===1000,'instrumentation disabled/cadence mismatch');
 const q=i.dirtyQueues,c=i.callerStats,cov=i.coverage;
 if(final){number(q?.orderLifecyclePending);number(q?.marketDataPending);}
 else if(typeof q?.orderLifecycleEmpty==='boolean'&&typeof q?.marketDataEmpty==='boolean'){}
 else {number(q?.orderLifecyclePending);number(q?.marketDataPending);}
 check(gen(q.databaseGeneration)===gen(generation)&&gen(cov?.databaseGeneration)===gen(generation),'database generation drift');
 check(zero(cov.generationGuardFailures)&&zero(cov.clockGuardFailures),'coverage guard failed');
 for(const key of ['calls','completed','active','maxConcurrent','callerCount'])number(c?.[key]);check(zero(c.failed),'caller failed');
 check(c.maxConcurrent<=expected.maxConcurrent,'caller concurrency');
 const names=Object.keys(c.callers??{});check(names.length===c.callerCount&&names.every(k=>expected.callers.includes(k)),'unknown caller');
 const callerSum=names.reduce((sum,k)=>sum+number(c.callers[k]),0);
 if(!final)return;
 check(c.completed+c.active===c.calls&&callerSum===c.calls,'final caller accounting mismatch');
 check(names.length===expected.callers.length&&c.calls>0&&c.active===0&&q.orderLifecyclePending===0&&q.marketDataPending===0,'stage not complete/idle');
 const marker=cov.lastMarker;check(marker?.markerId&&marker.postCommitObserved===true&&marker.sourceProjectionName===m.sourceProjectionName&&gen(marker.databaseGeneration)===gen(generation),'covering marker absent/source drift');
 check(Number.isFinite(Date.parse(marker.databaseSnapshotAt))&&Date.parse(marker.postCommitObservedAt)>=Date.parse(marker.databaseSnapshotAt),'marker time invalid');
 check(Array.isArray(marker.sourceWatermarks)&&marker.sourceWatermarks.length===v.partitions.length,'marker partition count');
 for(const p of v.partitions)check(marker.sourceWatermarks.filter(w=>w.partition===p.partition&&w.lastPartitionSequence===p.max).length===1,'marker does not cover exact cohort');
}

export function validateManifest(m) {
 check(m?.schemaVersion===1&&typeof m.project==='string'&&m.project.length>0&&m.dedicatedFixtureNoExternalWriters===true,'explicit dedicated fixture/project required');
 for(const k of ['fixtureId','sourceProjectionName','eventStream','commandStream','marketProjectionName'])literal(m[k]);exact(m.sourceCommands);
 check(/^[a-f0-9]{64}$/.test(m.preloadSha256),'immutable preloadSha256 required');
 check([5000,7500,10000].includes(m.targetCommandsPerSecond)&&Number.isSafeInteger(m.timeoutMs)&&m.timeoutMs>=1000&&m.timeoutMs<=900000,'bounded timeout/target required');
 if(Object.hasOwn(m,'canonicalCaptureTimeoutMs'))check(Number.isSafeInteger(m.canonicalCaptureTimeoutMs)&&m.canonicalCaptureTimeoutMs>=1000&&m.canonicalCaptureTimeoutMs<=900000,'canonical capture timeout must be1000..900000ms');
 check(Array.isArray(m.containers)&&m.containers.length>0&&new Set(m.containers.map(c=>c.id)).size===m.containers.length,'explicit unique complete container inventory required');
 for(const c of m.containers){check(/^[a-f0-9]{64}$/.test(c.id)&&/^[a-f0-9]{64}$/.test(c.configSha256)&&typeof c.runningBefore==='boolean','container ID/config hash/state required');}
 check(Array.isArray(m.projectors)&&m.projectors.length>0&&new Set(m.projectors.map(p=>p.id)).size===m.projectors.length,'unique canonical projector set required');
 const owners=m.projectors.flatMap(p=>p.partitions??[]);check(owners.length>0&&new Set(owners).size===owners.length,'partition ownership ambiguous');owners.forEach(number);
 for(const p of m.projectors){check(m.containers.some(c=>c.id===p.id&&c.runningBefore===false)&&Number.isSafeInteger(p.port)&&p.port>0&&p.port<65536,'projector not precreated/stopped/port missing');}
 for(const s of [m.lifecycle,m.market])check(m.projectors.some(p=>p.id===s?.id)&&Number.isSafeInteger(s.pollIntervalMs)&&s.pollIntervalMs>0&&Number.isSafeInteger(s.batchSize)&&s.batchSize>0&&Array.isArray(s.callers)&&s.callers.length>0&&new Set(s.callers).size===s.callers.length&&Number.isSafeInteger(s.maxConcurrent)&&s.maxConcurrent>0,'explicit stage topology required');
 for(const db of [m.canonicalDb,m.projectionDb]){check(m.containers.some(c=>c.id===db?.id&&c.runningBefore===true),'DB identity missing');literal(db.user);literal(db.database);check(Array.isArray(db.allowedClients)&&db.allowedClients.length>0,'DB client allowlist required');for(const c of db.allowedClients){check(Boolean(c.address)!==Boolean(c.containerId),'client requires exactly address or containerId');if(c.containerId)check(m.projectors.some(p=>p.id===c.containerId),'client container must be explicit projector');else literal(c.address);literal(c.applicationName);literal(c.user);}}
 check(m.canonicalDb.id!==m.projectionDb.id,'this bounded helper requires split canonical/projection DBs');return m;
}

const usage=`Usage: node scripts/dev/full-projection-headroom.mjs --manifest=FILE --preload-report=FILE --out=NEW_DIRECTORY
No defaults for topology/configuration. Manifest schema exported validateManifest: schemaVersion1, project, fixtureId, dedicatedFixtureNoExternalWriters:true, sourceCommands decimal string, sourceProjectionName, eventStream, commandStream, marketProjectionName, preloadSha256, targetCommandsPerSecond(5000|7500|10000), timeoutMs(1000..900000), optional canonicalCaptureTimeoutMs(1000..900000; default300000, outside drain timing), containers:[{id:fullDockerId,configSha256,runningBefore}], projectors:[{id,port,partitions}], lifecycle/market:{id,pollIntervalMs,batchSize,callers:[exact labels],maxConcurrent}, canonicalDb/projectionDb:{id,user,database,allowedClients:[{containerId:explicitProjectorId,applicationName,user}] (or exact address instead of containerId)}.
Pre-created projectors must be stopped, all producer containers stopped. Full selected-project inventory must match. Requires Docker, curl inside projectors, psql inside DBs, current Kotlin reference source locally. Fresh zero-offset exclusive cohort only. Runs reference after stopping projectors; startup-inclusive rate; no freshness claim. Raw evidence may contain fixture business data; protect output directory.`;

// Inspect is consumed privately for full-config hashing, never persisted verbatim.
export function createCommandRecorder(save, execute = exec) {
 let sequence=0;
 return async(args,timeout=15000)=>{
  const id=String(++sequence).padStart(4,'0'),sensitive=args[0]==='inspect';
  save(`${id}.command.json`,{executable:'docker',args});
  const output=(r)=>{save(`${id}.stdout`,sensitive?{redacted:true,sha256:hash(r.stdout??'')}:r.stdout??'');save(`${id}.stderr`,sensitive?'[inspect diagnostics withheld]':r.stderr??'');};
  try{const result=await execute('docker',args,{timeout,maxBuffer:256*1024*1024});output(result);return result.stdout;}
  catch(error){output(error);if(sensitive)throw Error('docker inspect failed; raw diagnostics withheld');throw error;}
 };
}

// Retain only exact membership/identity bookkeeping, never full outcome/batch payloads.
export function createCanonicalVerifier(v,journals,m) {
 const remaining=new Map([...journals].map(([id,j])=>[id,new Set(j.streamSequences)]));
 const commands=new Set(),seenBatches=new Set(),groups=new Map();let total=0n,batchCommands=0n;
 return {
  accept(row){
   check(row&&typeof row==='object'&&Object.keys(row).length===1,'invalid canonical evidence row');
   if(row.outcome){
    const o=row.outcome,j=journals.get(o.batch_id),sequence=exact(o.stream_sequence);
    check(o.command_stream===m.commandStream&&o.event_stream===m.eventStream,'foreign source stream');
    check(typeof o.command_id==='string'&&o.command_id.length>0&&!commands.has(o.command_id),'missing/duplicate canonical command');
    check(j&&o.partition_id===j.partition&&remaining.get(o.batch_id).delete(o.stream_sequence),'foreign/duplicate canonical batch member');
    commands.add(o.command_id);total++;
    const a=groups.get(o.partition_id)??{count:0n,min:sequence,max:sequence};a.count++;if(sequence<a.min)a.min=sequence;if(sequence>a.max)a.max=sequence;groups.set(o.partition_id,a);
   }else if(row.batch){
    const b=row.batch,j=journals.get(b.batch_id);
    check(b.command_stream===m.commandStream&&b.event_stream===m.eventStream,'foreign batch source stream');
    check(j&&!seenBatches.has(b.batch_id)&&j.payloadChecksum===b.payload_checksum&&j.partition===b.partition_id&&j.commandCount===String(number(b.command_count)),'canonical journal checksum/count/partition conflict');
    seenBatches.add(b.batch_id);batchCommands+=BigInt(b.command_count);
   }else throw Error('unknown canonical evidence row');
  },
  finish(){
   check(seenBatches.size===journals.size&&batchCommands===v.count&&[...remaining.values()].every(s=>s.size===0),'missing canonical batch/member');
   const summary={total:total.toString(),rows:[...groups].sort(([a],[b])=>a-b).map(([partition,a])=>({partition,count:a.count.toString(),distinct:a.count.toString(),min:a.min.toString(),max:a.max.toString()}))};
   validateCanonical(summary,v);return summary;
  }
 };
}

export async function streamCanonicalRows(readable,path,verifier) {
 const fd=openSync(path,'wx',0o600),digest=createHash('sha256'),decoder=new StringDecoder('utf8');let pending='';
 const consume=()=>{let end;while((end=pending.indexOf('\n'))>=0){check(end<=64*1024*1024,'single canonical row exceeds64MiB bound');const line=pending.slice(0,end);pending=pending.slice(end+1);if(line.trim())verifier.accept(JSON.parse(line));}check(pending.length<=64*1024*1024,'single canonical row exceeds64MiB bound');};
 try{
  for await(const chunk of readable){const bytes=Buffer.isBuffer(chunk)?chunk:Buffer.from(chunk);digest.update(bytes);for(let offset=0;offset<bytes.length;){const written=writeSync(fd,bytes,offset,bytes.length-offset);check(written>0,'canonical evidence write made no progress');offset+=written;}pending+=decoder.write(bytes);consume();}
  pending+=decoder.end();consume();if(pending.trim())verifier.accept(JSON.parse(pending));
  return {sha256:digest.digest('hex'),summary:verifier.finish()};
 }finally{closeSync(fd);}
}

export async function run(options) {
 mkdirSync(options.out,{recursive:false,mode:0o700});const save=(name,x)=>writeFileSync(join(options.out,name),typeof x==='string'?x:JSON.stringify(x,null,2));
 const command=createCommandRecorder(save);
 let started=false,m,projectorIds=[];let report={schemaVersion:'reef.fullProjectionHeadroom.v1',pass:false,authority:'fixed-backlog-not-sustained-freshness',checks:{}};
 try{
  m=validateManifest(JSON.parse(readFileSync(options.manifest,'utf8')));save('manifest.json',m);
  const raw=readFileSync(options.preloadReport);check(hash(raw)===m.preloadSha256,'preload artifact hash mismatch');save('preload-report.json',raw.toString());
  const preload=JSON.parse(raw);const cohort=buildUpstreamSourceCohort(preload);check(stable(cohort)===stable(preload.upstreamSourceCohort),'stored/recomputed source proof differs');
  const v=validateCohort(cohort,m.sourceCommands);const journals=validateJournal(preload.materializerTiming,v,m.commandStream);check(stable(v.partitions.map(p=>p.partition))===stable(m.projectors.flatMap(p=>p.partitions).sort((a,b)=>a-b)),'projector partition ownership differs from cohort');
  // Existing source proof validates checksum membership; require explicit load failures/drops too.
  check(preload.totalFailures===0&&preload.loadSchedule?.dropped===0,'explicit zero preload failures/drops required');
  projectorIds=m.projectors.map(p=>p.id);const inventory=async()=>{
   const ids=(await command(['ps','-aq','--filter',`label=com.docker.compose.project=${m.project}`])).trim().split(/\s+/).filter(Boolean);
   check(ids.length===m.containers.length,'unexpected/missing project container');const all=JSON.parse(await command(['inspect',...ids]));
   check(all.every(c=>m.containers.some(e=>e.id===c.Id)),'unknown project container');return all;
  };
  const before=await inventory();save('containers-before.json',before.map(c=>({...containerFingerprint(c),state:c.State,restarts:c.RestartCount})));
  for(const c of before){const e=m.containers.find(e=>e.id===c.Id);assertIdentity({id:e.id,hash:e.configSha256},c,e.runningBefore);check(c.Config?.Labels?.['com.docker.compose.project']===m.project,'project mismatch');if(!projectorIds.includes(c.Id)&&/^(platform-|matching-engine|simulator)/.test(c.Config.Labels['com.docker.compose.service']??''))check(!c.State.Running,'producer/runtime still running');}
  const sql=async(db,text)=>command(['exec','-e','PGAPPNAME=reef-headroom-observer',db.id,'psql','-X','-q','-A','-t','-w','-v','ON_ERROR_STOP=1','-U',db.user,'-d',db.database,'-c',text],60000);
  const snapshot=async(db,settings=true)=>JSON.parse((await sql(db,`SELECT json_build_object('generation',pg_postmaster_start_time()::text,'deadlocks',(SELECT deadlocks::text FROM pg_stat_database WHERE datname=current_database()),'clients',coalesce((SELECT json_agg(json_build_object('address',coalesce(client_addr::text,''),'applicationName',application_name,'user',usename)) FROM pg_stat_activity WHERE datname=current_database() AND backend_type='client backend' AND pid<>pg_backend_pid()),'[]'::json),'settings',${settings ? '(SELECT json_object_agg(name,setting) FROM pg_settings)' : 'NULL'});`)).trim());
  const dbBefore=await Promise.all([snapshot(m.canonicalDb),snapshot(m.projectionDb)]);check(dbBefore.every(s=>s.clients.length===0),'foreign/prestarted DB clients');save('database-before.json',dbBefore);
  const canonicalCaptureTimeoutMs=m.canonicalCaptureTimeoutMs??300000;
  let canonicalCapture=0;
  const canonical=async()=>{
   const text=`SELECT json_build_object('outcome',to_jsonb(o) || jsonb_build_object('stream_sequence',o.stream_sequence::text))::text FROM runtime.canonical_command_outcomes o ORDER BY command_id; SELECT json_build_object('batch',to_jsonb(b))::text FROM runtime.canonical_venue_event_batches b ORDER BY batch_id;`;
   const label=`canonical-rows-${++canonicalCapture}`;
   const args=['exec','-e','PGAPPNAME=reef-headroom-observer',m.canonicalDb.id,'psql','-X','-q','-A','-t','-w','-v','ON_ERROR_STOP=1','-U',m.canonicalDb.user,'-d',m.canonicalDb.database,'-c',text];
   save(`${label}.command.json`,{executable:'docker',args,timeoutMs:canonicalCaptureTimeoutMs});
   const child=spawn('docker',args,{stdio:['ignore','pipe','pipe']});let stderr='';
   child.stderr.on('data',chunk=>{stderr=(stderr+chunk).slice(-1024*1024);});
   const exit=new Promise((resolveExit,reject)=>{child.once('error',reject);child.once('close',code=>code===0?resolveExit():reject(Error(`canonical export exit${code}`)));});
   // Attach rejection handling immediately while consuming stdout.
   const timer=setTimeout(()=>child.kill(),canonicalCaptureTimeoutMs);
   try{return (await Promise.all([streamCanonicalRows(child.stdout,join(options.out,`${label}.ndjson`),createCanonicalVerifier(v,journals,m)),exit]))[0];}
   finally{clearTimeout(timer);if(child.exitCode===null)child.kill();save(`${label}.stderr`,stderr);}
  };
  const sourceBefore=await canonical();save('canonical-before.json',sourceBefore);
  const projection=async()=>JSON.parse((await sql(m.projectionDb,`SELECT json_build_object('watermarks',coalesce((SELECT json_agg(json_build_object('partition',partition_id,'sequence',last_partition_seq::text,'error',last_error)) FROM runtime.projection_watermarks WHERE projection_name=${literal(m.sourceProjectionName)}),'[]'::json),'allWatermarks',(SELECT count(*)::text FROM runtime.projection_watermarks),'lifecycleState',(SELECT count(*)::text FROM runtime.order_lifecycle_state),'marketState',(SELECT count(*)::text FROM runtime.market_data_snapshots),'trades',(SELECT count(*)::text FROM runtime.trades),'orders',(SELECT count(*)::text FROM runtime.orders),'executions',(SELECT count(*)::text FROM runtime.executions),'events',(SELECT count(*)::text FROM runtime.runtime_events),'lifecycle',(SELECT count(*)::text FROM runtime.order_lifecycle_dirty),'market',(SELECT count(*)::text FROM runtime.market_data_snapshot_dirty));`)).trim());
  const projectionBefore=await projection();check(projectionBefore.watermarks.length===0&&['allWatermarks','lifecycleState','marketState','trades','orders','executions','events','lifecycle','market'].every(k=>projectionBefore[k]==='0'),'pre-consumed/nonempty normalized fixture');save('projection-before.json',projectionBefore);
  const t0=process.hrtime.bigint();const dispatched=process.hrtime.bigint();started=true;
  await command(['start',...projectorIds],60000);
  let firstHealthy=null,lastComplete=null,finalSample=null,readySamples=0,maxGapMs=0,startedAtById=new Map();
  while(Number(process.hrtime.bigint()-t0)/1e6<m.timeoutMs){
   const tick=process.hrtime.bigint();const all=await inventory();
   for(const c of all){const e=m.containers.find(e=>e.id===c.Id);assertIdentity({id:e.id,hash:e.configSha256},c,projectorIds.includes(c.Id)||e.runningBefore);if(projectorIds.includes(c.Id)){if(startedAtById.has(c.Id))check(startedAtById.get(c.Id)===c.State.StartedAt,'projector restarted');else startedAtById.set(c.Id,c.State.StartedAt);}}
   const dbs=await Promise.all([snapshot(m.canonicalDb,false),snapshot(m.projectionDb,false)]);
   dbs.forEach((s,i)=>{check(gen(s.generation)===gen(dbBefore[i].generation),'DB generation changed');check(s.deadlocks===dbBefore[i].deadlocks,'database deadlock counter changed');const allowed=[m.canonicalDb,m.projectionDb][i].allowedClients;check(s.clients.every(c=>allowed.some(a=>{const addresses=a.containerId?Object.values(all.find(item=>item.Id===a.containerId)?.NetworkSettings?.Networks??{}).flatMap(n=>[n.IPAddress,n.GlobalIPv6Address]).filter(Boolean):[a.address];return a.applicationName===c.applicationName&&a.user===c.user&&addresses.includes(c.address);})), 'foreign DB client');});
   let sample;
   try{
    sample=await Promise.all(m.projectors.map(async p=>{const get=async path=>JSON.parse(await command(['exec',p.id,'curl','--silent','--show-error','--fail','--max-time','1',`http://127.0.0.1:${p.port}${path}`],2000));const [canonical,lifecycle,market]=await Promise.all([get('/internal/projector/status?includeProjectedCount=false'),get('/internal/order-lifecycle/projector/status?includeDirtyCounts=false'),get('/internal/market-data/projector/status?includeDirtyCounts=false')]);return {id:p.id,canonical,lifecycle,market};}));
   }catch(e){check(firstHealthy===null,'diagnostic sample failed after readiness');appendFileSync(join(options.out,'samples.ndjson'),JSON.stringify({elapsedNs:(process.hrtime.bigint()-t0).toString(),ready:false,error:String(e)})+'\n');await sleep(1000);continue;}
   const completed=process.hrtime.bigint();if(firstHealthy===null)firstHealthy=completed;
   if(lastComplete!==null){check(completed-lastComplete<=2_000_000_000n,'sampler gap exceeded2000ms');maxGapMs=Math.max(maxGapMs,Number(completed-lastComplete)/1e6);}lastComplete=completed;readySamples++;
   for(const s of sample){validateHealth(s.canonical,m);const p=m.projectors.find(p=>p.id===s.id);check(stable([...s.canonical.partitions].sort((a,b)=>a-b))===stable([...p.partitions].sort((a,b)=>a-b)),'partition owner changed');check(s.canonical.orderLifecycleProjectorEnabled===(s.id===m.lifecycle.id)&&s.canonical.marketDataProjectorEnabled===(s.id===m.market.id),'extra/missing stage owner');check(s.lifecycle.enabled===(s.id===m.lifecycle.id)&&s.market.enabled===(s.id===m.market.id),'stage endpoint topology mismatch');}
   const life=sample.find(s=>s.id===m.lifecycle.id).lifecycle,market=sample.find(s=>s.id===m.market.id).market;
   check(market.sourceProjectionName===m.sourceProjectionName&&market.projectionName===m.marketProjectionName,'market source binding mismatch');
   validateStage(life,m.lifecycle,m,dbBefore[1].generation,v);validateStage(market,m.market,m,dbBefore[1].generation,v);
   appendFileSync(join(options.out,'samples.ndjson'),JSON.stringify({elapsedNs:(completed-t0).toString(),ready:true,projectors:sample})+'\n');
   let drained=sample.every(s=>s.canonical.lag===0)&&life.instrumentation.dirtyQueues.orderLifecycleEmpty===true&&market.instrumentation.dirtyQueues.marketDataEmpty===true&&life.instrumentation.callerStats.active===0&&market.instrumentation.callerStats.active===0;
   if(drained&&readySamples>=2){
    const exact=await Promise.all([[m.lifecycle,'/internal/order-lifecycle/projector/status'],[m.market,'/internal/market-data/projector/status']].map(async([p,path])=>JSON.parse(await command(['exec',p.id,'curl','--silent','--show-error','--fail','--max-time','5',`http://127.0.0.1:${p.port}${path}`],6000))));
    if(exact[0].instrumentation.dirtyQueues.orderLifecyclePending!==0||exact[1].instrumentation.dirtyQueues.marketDataPending!==0)continue;
    validateStage(exact[0],m.lifecycle,m,dbBefore[1].generation,v,true);validateStage(exact[1],m.market,m,dbBefore[1].generation,v,true);
    check(exact[0].instrumentation.coverage.lastMarker.markerId===exact[1].instrumentation.coverage.lastMarker.markerId,'different covering cohort');
    check(sample.reduce((n,s)=>n+BigInt(s.canonical.metrics.projected),0n)===v.count,'projected source command count mismatch');
    finalSample={sample:sample.map(s=>({...s,lifecycle:s.id===m.lifecycle.id?exact[0]:s.lifecycle,market:s.id===m.market.id?exact[1]:s.market})),completed};break;
   }
   await sleep(Math.max(0,1000-Number(process.hrtime.bigint()-tick)/1e6));
  }
  check(finalSample,'full drain timeout');const rate=buildRate(m.sourceCommands,t0,dispatched,finalSample.completed,m.targetCommandsPerSecond);save('rate.json',rate);
  await command(['stop',...projectorIds],60000);started=false;
  const post=await projection();check(post.lifecycle==='0'&&post.market==='0','final queue nonzero');check(post.watermarks.length===v.partitions.length,'final SQL watermark count');for(const p of v.partitions)check(post.watermarks.filter(w=>w.partition===p.partition&&w.sequence===p.max&&w.error==='').length===1,'final SQL cohort vector mismatch');save('projection-after.json',post);
  const sourceAfter=await canonical();check(sourceAfter.sha256===sourceBefore.sha256,'immutable canonical source changed');save('canonical-after.json',sourceAfter);
  const dbAfter=await Promise.all([snapshot(m.canonicalDb),snapshot(m.projectionDb)]);dbAfter.forEach((s,i)=>check(s.deadlocks===dbBefore[i].deadlocks&&s.clients.length===0&&gen(s.generation)===gen(dbBefore[i].generation)&&stable(s.settings)===stable(dbBefore[i].settings),'post DB identity/client mismatch'));save('database-after.json',dbAfter);
  const kotlin=readFileSync(fileURLToPath(new URL('../../services/platform-runtime/src/main/kotlin/com/reef/platform/infrastructure/persistence/PostgresRuntimePersistence.kt',import.meta.url)),'utf8');
  const reference=buildReferenceScript(kotlin,{stage:'both',projectionName:m.marketProjectionName},{fixtureId:m.fixtureId,sourceProjectionName:m.sourceProjectionName,sourceMax:v.partitions.reduce((a,p)=>BigInt(p.max)>a?BigInt(p.max):a,0n).toString(),sourceLag:'0'});
  save('reference.sql',reference.sql);save('reference-provenance.json',reference.provenance);
  // Preserve psql framing commands by sending reference SQL over stdin.
  const output=await new Promise((resolvePromise,reject)=>{
   const child=spawn('docker',['exec','-i',m.projectionDb.id,'psql','-X','-q','-A','-t','-w','-v','ON_ERROR_STOP=1','-U',m.projectionDb.user,'-d',m.projectionDb.database]);let out='',err='';const timer=setTimeout(()=>{child.kill();reject(Error('reference timeout'));},330000);
   child.stdout.on('data',b=>{out+=b;});child.stderr.on('data',b=>{err+=b;});child.on('error',reject);child.on('close',code=>{clearTimeout(timer);save('reference.stdout',out);save('reference.stderr',err);code===0?resolvePromise(out):reject(Error(`reference exit${code}`));});child.stdin.end(reference.sql);
  });
  const verified=verifyReferenceOutput(output,'both');save('reference-verification.json',verified);
  const finalContainers=await inventory();for(const c of finalContainers){const e=m.containers.find(e=>e.id===c.Id);assertIdentity({id:e.id,hash:e.configSha256},c,projectorIds.includes(c.Id)?false:e.runningBefore);}
  save('containers-after.json',finalContainers.map(c=>({...containerFingerprint(c),state:c.State,restarts:c.RestartCount})));
  const finalDbs=await Promise.all([snapshot(m.canonicalDb),snapshot(m.projectionDb)]);finalDbs.forEach((s,i)=>check(s.deadlocks===dbBefore[i].deadlocks&&s.clients.length===0&&gen(s.generation)===gen(dbBefore[i].generation)&&stable(s.settings)===stable(dbBefore[i].settings),'DB drift during reference'));
  check((await canonical()).sha256===sourceBefore.sha256,'canonical source changed during reference');
  report={...report,...rate,checks:{sourceProof:true,canonicalSqlMembership:true,canonicalUnchanged:true,configurationMatched:true,databaseGenerationStable:true,finalVectorExact:true,allStagesDrainedAndIdle:true,businessReference:true,headroom:rate.pass},pass:rate.pass,preloadSha256:m.preloadSha256,startMonotonicNs:t0.toString(),dispatchMonotonicNs:dispatched.toString(),endMonotonicNs:finalSample.completed.toString(),sampler:{configuredIntervalMs:1000,maxAllowedGapMs:2000,successfulSampleCount:readySamples,maxGapMs,startupIncluded:true},canonicalCaptureTimeoutMs,dedicatedFixtureAttestationRequired:true,freshnessSloProven:false};
 }catch(e){report.error=String(e);throw e;}finally{if(started&&projectorIds.length){try{await command(['stop',...projectorIds],60000);}catch(e){report.cleanupError=String(e);}}save('report.json',report);}
 return report;
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
 const args=Object.fromEntries(process.argv.slice(2).map(a=>{const match=/^--(manifest|preload-report|out)=(.+)$/.exec(a);check(match,'unknown argument; '+usage);return [match[1],match[2]];}));
 if(!args.manifest||!args['preload-report']||!args.out){console.error(usage);process.exitCode=1;}else run({manifest:resolve(args.manifest),preloadReport:resolve(args['preload-report']),out:resolve(args.out)}).then(r=>{console.log(JSON.stringify(r));if(!r.pass)process.exitCode=1;}).catch(e=>{console.error(String(e));process.exitCode=1;});
}
