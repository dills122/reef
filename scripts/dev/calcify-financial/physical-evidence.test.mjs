import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { freezePhysicalManifest, collectPhysicalEvidence, validatePhysicalEvidence, physicalDelta, parseAllocatedKiB } from './physical-evidence.mjs';

const hash = text => createHash('sha256').update(text).digest('hex');
const uuid = '00000000-0000-0000-0000-000000000001';
function manifest() {
  return { schema:'calcify-physical-manifest-v1', runId:'test-run',
    provenance:{sourceCommit:'a'.repeat(40),sourceSha256:{'probe.kt':hash('source')},classpathSha256:hash('classpath'),imageDigest:`sha256:${'a'.repeat(64)}`,configSha256:hash('config'),buildSha256:hash('build'),fixtureSha256:hash('fixture')},
    ownedRoot:'/tmp/owned',registeredResources:{project:'reef-calcify-session-test',containers:[0,1,2].map(n=>({brokerId:n,id:String(n+1).repeat(64),imageId:`sha256:${'a'.repeat(64)}`,
      labels:{'com.docker.compose.project':'reef-calcify-session-test','com.docker.compose.service':`rp${n}`,'reef.test':'calcify-financial-sprint1'},
      volumes:[{name:`data${n}`,destination:'/var/lib/redpanda/data'}]}))},
    topics:[{name:'exact-owned-topic',topicId:uuid,category:'source',partitions:[{partition:0,replicas:[0,1,2]}]}],
    localResources:[{id:'ack',category:'controllerJournal',path:'/tmp/owned/ack'},{id:'state',category:'localStore',path:'/tmp/owned/state'},{id:'proof',category:'proof',path:'/tmp/owned/proof'}],
    categoryMappings:[] };
}
function metadata(m=manifest()) {
  const s={schema:'calcify-replica-inventory-v1',runId:m.runId,provenance:m.provenance,window:{startedAtMs:1000,completedAtMs:1001,clockScope:'host-monotonic-ms'},
    topics:m.topics.map(({name,topicId,partitions})=>({name,topicId,partitions})),
    replicas:[0,1,2].map(brokerId=>({brokerId,topic:'exact-owned-topic',topicId:uuid,partition:0,logDir:'/var/lib/redpanda/data',sizeBytes:123,unit:'bytes',error:null})),
    rawReceipts:[]};
  const topicRaw={schema:'calcify-admin-topics-raw-v1',request:{topics:m.topics.map(t=>t.name)},response:{topics:s.topics.map(t=>({...t,error:null}))}};
  const logRaw={schema:'calcify-admin-log-dirs-raw-v1',request:{brokerIds:m.registeredResources.containers.map(c=>c.brokerId)},response:{brokers:[0,1,2].map(brokerId=>({brokerId,
    logDirs:[{logDir:'/var/lib/redpanda/data',error:null,replicas:[{topic:'exact-owned-topic',partition:0,sizeBytes:123,isFuture:false}]}]}))}};
  s.rawReceipts=[['describeTopics',topicRaw],['describeLogDirs',logRaw]].map(([operation,payload])=>{const rawJSON=JSON.stringify(payload);return {operation,startedAtMs:1000,completedAtMs:1001,rawJSON,bytesSha256:hash(rawJSON)};});
  return s;
}
function inspection(m=manifest()) {
  return m.registeredResources.containers.map(c=>({Id:c.id,Image:c.imageId,Config:{Labels:c.labels},
    Mounts:c.volumes.map(v=>({Type:'volume',Name:v.name,Destination:v.destination,RW:true})),State:{Running:true,Status:'running',Paused:false,Restarting:false,Dead:false}}));
}
function deps(m=manifest(),override={}) {
  const calls=[];
  return {calls,now:()=>1002,verifyLocalPath:async()=>{},metadataSnapshot:async()=>metadata(m),execute:async(executable,args)=>{
    calls.push({executable,args});
    if(args[0]==='inspect')return {stdout:JSON.stringify(inspection(m)),stderr:'',exitCode:0};
    const path=args.at(-1);return {stdout:`8\t${path}\n`,stderr:'',exitCode:0};
  },...override};
}
async function sample(m=manifest()) {return collectPhysicalEvidence(m,deps(m));}

test('exact owned RF3 logical replicas distinct from measured broker/local allocation and unknown categories',async()=>{
  const s=await sample();
  assert.equal(s.logicalReplicaBytes,369);assert.equal(s.brokerAllocatedBytes,24576);
  assert.equal(s.localAllocatedBytes,24576);assert.equal(s.categories.source.status,'unknown');
  assert.equal(s.categories.changelog.status,'unknown');assert.equal(s.telemetry.rss.status,'unknown');
  assert.equal(s.raw.receipts.length,7);assert.equal(validatePhysicalEvidence(manifest(),s,1002).manifestSha256,s.manifestSha256);
});
test('manifest freezes clone and rejects prefix authority, nonRF3, duplicate topics/partitions/brokers/local paths',()=>{
  const m=manifest(),f=freezePhysicalManifest(m);m.runId='changed';assert.equal(f.runId,'test-run');assert.throws(()=>f.topics.push({}));
  for(const mutate of [m=>m.topics=[],m=>m.topics[0].topicId='',m=>m.topics[0].partitions[0].replicas=[0,0,2],m=>m.topics[0].partitions[0].replicas=[0,1],
    m=>m.topics.push(m.topics[0]),m=>m.topics[0].partitions.push(m.topics[0].partitions[0]),m=>m.registeredResources.containers[1].brokerId=0,
    m=>m.localResources[1].path=m.localResources[0].path,m=>m.localResources[0].path='/var/lib/redpanda/data',m=>m.provenance.configSha256='bad']){
    const input=manifest();mutate(input);assert.throws(()=>freezePhysicalManifest(input));
  }
});
test('replica missing duplicate extra identity range API errors and unsafe integers fail with retained raw evidence',async()=>{
  for(const mutate of [s=>s.replicas.pop(),s=>s.replicas.push(s.replicas[0]),s=>s.replicas[0].brokerId=3,s=>s.replicas[0].topicId='new-topic',
    s=>s.replicas[0].partition=1,s=>s.topics[0].partitions[0].replicas=[0,1],s=>s.replicas[0].error='API_FAILURE',
    s=>s.replicas[0].sizeBytes=Number.MAX_SAFE_INTEGER+1,s=>s.replicas[0].unit='KiB',s=>s.provenance.sourceSha256['probe.kt']=hash('other'),s=>s.provenance.configSha256=hash('other')]){
    const m=manifest(),d=deps(m);d.metadataSnapshot=async()=>{const s=metadata(m);mutate(s);return s;};
    await assert.rejects(collectPhysicalEvidence(m,d),error=>{assert.ok(error.evidence.raw.receipts.length>0);return true;});
  }
});
test('stale future and spanning sample windows fail',async()=>{
  for(const mutate of [s=>s.window.startedAtMs=-1,s=>s.window.completedAtMs=7000,s=>s.window.startedAtMs=0,s=>s.window.completedAtMs=s.window.startedAtMs-1]){
    const m=manifest(),d=deps(m,{now:()=>6001});d.metadataSnapshot=async()=>{const s=metadata(m);mutate(s);return s;};await assert.rejects(collectPhysicalEvidence(m,d));
  }
  const s=await sample();assert.throws(()=>validatePhysicalEvidence(manifest(),s,6003),/STALE/);
});
test('Docker exact ownership image mount drift stops before any exec; stopped broker never executed',async()=>{
  for(const mutate of [rows=>rows.pop(),rows=>rows[0].Config.Labels={'com.docker.compose.project':'unrelated'},rows=>rows[0].Image=`sha256:${'b'.repeat(64)}`,
    rows=>rows[0].Mounts[0].Name='foreign',rows=>rows[0].Mounts[0].RW=false,rows=>rows[0].State.Running=false]){
    const m=manifest(),d=deps(m);d.execute=async(exe,args)=>{d.calls.push(args);const rows=inspection(m);mutate(rows);return {stdout:JSON.stringify(rows),stderr:'',exitCode:0};};
    await assert.rejects(collectPhysicalEvidence(m,d));assert.equal(d.calls.length,1);
  }
});
test('strict du KiB conversion rejects unit path numeric errors; raw receipt hashes validated',async()=>{
  assert.equal(parseAllocatedKiB('8\t/tmp/owned\n','/tmp/owned'),8192);
  for(const text of ['8 bytes /tmp/owned','1.5 /tmp/owned','-8 /tmp/owned','8 /tmp/other','9007199254740991 /tmp/owned'])assert.throws(()=>parseAllocatedKiB(text,'/tmp/owned'));
  const s=await sample();s.raw.receipts[1].stdout='9\t/var/lib/redpanda/data\n';assert.throws(()=>validatePhysicalEvidence(manifest(),s,1002),/HASH/);
});
test('collector command failures, output overrun and hang have bounded failure evidence',async()=>{
  for(const execute of [async()=>{throw Object.assign(new Error('command failed'),{stdout:'partial',stderr:'failed',code:1});},
    async()=>({stdout:'[]',stderr:'API error',exitCode:1})]){
    await assert.rejects(collectPhysicalEvidence(manifest(),deps(manifest(),{execute})),error=>{assert.equal(error.evidence.raw.receipts.length,1);return true;});
  }
  await assert.rejects(collectPhysicalEvidence(manifest(),deps(manifest(),{execute:()=>new Promise(()=>{}),timeoutMs:10})),/TIMEOUT/);
});
test('signed deltas retain decreasing allocations as reclamation and keep baseline separate',async()=>{
  const before=await sample(),after=structuredClone(before);after.startedAtMs=1003;after.completedAtMs=1004;
  after.brokerAllocatedBytes=before.brokerAllocatedBytes-4096;
  // Delta works only on validated samples; mutating summary without raw measurements must fail.
  assert.throws(()=>physicalDelta(manifest(),before,after,1004));
  const m=manifest(),d=deps(m,{now:()=>1004});d.execute=async(exe,args)=>args[0]==='inspect'?{stdout:JSON.stringify(inspection(m)),stderr:'',exitCode:0}:{stdout:`4\t${args.at(-1)}\n`,stderr:'',exitCode:0};
  const decreasing=await collectPhysicalEvidence(m,d),delta=physicalDelta(m,before,decreasing,1004);
  assert.equal(delta.broker.deltaBytes,-12288);assert.equal(delta.broker.direction,'reclamation');assert.equal(delta.broker.baselineBytes,24576);
  assert.equal(delta.broker.costPerTradeBytes,null);assert.equal(delta.categories.changelog.status,'unknown');
});
test('receipt unit and executable mutations fail even with recomputed input hash',async()=>{
  const m=manifest(),s=await sample(m);s.raw.receipts[1].unit='bytes';assert.throws(()=>validatePhysicalEvidence(m,s,1002),/UNIT/);
  const other=await sample(m),r=other.raw.receipts[1];r.executable='/tmp/foreign-docker';r.inputSha256=hash(JSON.stringify({executable:r.executable,argv:r.argv}));
  assert.throws(()=>validatePhysicalEvidence(m,other,1002),/EXECUTABLE/);
});
test('actual local symlink escape rejected before host du',async()=>{
  const root=await mkdtemp(join(tmpdir(),'physical-owned-')),foreign=await mkdtemp(join(tmpdir(),'physical-foreign-'));
  // Darwin /tmp is an alias; freeze its real canonical path as required.
  const { realpath }=await import('node:fs/promises');const ownedRoot=await realpath(root);await symlink(foreign,join(ownedRoot,'escape'));
  const m=manifest();m.ownedRoot=ownedRoot;m.localResources=[{id:'state',category:'localStore',path:join(ownedRoot,'escape')}];
  const d=deps(m);delete d.verifyLocalPath;
  await assert.rejects(collectPhysicalEvidence(m,d),/SYMLINK/);assert.equal(d.calls.filter(c=>c.executable==='/usr/bin/du').length,0);
});
test('opaque category path mapping never authorizes category allocation',()=>{
  const m=manifest();m.categoryMappings=[0,1,2].map(brokerId=>({brokerId,category:'source',path:`/var/lib/redpanda/data/kafka/exact-owned-topic/0_${brokerId}`,
    evidence:'verified exact mapping raw receipt',evidenceSha256:hash('verified exact mapping raw receipt'),replicas:[{topic:'exact-owned-topic',topicId:uuid,partition:0}]}));
  assert.throws(()=>freezePhysicalManifest(m),/UNSUPPORTED/);
});
test('raw actual operation JSON must semantically derive exact inventory sizes and topic incarnation',async()=>{
  for(const mutate of [s=>s.replicas.forEach(r=>r.sizeBytes=999),s=>s.rawReceipts[0].rawJSON='opaque bytes',s=>s.rawReceipts[1].operation='arbitraryOperation',
    s=>s.rawReceipts.pop(),s=>s.rawReceipts.push(s.rawReceipts[0]),s=>{const raw=JSON.parse(s.rawReceipts[0].rawJSON);raw.response.topics[0].topicId='recreated';s.rawReceipts[0].rawJSON=JSON.stringify(raw);},
    s=>{const raw=JSON.parse(s.rawReceipts[1].rawJSON);raw.response.brokers[0].logDirs[0].error='API_FAILURE';s.rawReceipts[1].rawJSON=JSON.stringify(raw);},
    s=>{const raw=JSON.parse(s.rawReceipts[1].rawJSON);raw.response.brokers[0].logDirs[0].replicas[0].isFuture=true;s.rawReceipts[1].rawJSON=JSON.stringify(raw);}]){
    const m=manifest(),d=deps(m);d.metadataSnapshot=async()=>{const s=metadata(m);mutate(s);for(const r of s.rawReceipts)r.bytesSha256=hash(r.rawJSON);return s;};
    await assert.rejects(collectPhysicalEvidence(m,d));
  }
});
test('unregistered local categories remain explicitly unknown, registered zero allocation measured',async()=>{
  for(const locals of [[],[manifest().localResources[0]]]){
    const m=manifest();m.localResources=locals;const s=await sample(m);
    for(const category of ['localStore','controllerJournal','proof']){
      const known=locals.some(l=>l.category===category);assert.equal(s.localCategoryAllocatedBytes[category].status,known?'measured':'unknown');
      assert.equal(s.localCategoryAllocatedBytes[category].allocatedBytes,known?8192:null);
    }
    assert.equal(s.localAllocatedScope,'registered local paths only; missing categories excluded and unknown');
  }
  const m=manifest(),d=deps(m);d.execute=async(exe,args)=>args[0]==='inspect'?{stdout:JSON.stringify(inspection(m)),stderr:'',exitCode:0}:{stdout:`0\t${args.at(-1)}\n`,stderr:'',exitCode:0};
  const zero=await collectPhysicalEvidence(m,d);assert.deepEqual(zero.localCategoryAllocatedBytes.localStore,{status:'measured',allocatedBytes:0});
});
test('unsupported measured missing and invented telemetry cannot enter validated evidence',async()=>{
  for(const mutate of [s=>s.telemetry.rss={status:'measured',bytes:12},s=>delete s.telemetry,s=>delete s.telemetry.cpu,
    s=>s.telemetry.native={status:'unknown',reason:'fabricated'},s=>s.telemetry.additional={status:'measured',bytes:5}]){
    const s=await sample();mutate(s);assert.throws(()=>validatePhysicalEvidence(manifest(),s,1002),/TELEMETRY/);
  }
});
test('explicit lower frozen raw budget refuses actual output overflow without raising existing cap',async()=>{
  const m=manifest();m.rawProofBytes=1024;
  await assert.rejects(collectPhysicalEvidence(m,deps(m)),error=>{assert.match(error.message,/RAW_PROOF_BUDGET/);assert.equal(error.evidence.raw.receipts.length,1);return true;});
  for(const limit of [0,-1,NaN,Infinity,256*1024**2+1]){const m=manifest();m.rawProofBytes=limit;assert.throws(()=>freezePhysicalManifest(m));}
});
test('timeout never resumes later Docker/local commands from late executor completion',async()=>{
  const m=manifest(),d=deps(m);let calls=0;d.execute=async()=>{calls++;await new Promise(resolve=>setTimeout(resolve,30));return {stdout:JSON.stringify(inspection(m)),stderr:'',exitCode:0};};
  await assert.rejects(collectPhysicalEvidence(m,{...d,timeoutMs:5}),/TIMEOUT/);
  await new Promise(resolve=>setTimeout(resolve,40));assert.equal(calls,1);
});
test('metadata raw hash and normalized snapshot mutations, extra mounts and missing API receipts rejected',async()=>{
  for(const mutate of [s=>s.rawReceipts=[],s=>s.rawReceipts[0].rawJSON='mutated',s=>s.rawReceipts[0].completedAtMs=1002]){
    const m=manifest(),d=deps(m);d.metadataSnapshot=async()=>{const s=metadata(m);mutate(s);return s;};await assert.rejects(collectPhysicalEvidence(m,d));
  }
  const s=await sample();s.raw.inventory.replicas[0].sizeBytes=124;s.logicalReplicaBytes=370;assert.throws(()=>validatePhysicalEvidence(manifest(),s,1002),/RAW_REPLICA_BINDING/);
  const hashMutant=await sample();hashMutant.raw.inventorySha256=hash('different');assert.throws(()=>validatePhysicalEvidence(manifest(),hashMutant,1002),/HASH/);
  const m=manifest(),d=deps(m);d.execute=async()=>{const rows=inspection(m);rows[0].Mounts.push({Type:'bind',Destination:'/var/lib/redpanda/data/kafka'});return {stdout:JSON.stringify(rows),stderr:'',exitCode:0};};
  await assert.rejects(collectPhysicalEvidence(m,d),/MOUNT/);
});
test('actual raw requests broker identity partition duplicates and registered ranges all fail closed',async()=>{
  for(const mutate of [r=>r.request.brokerIds.pop(),r=>r.response.brokers.pop(),r=>r.response.brokers[1].brokerId=0,
    r=>r.response.brokers[0].logDirs[0].replicas.push({...r.response.brokers[0].logDirs[0].replicas[0]}),
    r=>r.response.brokers[0].logDirs[0].replicas[0].partition=1,r=>r.response.brokers[0].logDirs[0].replicas[0].sizeBytes=-1,
    r=>r.response.brokers[0].logDirs[0].logDir='/unregistered/data']){
    const m=manifest(),d=deps(m);d.metadataSnapshot=async()=>{const s=metadata(m),r=s.rawReceipts[1],raw=JSON.parse(r.rawJSON);mutate(raw);r.rawJSON=JSON.stringify(raw);r.bytesSha256=hash(r.rawJSON);return s;};
    await assert.rejects(collectPhysicalEvidence(m,d));
  }
  const m=manifest(),d=deps(m);d.metadataSnapshot=async()=>{const s=metadata(m),r=s.rawReceipts[1],raw=JSON.parse(r.rawJSON);raw.response.brokers[0].logDirs[0].replicas.push({topic:'foreign-topic',partition:99,sizeBytes:98765,isFuture:false});r.rawJSON=JSON.stringify(raw);r.bytesSha256=hash(r.rawJSON);return s;};
  const s=await collectPhysicalEvidence(m,d);assert.equal(s.logicalReplicaBytes,369);assert.ok(s.raw.inventory.rawReceipts[1].rawJSON.includes('foreign-topic'));
  s.categories.source={status:'measured',allocatedBytes:12};assert.throws(()=>validatePhysicalEvidence(m,s,1002),/categories/);
});
