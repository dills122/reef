// Test-only owned physical evidence. Logical Kafka replica lengths are never allocated bytes.
import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { realpath, lstat, readFile } from 'node:fs/promises';
import { isAbsolute, relative, resolve, sep } from 'node:path';
import { BUDGETS } from './proof-supervisor.mjs';
import { hostMonotonicMs } from './lib/external-faults.mjs';

const exec = promisify(execFile);
const sha = value => createHash('sha256').update(value).digest('hex');
const encoded = value => JSON.stringify(value);
const hashPattern = /^[a-f0-9]{64}$/;
const dataRoot = '/var/lib/redpanda/data';
const categories = ['source','changelog','output'];
const localCategories = ['localStore','controllerJournal','proof'];
const unknownTelemetry = () => Object.fromEntries(['rss','native','cpu'].map(k=>[k,{status:'unknown',reason:'No process-scoped raw telemetry supplied'}]));
const fail = (condition, message) => { if (!condition) throw new Error(`PHYSICAL_${message}`); };
const integer = (value,label) => { fail(Number.isSafeInteger(value)&&value>=0,`${label}_INVALID`);return value; };
const add = (a,b) => integer(a+b,'SUM_BYTES');
const text = value => typeof value==='string'&&value.length>0&&!value.includes('\0');
function freeze(value) { if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value; }
function contained(root,path) { const rel=relative(root,path);return rel!==''&&!rel.startsWith(`..${sep}`)&&rel!=='..'&&!isAbsolute(rel); }
function absolute(path) { return text(path)&&isAbsolute(path)&&resolve(path)===path&&!/[\r\n\t]/.test(path); }
function exact(a,b,label) { fail(encoded(a)===encoded(b),`${label}_DRIFT`); }
function provenance(p) {
  fail(p&&typeof p==='object','PROVENANCE_REQUIRED');
  for(const key of ['configSha256','fixtureSha256','buildSha256','classpathSha256'])fail(hashPattern.test(p[key]),`${key}_INVALID`);
  fail(/^sha256:[a-f0-9]{64}$/.test(p.imageDigest),'IMAGE_DIGEST_INVALID');
  const source=p.sourceSha256;
  fail(hashPattern.test(p.candidateSha256)||source&&typeof source==='object'&&!Array.isArray(source)&&Object.keys(source).length>0&&
    Object.entries(source).every(([path,hash])=>text(path)&&hashPattern.test(hash)),'SOURCE_HASH_IDENTITY_REQUIRED');
}
function topicRows(topics,brokerIds,categoryRequired=false) {
  fail(Array.isArray(topics)&&topics.length>0,'TOPIC_MANIFEST_REQUIRED');const names=new Set(),ids=new Set(),keys=new Set();
  for(const t of topics){
    fail(text(t.name)&&text(t.topicId)&&!names.has(t.name)&&!ids.has(t.topicId),'TOPIC_IDENTITY_INVALID');names.add(t.name);ids.add(t.topicId);
    if(categoryRequired)fail(categories.includes(t.category),'TOPIC_CATEGORY_INVALID');
    fail(Array.isArray(t.partitions)&&t.partitions.length>0,'PARTITIONS_REQUIRED');const partitions=new Set();
    for(const p of t.partitions){
      integer(p.partition,'PARTITION');fail(!partitions.has(p.partition),'DUPLICATE_PARTITION');partitions.add(p.partition);
      fail(Array.isArray(p.replicas)&&p.replicas.length===3&&new Set(p.replicas).size===3&&p.replicas.every(b=>brokerIds.has(b)),'EXACT_RF3_ASSIGNMENT_REQUIRED');
      for(const b of p.replicas)keys.add(encoded([b,t.name,t.topicId,p.partition]));
    }
    fail([...partitions].sort((a,b)=>a-b).every((p,i)=>p===i),'PARTITION_RANGE_DRIFT');
  }
  return keys;
}
export function freezePhysicalManifest(input) {
  const m=structuredClone(input);fail(m?.schema==='calcify-physical-manifest-v1'&&text(m.runId),'MANIFEST_SCHEMA_INVALID');provenance(m.provenance);
  m.executables??={docker:'/usr/local/bin/docker',du:'/usr/bin/du'};
  fail(absolute(m.executables.docker)&&absolute(m.executables.du),'EXECUTABLE_PINS_REQUIRED');
  m.rawProofBytes??=BUDGETS.rawProofBytes;
  fail(Number.isSafeInteger(m.rawProofBytes)&&m.rawProofBytes>0&&m.rawProofBytes<=BUDGETS.rawProofBytes,'RAW_PROOF_BUDGET_INVALID');
  const r=m.registeredResources;fail(r&&/^reef-calcify-[a-z0-9-]+$/.test(r.project),'PROJECT_INVALID');
  fail(Array.isArray(r.containers)&&r.containers.length===3,'EXACT_THREE_BROKERS_REQUIRED');const ids=new Set(),brokers=new Set(),services=new Set();
  for(const c of r.containers){
    fail(/^[a-f0-9]{64}$/.test(c.id)&&!ids.has(c.id),'CONTAINER_ID_INVALID');ids.add(c.id);
    integer(c.brokerId,'BROKER_ID');fail(!brokers.has(c.brokerId),'DUPLICATE_BROKER');brokers.add(c.brokerId);
    fail(c.imageId===m.provenance.imageDigest,'IMAGE_PIN_REQUIRED');
    const labels=c.labels;fail(labels?.['com.docker.compose.project']===r.project&&text(labels['com.docker.compose.service'])&&
      labels['reef.test']==='calcify-financial-sprint1'&&Object.entries(labels).every(([k,v])=>text(k)&&typeof v==='string'),'LABEL_PINS_REQUIRED');
    fail(!services.has(labels['com.docker.compose.service']),'DUPLICATE_SERVICE');services.add(labels['com.docker.compose.service']);
    fail(Array.isArray(c.volumes)&&c.volumes.length>0&&c.volumes.every(v=>text(v.name)&&absolute(v.destination))&&
      c.volumes.some(v=>v.destination===dataRoot)&&new Set(c.volumes.map(v=>v.destination)).size===c.volumes.length,'VOLUME_PINS_REQUIRED');
  }
  topicRows(m.topics,brokers,true);
  fail(absolute(m.ownedRoot)&&m.ownedRoot!=='/','OWNED_ROOT_REQUIRED');
  fail(Array.isArray(m.localResources),'LOCAL_RESOURCES_REQUIRED');const localIds=new Set(),paths=[];
  for(const l of m.localResources){
    fail(text(l.id)&&!localIds.has(l.id)&&localCategories.includes(l.category),'LOCAL_IDENTITY_INVALID');localIds.add(l.id);
    fail(absolute(l.path)&&contained(m.ownedRoot,l.path)&&!paths.some(p=>p===l.path||contained(p,l.path)||contained(l.path,p)),'LOCAL_PATH_OUTSIDE_OR_OVERLAPPING');paths.push(l.path);
  }
  // Redpanda directory incarnation/category ownership has no verified raw mapping API yet.
  m.categoryMappings??=[];fail(Array.isArray(m.categoryMappings)&&m.categoryMappings.length===0,'CATEGORY_MAPPING_UNSUPPORTED');
  return freeze(m);
}
export function parseAllocatedKiB(stdout,path) {
  fail(typeof stdout==='string','DU_OUTPUT_INVALID');const lines=stdout.trimEnd().split('\n');fail(lines.length===1,'DU_OUTPUT_INVALID');
  const match=/^(\d+)[\t ]+(.+)$/.exec(lines[0]);fail(match&&match[2]===path,'DU_UNIT_OR_PATH_INVALID');
  return integer(Number(match[1])*1024,'ALLOCATED_BYTES');
}
function window(w,now,label) {
  fail(w&&w.clockScope==='host-monotonic-ms',`${label}_CLOCK_SCOPE_INVALID`);
  integer(w.startedAtMs,`${label}_START`);integer(w.completedAtMs,`${label}_END`);
  fail(w.startedAtMs<=w.completedAtMs&&w.completedAtMs<=now&&now-w.startedAtMs<=BUDGETS.sampleIntervalMs,`${label}_STALE_WINDOW`);
}
function inspect(m,rows) {
  fail(Array.isArray(rows)&&rows.length===3&&new Set(rows.map(r=>r.Id)).size===3,'INSPECT_RESOURCE_SET_DRIFT');
  for(const c of m.registeredResources.containers){
    const r=rows.find(r=>r.Id===c.id);fail(r&&r.Image===c.imageId&&Object.entries(c.labels).every(([k,v])=>r.Config?.Labels?.[k]===v),'INSPECT_IDENTITY_DRIFT');
    const volumes=r.Mounts?.filter(v=>v.Type==='volume');fail(volumes?.length===c.volumes.length&&c.volumes.every(v=>volumes.some(a=>a.Name===v.name&&a.Destination===v.destination&&a.RW===true)),'INSPECT_MOUNT_DRIFT');
    fail(r.State?.Running===true&&r.State.Status==='running'&&r.State.Paused===false&&r.State.Restarting===false&&r.State.Dead===false,'BROKER_NOT_RUNNING');
    // Unpinned binds into data root can shadow an owned volume.
    fail(r.Mounts.every(v=>v.Type==='volume'||!(v.Destination===dataRoot||contained(dataRoot,v.Destination))),'INSPECT_DATA_MOUNT_DRIFT');
  }
}
function validateInventory(m,s,now) {
  fail(s?.schema==='calcify-replica-inventory-v1'&&s.runId===m.runId,'INVENTORY_IDENTITY_DRIFT');exact(s.provenance,m.provenance,'PROVENANCE');window(s.window,now,'INVENTORY');
  const brokerIds=new Set(m.registeredResources.containers.map(c=>c.brokerId));topicRows(s.topics,brokerIds);
  exact(s.topics,m.topics.map(({name,topicId,partitions})=>({name,topicId,partitions})),'TOPIC_ASSIGNMENT');
  const expected=topicRows(m.topics,brokerIds),seen=new Set();fail(Array.isArray(s.replicas)&&s.replicas.length===expected.size,'REPLICA_SET_DRIFT');let total=0;
  for(const r of s.replicas){
    const key=encoded([r.brokerId,r.topic,r.topicId,r.partition]);fail(expected.has(key)&&!seen.has(key),'REPLICA_IDENTITY_DRIFT');seen.add(key);
    fail(r.error===null&&r.unit!=='KiB'&&(r.unit===undefined||r.unit==='bytes')&&absolute(r.logDir),'REPLICA_API_OR_UNIT_ERROR');total=add(total,integer(r.sizeBytes,'LOGICAL_REPLICA_BYTES'));
  }
  fail(Array.isArray(s.rawReceipts)&&s.rawReceipts.length===2&&new Set(s.rawReceipts.map(r=>r.operation)).size===2&&
    s.rawReceipts.every(r=>['describeTopics','describeLogDirs'].includes(r.operation)),'INVENTORY_RAW_OPERATIONS_REQUIRED');
  const payloads={};
  for(const r of s.rawReceipts){fail(text(r.operation)&&typeof r.rawJSON==='string'&&sha(r.rawJSON)===r.bytesSha256,'INVENTORY_RAW_HASH_INVALID');
    window({...r,clockScope:s.window.clockScope},now,'API_RECEIPT');fail(r.startedAtMs>=s.window.startedAtMs&&r.completedAtMs<=s.window.completedAtMs,'API_RECEIPT_WINDOW_DRIFT');
    try{payloads[r.operation]=JSON.parse(r.rawJSON);}catch{fail(false,'INVENTORY_RAW_JSON_INVALID');}}
  const t=payloads.describeTopics,d=payloads.describeLogDirs;
  fail(t?.schema==='calcify-admin-topics-raw-v1'&&d?.schema==='calcify-admin-log-dirs-raw-v1','INVENTORY_RAW_SCHEMA_INVALID');
  const names=m.topics.map(t=>t.name),brokerList=m.registeredResources.containers.map(c=>c.brokerId);
  exact(t.request?.topics,names,'RAW_TOPIC_REQUEST');exact(d.request?.brokerIds,brokerList,'RAW_BROKER_REQUEST');
  fail(Array.isArray(t.response?.topics)&&t.response.topics.length===names.length&&new Set(t.response.topics.map(t=>t.name)).size===names.length,'RAW_TOPIC_RESPONSE_SET_DRIFT');
  const rawTopics=names.map(name=>{const row=t.response.topics.find(t=>t.name===name);fail(row&&row.error===null,'RAW_TOPIC_API_ERROR');return {name:row.name,topicId:row.topicId,partitions:row.partitions};});
  topicRows(rawTopics,brokerIds);exact(s.topics,rawTopics,'RAW_TOPIC_BINDING');
  fail(Array.isArray(d.response?.brokers)&&d.response.brokers.length===3&&new Set(d.response.brokers.map(b=>b.brokerId)).size===3&&d.response.brokers.every(b=>brokerIds.has(b.brokerId)),'RAW_BROKER_RESPONSE_SET_DRIFT');
  const derived=[];
  for(const b of d.response.brokers){
    fail(Array.isArray(b.logDirs)&&b.logDirs.length>0&&new Set(b.logDirs.map(l=>l.logDir)).size===b.logDirs.length,'RAW_LOG_DIR_SET_INVALID');
    for(const l of b.logDirs){
      fail(l.error===null&&absolute(l.logDir)&&(l.logDir===dataRoot||contained(dataRoot,l.logDir))&&Array.isArray(l.replicas),'RAW_LOG_DIR_API_ERROR');
      for(const r of l.replicas){
        // Admin returns unregistered topics too. Preserve those raw rows without
        // claiming ownership or measuring them in this finite replica inventory.
        if(!names.includes(r.topic))continue;
        fail(r.isFuture===false,'RAW_FUTURE_REPLICA_UNSUPPORTED');
        integer(r.partition,'RAW_PARTITION');integer(r.sizeBytes,'RAW_LOGICAL_REPLICA_BYTES');
        fail(r.error===undefined||r.error===null,'RAW_REPLICA_API_ERROR');
        derived.push({brokerId:b.brokerId,topic:r.topic,topicId:rawTopics.find(t=>t.name===r.topic).topicId,partition:r.partition,logDir:l.logDir,sizeBytes:r.sizeBytes,error:null});
      }
    }
  }
  const normalized=s.replicas.map(({brokerId,topic,topicId,partition,logDir,sizeBytes,error})=>({brokerId,topic,topicId,partition,logDir,sizeBytes,error}));
  const order=(a,b)=>encoded([a.brokerId,a.topic,a.partition,a.logDir]).localeCompare(encoded([b.brokerId,b.topic,b.partition,b.logDir]));
  exact(normalized.sort(order),derived.sort(order),'RAW_REPLICA_BINDING');
  return total;
}
function summaries(m,e) {
  let broker=0,local=0;const localCategoryAllocatedBytes=Object.fromEntries(localCategories.map(c=>[c,{status:'unknown',allocatedBytes:null,reason:'No registered local path for category'}]));
  for(const r of e.allocations){const amount=parseAllocatedKiB(e.raw.receipts[r.receiptIndex].stdout,r.path);exact(r.allocatedBytes,amount,'ALLOCATION_SUMMARY');
    if(r.kind==='broker')broker=add(broker,amount);else if(r.kind==='local'){local=add(local,amount);localCategoryAllocatedBytes[r.category]={status:'measured',allocatedBytes:add(localCategoryAllocatedBytes[r.category].allocatedBytes??0,amount)};}else fail(false,'ALLOCATION_KIND_INVALID');}
  const result={};
  for(const category of categories){
    result[category]={status:'unknown',allocatedBytes:null,reason:'No complete verified exact replica-to-directory mapping'};
  }
  return {brokerAllocatedBytes:broker,localAllocatedBytes:local,localAllocatedScope:'registered local paths only; missing categories excluded and unknown',localCategoryAllocatedBytes,categories:result};
}
function expectedAllocations(m) {
  return [...m.registeredResources.containers.map(c=>({kind:'broker',containerId:c.id,brokerId:c.brokerId,path:dataRoot})),
    ...m.localResources.map(l=>({kind:'local',resourceId:l.id,category:l.category,path:l.path}))];
}
export function validatePhysicalEvidence(input,e,now=hostMonotonicMs()) {
  const m=freezePhysicalManifest(input);fail(e?.schema==='calcify-physical-evidence-v1'&&e.runId===m.runId&&e.manifestSha256===sha(encoded(m)),'MANIFEST_HASH_DRIFT');
  window({...e,clockScope:e.clockScope},now,'COLLECTOR');
  exact(e.telemetry,unknownTelemetry(),'TELEMETRY');
  fail(Array.isArray(e.raw?.receipts),'RAW_RECEIPTS_REQUIRED');let rawBytes=0;
  for(const r of e.raw.receipts){
    fail(typeof r.stdout==='string'&&typeof r.stderr==='string'&&r.stdoutSha256===sha(r.stdout)&&r.stderrSha256===sha(r.stderr)&&r.inputSha256===sha(encoded({executable:r.executable,argv:r.argv})),'RAW_RECEIPT_HASH_INVALID');
    fail(r.exitCode===0&&r.error===null,'RAW_COMMAND_ERROR');window({...r,clockScope:e.clockScope},now,'COMMAND');
    fail(r.startedAtMs>=e.startedAtMs&&r.completedAtMs<=e.completedAtMs,'COMMAND_WINDOW_DRIFT');rawBytes=add(rawBytes,Buffer.byteLength(r.stdout)+Buffer.byteLength(r.stderr));
  }
  fail(rawBytes<m.rawProofBytes,'RAW_PROOF_BUDGET');
  inspect(m,JSON.parse(e.raw.receipts[0].stdout));
  const inventory=e.raw.inventory;const logical=validateInventory(m,inventory,now);
  fail(e.raw.inventorySha256===sha(encoded(inventory)),'INVENTORY_HASH_DRIFT');
  for(const r of inventory.rawReceipts)rawBytes=add(rawBytes,Buffer.byteLength(r.rawJSON));fail(rawBytes<m.rawProofBytes,'RAW_PROOF_BUDGET');
  exact(e.logicalReplicaBytes,logical,'LOGICAL_SUMMARY');
  const expected=expectedAllocations(m);fail(Array.isArray(e.allocations)&&e.allocations.length===expected.length&&e.raw.receipts.length===expected.length+1,'ALLOCATION_SET_DRIFT');
  exact(e.raw.receipts[0].argv,['inspect',...m.registeredResources.containers.map(c=>c.id)],'INSPECT_COMMAND');
  for(const [i,r] of e.allocations.entries()){
    const {receiptIndex,allocatedBytes,...identity}=r;exact(identity,expected[i],'ALLOCATION_IDENTITY');fail(receiptIndex===i+1,'ALLOCATION_RECEIPT_DRIFT');
    exact(e.raw.receipts[receiptIndex].argv,r.kind==='local'?['-sk',r.path]:['exec',r.containerId,'du','-sk',r.path],'ALLOCATION_COMMAND');
    fail(e.raw.receipts[receiptIndex].unit==='allocated KiB (1024 bytes)','RAW_RECEIPT_UNIT_DRIFT');
    fail(e.raw.receipts[receiptIndex].executable===(r.kind==='local'?m.executables.du:m.executables.docker),'RAW_RECEIPT_EXECUTABLE_DRIFT');
  }
  fail(e.raw.receipts[0].unit==='Docker JSON','INSPECT_RECEIPT_UNIT_DRIFT');
  fail(e.raw.receipts[0].executable===m.executables.docker,'INSPECT_RECEIPT_EXECUTABLE_DRIFT');
  const summary=summaries(m,e);for(const [key,value]of Object.entries(summary))exact(e[key],value,key);
  fail(Buffer.byteLength(encoded(e.raw))<m.rawProofBytes,'RAW_PROOF_BUDGET');
  return e;
}
async function verifyLocalPath(path,ownedRoot) {
  const root=await realpath(ownedRoot),actual=await realpath(path);fail(root===ownedRoot&&actual===path&&contained(root,actual),'LOCAL_SYMLINK_OR_ROOT_ESCAPE');
  fail(!(await lstat(path)).isSymbolicLink(),'LOCAL_SYMLINK_ESCAPE');
}
// execute(executable, argv, {signal,timeout,maxBuffer}) must preserve stdout/stderr.
// metadataSnapshot({signal,deadlineMs,manifest}) returns complete finite Admin inventory.
export async function collectPhysicalEvidence(input,deps={}) {
  const m=freezePhysicalManifest(input),now=deps.now??hostMonotonicMs,execute=deps.execute??exec;
  const timeoutMs=deps.timeoutMs??BUDGETS.sampleIntervalMs;fail(Number.isSafeInteger(timeoutMs)&&timeoutMs>0&&timeoutMs<=BUDGETS.sampleIntervalMs,'TIMEOUT_INVALID');
  const docker=deps.docker??m.executables.docker,du=deps.du??m.executables.du;
  fail(docker===m.executables.docker&&du===m.executables.du,'EXECUTABLE_DRIFT');
  const start=now(),deadline=start+timeoutMs,controller=new AbortController();let timer,totalRaw=0;
  const e={schema:'calcify-physical-evidence-v1',runId:m.runId,manifestSha256:sha(encoded(m)),startedAtMs:start,completedAtMs:null,clockScope:'host-monotonic-ms',
    stage:'inspect',allocations:[],raw:{receipts:[],inventory:null,inventorySha256:null},telemetry:unknownTelemetry()};
  const charge = raw=>{totalRaw=add(totalRaw,Buffer.byteLength(raw));fail(totalRaw<m.rawProofBytes,'RAW_PROOF_BUDGET');};
  const active=()=>fail(!controller.signal.aborted&&now()<=deadline,'COLLECTION_TIMEOUT');
  async function command(executable,argv){
    active();
    const r={executable,argv,inputSha256:sha(encoded({executable,argv})),startedAtMs:now(),completedAtMs:null,unit:argv[0]==='inspect'?'Docker JSON':'allocated KiB (1024 bytes)',stdout:'',stderr:'',exitCode:null,error:null};
    e.raw.receipts.push(r);
    try{const out=await execute(executable,argv,{signal:controller.signal,timeout:Math.max(1,deadline-now()),maxBuffer:m.rawProofBytes-totalRaw,killSignal:'SIGKILL'});
      r.stdout=out.stdout??'';r.stderr=out.stderr??'';r.exitCode=out.exitCode??0;
    }catch(error){r.stdout=error.stdout??'';r.stderr=error.stderr??'';r.exitCode=Number.isInteger(error.code)?error.code:null;r.error=error.message;throw error;}
    finally{r.completedAtMs=now();r.stdoutSha256=sha(r.stdout);r.stderrSha256=sha(r.stderr);charge(r.stdout);charge(r.stderr);}
    active();fail(r.exitCode===0,'COMMAND_FAILED');return r;
  }
  const work=async()=>{
    const raw=await command(docker,['inspect',...m.registeredResources.containers.map(c=>c.id)]);inspect(m,JSON.parse(raw.stdout));
    fail(typeof deps.metadataSnapshot==='function','INVENTORY_COLLECTOR_REQUIRED');
    e.stage='inventory';active();
    let inventory;
    try{inventory=await deps.metadataSnapshot({signal:controller.signal,deadlineMs:deadline,manifest:m});}
    catch(error){e.raw.inventoryFailure=structuredClone(error.rawEvidence??error.rawReceipts??null);throw error;}
    e.raw.inventory=structuredClone(inventory);active();
    for(const receipt of e.raw.inventory.rawReceipts??[]){
      if(receipt.rawJSON===undefined&&receipt.path){
        fail(absolute(receipt.path)&&contained(m.ownedRoot,receipt.path),'API_RECEIPT_PATH_ESCAPE');await (deps.verifyLocalPath??verifyLocalPath)(receipt.path,m.ownedRoot);
        const stat=await lstat(receipt.path);fail(stat.size<m.rawProofBytes-totalRaw,'RAW_PROOF_BUDGET');receipt.rawJSON=(await readFile(receipt.path)).toString('utf8');
      }
      if(typeof receipt.rawJSON==='string')charge(receipt.rawJSON);
    }
    e.logicalReplicaBytes=validateInventory(m,e.raw.inventory,now());
    e.raw.inventorySha256=sha(encoded(e.raw.inventory));e.stage='allocation';
    for(const allocation of expectedAllocations(m)){
      active();
      if(allocation.kind==='local')await (deps.verifyLocalPath??verifyLocalPath)(allocation.path,m.ownedRoot);
      const argv=allocation.kind==='local'?['-sk',allocation.path]:['exec',allocation.containerId,'du','-sk',allocation.path];
      const receiptIndex=e.raw.receipts.length,r=await command(allocation.kind==='local'?du:docker,argv);
      e.allocations.push({...allocation,receiptIndex,allocatedBytes:parseAllocatedKiB(r.stdout,allocation.path)});
    }
    Object.assign(e,summaries(m,e));e.completedAtMs=now();e.stage='complete';return validatePhysicalEvidence(m,e,now());
  };
  try{return await Promise.race([work(),new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();reject(new Error('PHYSICAL_COLLECTION_TIMEOUT'));},timeoutMs);})]);}
  catch(error){e.completedAtMs=now();error.evidence=structuredClone(e);throw error;}
  finally{clearTimeout(timer);controller.abort();}
}
export function physicalDelta(input,before,after,now=hostMonotonicMs()) {
  // Baseline may be older than five seconds. Validate at its own completed instant;
  // validate current sample against now, with identical frozen ownership/provenance.
  validatePhysicalEvidence(input,before,before.completedAtMs);validatePhysicalEvidence(input,after,now);
  fail(after.startedAtMs>=before.completedAtMs,'DELTA_WINDOW_OVERLAP');
  const delta=(a,b)=>({baselineBytes:a,finalBytes:b,deltaBytes:b-a,direction:b<a?'reclamation':b>a?'growth':'unchanged',costPerTradeBytes:null});
  return {schema:'calcify-physical-delta-v1',manifestSha256:after.manifestSha256,window:{startedAtMs:before.startedAtMs,completedAtMs:after.completedAtMs,clockScope:after.clockScope},
    broker:delta(before.brokerAllocatedBytes,after.brokerAllocatedBytes),local:delta(before.localAllocatedBytes,after.localAllocatedBytes),logicalReplica:delta(before.logicalReplicaBytes,after.logicalReplicaBytes),
    categories:Object.fromEntries(categories.map(c=>[c,before.categories[c].status==='measured'&&after.categories[c].status==='measured'?{status:'measured',...delta(before.categories[c].allocatedBytes,after.categories[c].allocatedBytes)}:{status:'unknown',deltaBytes:null}]))};
}
