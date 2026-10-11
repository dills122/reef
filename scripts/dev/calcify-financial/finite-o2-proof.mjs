#!/usr/bin/env bun
// Finite O2 correctness fixture. Every live phase requires manager resource lease.
import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { constants as fsConstants } from 'node:fs';
import { mkdir, readFile, writeFile, lstat, readdir, realpath, chmod, rename, open } from 'node:fs/promises';
import { dirname, resolve, join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
export const ROOT = resolve(here, '../../..');
export const PG_IMAGE = 'postgres@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685';
export const RP_IMAGE = 'redpandadata/redpanda@sha256:9e83cfa99278f30d0133271c26bf670cd69c94ffa6ba0b42830dd0c3bd9dcfd9';
export const LIMITS = Object.freeze({ memoryMiB:3968, controllerBytes:256*1024**2, diskAbortBytes:3*1024**3+640*1024**2, diskCeilingBytes:4*1024**3, proofBytes:64*1024**2, samplePeriodMs:5000 });
const namePattern = /^calcify-o2-[a-z0-9-]{1,64}$/;
const hashPattern = /^[0-9a-f]{64}$/;
const decimal = /^(0|[1-9][0-9]*)$/;
function requireValue(v,why) { if(!v) throw Error(why); }
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const bytes = x => Buffer.from(x,'utf8');
const u32 = x => { requireValue(Number.isInteger(x)&&x>=0&&x<=0xffffffff,'u32');const b=Buffer.alloc(4);b.writeUInt32BE(x);return b; };
const u64 = x => { requireValue(decimal.test(String(x))&&BigInt(x)<=0x7fffffffffffffffn,'u64');const b=Buffer.alloc(8);b.writeBigUInt64BE(BigInt(x));return b; };
const frame = x => { requireValue(typeof x==='string','string');const b=bytes(x);return Buffer.concat([u32(b.length),b]); };
const side = x => { requireValue(x==='BUY'||x==='SELL','side');return Buffer.from([x==='BUY'?1:2]); };
const sortedParties = parties => [...parties].sort((a,b)=>a.participantId<b.participantId?-1:a.participantId>b.participantId?1:a.accountId<b.accountId?-1:a.accountId>b.accountId?1:0);
export function profileBytes(profile) {
  return bytes(JSON.stringify({schema:profile.schema,runIds:[...profile.runIds].sort(),venueSessionId:profile.venueSessionId,instrumentId:profile.instrumentId,currency:profile.currency,maxOrderIds:profile.maxOrderIds,maxQuantityUnits:profile.maxQuantityUnits,maxLimitPrice:profile.maxLimitPrice}));
}
export function budgetBytes(o) {
  const b=o.budget;
  return Buffer.concat([bytes('calcify-finite-budget-v1\0'),Buffer.from(o.profileHash,'hex'),...['policyId','runId','venueSessionId','instrumentId','quoteCurrency'].map(k=>frame(o[k])),u32(o.parties.length),...sortedParties(o.parties).flatMap(p=>[frame(p.participantId),frame(p.accountId),side(p.side)]),...['attempts','publications','commandBytes','outcomes','trades','openWindows','sourceBytes','captureBytes','attemptBytes','openingBytes'].map(k=>u64(b[k]))]);
}
export function bindingBytes(o) {
  requireValue(hashPattern.test(o.profileHash)&&hashPattern.test(o.budgetHash),'binding hashes');
  requireValue(o.schema==='calcify-finite-binding-v1'&&o.openingMode==='GENESIS'&&o.partition===0&&o.partitionCount===1,'opening scope');
  const roles=['COMMAND','SOURCE','CAPTURE','OWNERSHIP','CAPTURE_CHANGELOG'];
  requireValue(o.topics.length===5&&o.topics.every((t,i)=>t.role===roles[i]&&/^[A-Za-z0-9_-]{22}$/.test(t.topicId)),'topic identities');
  return Buffer.concat([bytes('calcify-finite-binding-v1\0'),Buffer.from(o.profileHash,'hex'),Buffer.from(o.budgetHash,'hex'),...['lifecycleSchema','policyId','runId','venueSessionId','instrumentId','quoteCurrency','baseAssetId'].map(k=>frame(o[k])),u32(o.parties.length),...sortedParties(o.parties).flatMap(p=>[frame(p.participantId),frame(p.accountId),side(p.side)]),...o.topics.flatMap(t=>[frame(t.name),frame(t.topicId)]),u32(o.sourceGeneration),u32(o.partition),u32(o.partitionCount),...['commandConsumerGroup','ownershipGroup','matcherShardId','commandSubjectPrefix','sourceSubjectPrefix','brokerClusterId','captureApplicationId','storeName','stateVersion'].map(k=>frame(o[k])),Buffer.from([1]),...['initialCommandNextOffset','initialSourceNextOffset','initialCaptureNextOffset','initialCaptureSourceGroupNextOffset'].map(k=>{requireValue(o[k]==='0','genesis vector');return u64(o[k]);})]);
}
export function instantiateOpening(template,prefix,metadata,referenceRow) {
  requireValue(namePattern.test(prefix),'owned prefix');
  requireValue(referenceRow.instrumentId===template.instrumentId&&referenceRow.baseAssetId===template.baseAssetId&&referenceRow.quoteCurrency===template.quoteCurrency,'actual seeded reference mismatch');
  requireValue(typeof metadata.clusterId==='string'&&metadata.clusterId.length>0,'actual cluster identity');
  const profile={schema:'calcify-finite-source-v1',runIds:[prefix],venueSessionId:template.venueSessionId,instrumentId:template.instrumentId,currency:template.quoteCurrency,maxOrderIds:8,maxQuantityUnits:10,maxLimitPrice:100000000000};
  const app=prefix+'-capture';
  const names=[prefix+'-commands',prefix+'-source',prefix+'-capture',prefix+'-ownership',app+'-finite-lifecycle-changelog'];
  const roles=['COMMAND','SOURCE','CAPTURE','OWNERSHIP','CAPTURE_CHANGELOG'];
  const topics=names.map((name,i)=>{const t=metadata.topics.find(x=>x.name===name);requireValue(t&&t.partitions===1&&t.lowOffset==='0'&&t.highWatermark==='0','actual empty genesis required');requireValue(t.uuid!=='AAAAAAAAAAAAAAAAAAAAAA','zero UUID');return {role:roles[i],name,topicId:t.uuid};});
  const o={schema:'calcify-finite-binding-v1',profile,profileHash:sha256(profileBytes(profile)),budget:template.budget,budgetHash:'',lifecycleSchema:'calcify-order-lifecycle-command-v1',policyId:'calcify-limit-day-v1',runId:prefix,venueSessionId:template.venueSessionId,instrumentId:template.instrumentId,quoteCurrency:template.quoteCurrency,baseAssetId:referenceRow.baseAssetId,parties:template.parties,topics,sourceGeneration:1,partition:0,partitionCount:1,commandConsumerGroup:prefix+'-engine-p00',ownershipGroup:prefix+'-ownership-group',matcherShardId:prefix+'-shard',commandSubjectPrefix:prefix+'.commands',sourceSubjectPrefix:prefix+'.events',brokerClusterId:metadata.clusterId,captureApplicationId:app,storeName:'finite-lifecycle',stateVersion:'calcify-finite-capture-state-v1',openingMode:'GENESIS',initialCommandNextOffset:'0',initialSourceNextOffset:'0',initialCaptureNextOffset:'0',initialCaptureSourceGroupNextOffset:'0'};
  o.budgetHash=sha256(budgetBytes(o));const canonical=bindingBytes(o);requireValue(canonical.length<=16384,'opening byte cap');return {opening:o,canonical, digest:sha256(canonical)};
}
export function fixtureCommands(template,prefix) {
  requireValue(template.commands.length===12&&namePattern.test(prefix),'finite fixture');
  return template.commands.map((c,i)=>{
    const p=template.parties.find(p=>p.side===c.party);requireValue(p,'party');
    const commandId=prefix+'-cmd-'+String(i+1).padStart(2,'0');
    const body={commandId,traceId:'trace-'+commandId,causationId:'cause-'+commandId,correlationId:'corr-'+commandId,actorId:p.participantId,runId:prefix,venueSessionId:template.venueSessionId,occurredAt:`2026-10-10T03:00:${String(i).padStart(2,'0')}Z`,orderId:c.orderId,instrumentId:template.instrumentId,participantId:p.participantId,accountId:p.accountId};
    if(c.kind==='submit')Object.assign(body,{side:p.side,orderType:'LIMIT',quantityUnits:c.quantityUnits,limitPrice:c.limitPrice,currency:template.quoteCurrency,timeInForce:'DAY'});
    else if(c.kind==='modify')Object.assign(body,{quantityUnits:c.newQuantityUnits,limitPrice:c.newLimitPrice});
    else Object.assign(body,{reason:c.reason});
    return {path:'/api/v1/orders/'+c.kind,headers:{'Idempotency-Key':commandId,'X-Client-Id':p.participantId},body};
  });
}
export function validateLease(lease,now=Date.now(),{cleanupOnly=false}={}) {
  requireValue(lease.schema==='calcify-finite-o2-lease-v1'&&namePattern.test(lease.project)&&lease.prefix===lease.project,'manager lease identity');
  requireValue(Number.isFinite(Date.parse(lease.expiresAt))&&(cleanupOnly||Date.parse(lease.expiresAt)>now),'expired lease');
  requireValue(/^[0-9a-f]{40}$/.test(lease.sourceRevision),'source revision');
  for(const k of ['proofRoot','secretRoot','stateRoot'])requireValue(isAbsolute(lease[k])&&lease[k].startsWith('/private/tmp/')&&lease[k].includes(lease.project),'private owned path');
  requireValue(new Set([lease.proofRoot,lease.secretRoot,lease.stateRoot]).size===3,'distinct private roots');
  requireValue(lease.scope==='pg-test'||lease.scope==='full','resource scope');
  requireValue(/^10\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\/28$/.test(lease.networkSubnet),'explicit researched private /28 required');
  if(lease.scope==='full'){
    requireValue(lease.images?.broker===RP_IMAGE&&lease.images?.postgres===PG_IMAGE,'image digests');
    requireValue(/^sha256:[0-9a-f]{64}$/.test(lease.images?.runtime)&&/^sha256:[0-9a-f]{64}$/.test(lease.images?.matcher),'immutable app image IDs');
    requireValue(lease.artifacts?.sourceManifest&&lease.artifacts?.distribution&&lease.artifacts?.matcherBinary&&lease.artifacts?.probeBinary,'fresh artifacts');
  }
  return lease;
}
export function checkOwned(container,lease,service,expectedImage) {
  requireValue(/^[0-9a-f]{64}$/.test(container.Id),'container ID');
  const labels=container.Config?.Labels??{};
  requireValue(labels['reef.test']==='calcify-finite-o2'&&labels['com.docker.compose.project']===lease.project&&labels['com.docker.compose.service']===service,'ownership labels');
  requireValue(container.Image===expectedImage,'image drift');
  requireValue(!Object.keys(container.NetworkSettings?.Ports??{}).some(k=>container.NetworkSettings.Ports[k]?.length),'published ports');
  requireValue(!container.Mounts?.some(m=>m.Source==='/var/run/docker.sock'||m.Destination==='/var/run/docker.sock'),'Docker socket mount');
  if(lease.scope==='full'){
    const caps={broker:[2048,2],postgres:[512,1],runtime:[1024,2],matcher:[128,1]};const [memory,cpu]=caps[service]??[];
    requireValue(container.HostConfig?.Memory===memory*1024**2&&container.HostConfig?.NanoCpus===cpu*1e9,'actual resource caps');
    requireValue(container.HostConfig?.RestartPolicy?.Name==='no'&&container.HostConfig?.Privileged===false,'restart/privilege drift');
    requireValue(Object.keys(container.NetworkSettings?.Networks??{}).join()===lease.project+'-private','private network drift');
    requireValue(!container.Mounts.some(m=>m.Type==='volume'&&!(m.Name??'').startsWith(lease.project+'-')),'volume drift');
    const mountSource=m=>m.Source.startsWith('/host_mnt/')?m.Source.slice('/host_mnt'.length):m.Source;
    const allowed=service==='broker'?[lease.artifacts.probeBinary,join(here,'finite-o2/redpanda.yaml')]:service==='postgres'?[lease.secretRoot]:service==='runtime'?[lease.artifacts.distribution,lease.artifacts.faultProbeDistribution??lease.artifacts.distribution,lease.stateRoot,lease.secretRoot]:[lease.artifacts.matcherBinary,lease.secretRoot];
    requireValue(container.Mounts.every(m=>m.Type==='volume'||allowed.some(p=>mountSource(m)===p||mountSource(m).startsWith(p+'/'))),'bind mount drift');
    const credentials={broker:[],postgres:['installer-password','runtime.pgpass','registrar.pgpass'],runtime:['runtime-password','ingress-password','capture-password','service-token','client-token'],matcher:['matcher-password','service-token']}[service];
    for(const mount of container.Mounts){if(mountSource(mount)===lease.secretRoot||mountSource(mount).startsWith(lease.secretRoot+'/'))requireValue(mount.Type==='bind'&&credentials.some(name=>mountSource(mount)===join(lease.secretRoot,name))&&mount.RW===false,'credential mount expansion');}
    if(service==='runtime'||service==='matcher')requireValue(container.HostConfig.ReadonlyRootfs===true,'app root write expansion');
  }
  return container.Id;
}
export function validateSample(s) {
  requireValue(s.schema==='calcify-finite-o2-resource-sample-v1'&&Array.isArray(s.processes)&&s.processes.length>0,'resource sample');
  requireValue(Number.isSafeInteger(s.allocatedBytes)&&s.allocatedBytes>=0&&s.allocatedBytes<LIMITS.diskAbortBytes,'disk abort');
  requireValue(Number.isSafeInteger(s.proofBytes)&&s.proofBytes>=0&&s.proofBytes<LIMITS.proofBytes,'proof abort');
  requireValue(Number.isSafeInteger(s.guestFreeBytes)&&s.guestFreeBytes>=20*1024**3,'guest disk floor');
  let total=0;for(const p of s.processes){requireValue(Number.isSafeInteger(p.memoryBytes)&&p.memoryBytes>=0&&Number.isSafeInteger(p.capBytes)&&p.memoryBytes<=p.capBytes,'memory sample/cap');total+=p.capBytes;}
  requireValue(total<=LIMITS.memoryMiB*1024**2,'aggregate memory cap');
  requireValue(s.controllerRssBytes<=LIMITS.controllerBytes,'controller RSS');return s;
}
export function assertProbe(r,status='ok') {
  requireValue(r.schema==='calcify-finite-probe-result-v1'&&r.status===status,'probe semantic status');
  if(status==='denied')requireValue(['29','30','31','53'].includes(r.code),'actual authorization error');
  if(status==='authentication')requireValue(r.code==='58','actual authentication error');
  if(status==='security-refusal')requireValue(r.code==='34','actual unauthenticated protocol state error');
  return r.data;
}
export function validateAdverse(q,lease,opening){
  const role=q.mode==='recreate-source'?'SOURCE':q.mode==='truncate-command-genesis'?'COMMAND':null;
  requireValue(role&&lease.scope==='full'&&lease.adverse===(role==='SOURCE'?'topic-identity':'command-history'),'dedicated adverse lease');
  const topic=opening.topics.find(t=>t.role===role);requireValue(q.adverse===lease.adverse&&q.topic===topic.name&&q.topicId===topic.topicId&&q.auth.username===lease.prefix+'-operator','exact adverse identity/operator');
  if(role==='COMMAND')requireValue(q.offset===1,'exact genesis trim only');return role;
}
export function noSecretSpill(output,secrets) {
  const b=Buffer.isBuffer(output)?output:bytes(output);
  for(const s of secrets){if(!s)continue;requireValue(!b.includes(bytes(s))&&!b.includes(bytes(Buffer.from(s).toString('base64'))),'secret spill');}
  return b;
}
export async function boundedCommand(executable,argv,{input,timeoutMs=20000,maxBytes=2*1024**2,secrets=[],env=process.env,cwd=ROOT}={}) {
  requireValue(isAbsolute(executable)&&Array.isArray(argv),'argument vector required');
  for(const arg of argv)noSecretSpill(String(arg),secrets);
  const child=spawn(executable,argv,{cwd,env,stdio:['pipe','pipe','pipe']});
  const chunks={stdout:[],stderr:[]};let length=0,reason=null;
  const collect=(which,b)=>{length+=b.length;if(length>maxBytes){reason='output cap';child.kill('SIGKILL');}else chunks[which].push(b);};
  child.stdout.on('data',b=>collect('stdout',b));child.stderr.on('data',b=>collect('stderr',b));
  child.stdin.on('error',()=>{});child.stdin.end(input);
  const timer=setTimeout(()=>{reason='command deadline';child.kill('SIGKILL');},timeoutMs);
  let exit;try{exit=await new Promise((ok,fail)=>{child.once('error',fail);child.once('close',(code,signal)=>ok({code,signal}));});}finally{clearTimeout(timer);}
  const stdout=noSecretSpill(Buffer.concat(chunks.stdout),secrets),stderr=noSecretSpill(Buffer.concat(chunks.stderr),secrets);
  requireValue(!reason,reason);return {executable,argv,exit,...{stdout:stdout.toString('utf8'),stderr:stderr.toString('utf8')}};
}
async function pathGuard(path,kind='directory'){
  const s=await lstat(path);requireValue(!s.isSymbolicLink()&&(kind==='directory'?s.isDirectory():s.isFile()),'artifact symlink/type');requireValue(await realpath(path)===path,'noncanonical path');return s;
}
async function rawTree(path){const s=await pathGuard(path,'file').catch(async()=>pathGuard(path));if(s.isFile())return s.size;let n=0;for(const name of await readdir(path))n+=await rawTree(join(path,name));return n;}
// One reused64KiB buffer; serialization prevents concurrent digests sharing bytes.
const artifactHashBuffer=Buffer.allocUnsafe(64*1024);let artifactHashTail=Promise.resolve();
export function hashArtifactFile(path){const next=artifactHashTail.then(()=>hashArtifactFileLocked(path));artifactHashTail=next.catch(()=>{});return next;}
async function hashArtifactFileLocked(path){
  const before=await pathGuard(path,'file'),digest=createHash('sha256');let count=0;const handle=await open(path,fsConstants.O_RDONLY|fsConstants.O_NOFOLLOW);
  try{const opened=await handle.stat();requireValue(opened.ino===before.ino&&opened.dev===before.dev,'artifact replaced before hash');while(true){const {bytesRead}=await handle.read(artifactHashBuffer,0,artifactHashBuffer.length,null);if(!bytesRead)break;count+=bytesRead;requireValue(count<=before.size,'artifact grew during hash');digest.update(artifactHashBuffer.subarray(0,bytesRead));}}finally{await handle.close();}
  const after=await pathGuard(path,'file');requireValue(count===before.size&&after.size===before.size&&after.ino===before.ino&&after.dev===before.dev&&after.mtimeMs===before.mtimeMs,'artifact changed during hash');return {bytes:count,sha256:digest.digest('hex')};
}
export async function freezeArtifacts(root){
  await pathGuard(root);const files=[];
  async function walk(path,relative){for(const name of (await readdir(path)).sort()){const p=join(path,name),s=await lstat(p);requireValue(!s.isSymbolicLink(),'artifact symlink');if(s.isDirectory())await walk(p,relative+name+'/');else{requireValue(s.isFile(),'artifact type');files.push({path:relative+name,...await hashArtifactFile(p)});}}}
  await walk(root,'');requireValue(files.length>0,'empty artifacts');return {root,files,manifestSha256:sha256(JSON.stringify(files))};
}
const quoteSQL = s => "'"+String(s).replaceAll("'","''")+"'";
export function semanticChecksum(batch) {
  const object={...batch};for(const k of ['createdAt','workFinishedAt','timingChecksum','payloadChecksum','payloadChecksumAlgorithm'])delete object[k];
  const token=(kind,value)=>Buffer.concat([bytes(kind+Buffer.byteLength(value)+':'),bytes(value)]);
  function encode(v){if(v===null)return token('n','');if(typeof v==='boolean')return token('b',v?'1':'0');if(typeof v==='string')return token('s',v);if(typeof v==='number'){requireValue(Number.isSafeInteger(v),'unsafe source number');return token('d',String(v));}if(Array.isArray(v))return Buffer.concat([token('a',String(v.length)),...v.map(encode)]);const keys=Object.keys(v).sort();return Buffer.concat([token('o',String(keys.length)),...keys.flatMap(k=>[token('s',k),encode(v[k])])]);}
  return sha256(encode(object));
}
export function protobufFields(raw) {
  requireValue(raw.length<=131072,'capture byte cap');let at=0;const fields=[];
  function varint(){let n=0n,shift=0n;for(let i=0;i<10;i++){requireValue(at<raw.length,'truncated protobuf');const b=raw[at++];n|=BigInt(b&127)<<shift;if(!(b&128))return n;shift+=7n;}throw Error('protobuf varint');}
  while(at<raw.length){requireValue(fields.length<4096,'protobuf field cap');const start=at,key=varint(),id=Number(key>>3n),wire=Number(key&7n);requireValue(id>0,'protobuf field zero');let value;if(wire===0)value=varint();else if(wire===2){const length=Number(varint());requireValue(Number.isSafeInteger(length)&&at+length<=raw.length,'protobuf length');value=raw.subarray(at,at+length);at+=length;}else if(wire===1||wire===5){const length=wire===1?8:4;requireValue(at+length<=raw.length,'protobuf fixed width');value=raw.subarray(at,at+length);at+=length;}else throw Error('protobuf wire');fields.push({id,wire,value,raw:raw.subarray(start,at)});}
  return fields;
}
const pbMany=(f,id)=>f.filter(x=>x.id===id);
const pbOne=(f,id)=>{const a=pbMany(f,id);requireValue(a.length<=1,'duplicate protobuf scalar');return a[0]?.value;};
const pbText=(f,id)=>(pbOne(f,id)??Buffer.alloc(0)).toString('utf8');
const pbInt=(f,id)=>String(pbOne(f,id)??0n);
const pbNested=(f,id)=>protobufFields(pbOne(f,id)??Buffer.alloc(0));
export function validateFlow(template,opening,digest,commands,source,capture,journal) {
  requireValue(commands.records.length===12&&source.records.length===12&&capture.records.length===12&&journal.length===12,'exact finite record coverage');
  let applied=0,rejected=0,members=0;const trades=[];
  const expected=fixtureCommands(template,opening.runId);
  for(let i=0;i<12;i++){
    const command=commands.records[i],row=source.records[i],output=capture.records[i],wire=Buffer.from(row.valueBase64,'base64'),batch=JSON.parse(wire);
    requireValue(sha256(wire)===row.payloadSha256&&batch.payloadChecksum===semanticChecksum(batch),'source checksum');
    requireValue(command.offset===String(i)&&journal[i].commandId===expected[i].body.commandId&&journal[i].state==='PUBLISHED'&&journal[i].offset===command.offset&&journal[i].payloadHash===command.payloadSha256,'durable publication correspondence');
    requireValue(batch.outcomes.length===1&&batch.commandCount===1&&batch.shardId===opening.matcherShardId&&batch.partition===0,'source scope');
    const fact=batch.outcomes[0];requireValue(fact.commandId===expected[i].body.commandId&&fact.runId===opening.runId&&fact.orderId===expected[i].body.orderId&&fact.payloadHash===command.payloadSha256,'source command correspondence');
    const status=i<8?'accepted':'rejected';requireValue(fact.status.toLowerCase()===status,'expected outcome');if(status==='accepted')applied++;else rejected++;
    const life=fact.lifecycleCommand;requireValue(life.finiteBindingDigest===digest&&life.sourceProfileHash===opening.profileHash&&life.commandId===fact.commandId&&life.runId===opening.runId&&life.venueSessionId===opening.venueSessionId&&life.instrumentId===opening.instrumentId&&life.orderId===fact.orderId,'typed lifecycle scope');
    const actualTrades=fact.result.trades??[];trades.push(...actualTrades.map(t=>({buy:t.buyOrderId,sell:t.sellOrderId,units:t.quantityUnits,price:t.price,currency:t.currency})));
    const raw=Buffer.from(output.valueBase64,'base64'),f=protobufFields(raw),provenance=pbNested(f,3),frontier=pbNested(f,10),ms=pbMany(f,4);
    requireValue(sha256(raw)===output.payloadSha256&&pbText(f,1)==='calcify-finite-lifecycle-capture-v1'&&pbText(f,2)===digest,'capture identity');
    requireValue(pbText(f,12)===sha256(Buffer.concat([bytes('calcify-finite-capture-content-v1\0'),...f.filter(x=>x.id!==12).map(x=>x.raw)])),'capture content digest');
    requireValue(pbInt(provenance,1)===String(opening.sourceGeneration)&&pbInt(provenance,2)==='0'&&pbInt(provenance,3)===row.offset&&pbText(provenance,5)===opening.topics[1].name&&pbText(provenance,6)===opening.topics[1].topicId&&pbText(provenance,7)===batch.batchId&&pbText(provenance,8)===batch.payloadChecksum,'capture provenance');
    requireValue(Buffer.concat(provenance.map(x=>x.raw)).equals(Buffer.concat(frontier.map(x=>x.raw)))&&BigInt(pbInt(f,11))>BigInt(row.offset)&&pbInt(f,13)==='1','prefix closure/frontier');
    requireValue(pbInt(f,5)==='1'&&pbInt(f,6)===String(actualTrades.length)&&pbInt(f,7)===String(ms.length)&&ms.length===1+actualTrades.length&&pbInt(f,8)===String(wire.length)&&pbText(f,9)===sha256(wire),'capture coverage counts');
    const cm=protobufFields(ms[0].value),typed=pbNested(cm,3);requireValue(pbInt(cm,2)===(status==='accepted'?'1':'2')&&pbText(cm,4)===fact.payloadHash&&pbText(typed,5)===fact.commandId&&pbText(typed,3)===digest&&pbText(typed,6)===opening.runId,'capture command facts');
    actualTrades.forEach((t,j)=>{const m=protobufFields(ms[j+1].value),trade=pbNested(m,8),value=pbNested(trade,1);requireValue(pbInt(m,2)==='3'&&pbText(trade,3)===opening.runId&&pbText(value,4)===t.buyOrderId&&pbText(value,5)===t.sellOrderId&&pbText(pbNested(value,7),1)===t.quantityUnits&&pbText(pbNested(value,8),1)===t.price&&pbText(pbNested(value,8),2)===t.currency,'capture execution facts');});members+=ms.length;
  }
  const wanted=[{buy:'p0-3',sell:'p0-1',units:'3',price:'100000000000',currency:'USD'},{buy:'p0-3',sell:'p0-2',units:'1',price:'100000000000',currency:'USD'},{buy:'p0-4',sell:'p0-5',units:'2',price:'100000000000',currency:'USD'}];
  requireValue(JSON.stringify(trades)===JSON.stringify(wanted)&&applied===8&&rejected===4&&members===15,'fixture financial facts');
  return {schema:'calcify-finite-o2-flow-proof-v1',commandRecords:12,sourceRecords:12,captureRecords:12,applied,rejected,members,trades,scope:'Finite order lifecycle capture; no settlement/throughput claim'};
}
export function validateFaultScope(fault,prefix){
 requireValue(fault&&['RESERVED','PUBLISHING','BROKER_ACK','PUBLISHED'].includes(fault.boundary)&&!Object.hasOwn(fault,'captureSourceOffset'),'command-bound fault scope');
 const mixed=Object.hasOwn(fault,'captureCommandId');requireValue(mixed?fault.boundary==='PUBLISHING'&&fault.commandId===prefix+'-cmd-02'&&fault.captureCommandId===prefix+'-cmd-01':fault.commandId===prefix+'-cmd-01','dedicated fault command target');return mixed;
}
export function validateCaptureCommandProof({opening,digest,fault,ingress,event,sourceHistory,sourceMetadata,commandHistory,journal,cuts}){
 requireValue(validateFaultScope(fault,opening.runId)&&sha256(bindingBytes(opening))===digest,'mixed capture binding');const target=fault.captureCommandId,topic=opening.topics[1];
 requireValue(event.schema==='calcify-finite-fault-probe-v1'&&event.boundary==='CAPTURE_PROCESSED_BEFORE_COMMIT'&&event.pid===ingress.pid&&decimal.test(event.pid)&&event.bindingDigest===digest&&event.classManifestHash===ingress.classManifestHash&&hashPattern.test(event.classManifestHash),'samecomposition capture identity');
 requireValue(event.captureCommandId===target&&Array.isArray(event.commandIds)&&event.commandIds.length===1&&event.commandIds[0]===target,'actual command-bound capture member');
 requireValue(event.sourceTopic===topic.name&&event.sourceTopicId===topic.topicId&&event.sourceGeneration===opening.sourceGeneration&&event.sourcePartition===opening.partition,'actual capture source lane');
 requireValue(decimal.test(event.sourceOffset)&&BigInt(event.sourceOffset)<0x7fffffffffffffffn&&event.completedFrontierSourceOffset===event.sourceOffset&&event.resumeOffset===String(BigInt(event.sourceOffset)+1n)&&event.completedRecordsCount===1&&hashPattern.test(event.stateDigest)&&hashPattern.test(event.sourceContentDigest),'actual capture completed frontier');
 requireValue(sourceMetadata.topics?.length===1&&sourceMetadata.topics[0].name===topic.name&&sourceMetadata.topics[0].uuid===topic.topicId&&sourceMetadata.topics[0].lowOffset==='0','fresh source UUID/genesis');
 requireValue(sourceHistory.topic===topic.name&&sourceHistory.lowOffset==='0'&&sourceHistory.records.length===1&&commandHistory.topic===opening.topics[0].name&&commandHistory.records.length===1,'unique first committed source/command');
 const row=sourceHistory.records[0],raw=Buffer.from(row.valueBase64,'base64'),batch=JSON.parse(raw),command=commandHistory.records[0],commandRaw=Buffer.from(command.valueBase64,'base64'),commandBody=JSON.parse(commandRaw),entry=journal.find(j=>j.commandId===target);
 requireValue(raw.length===row.bytes&&raw.length<=Number(opening.budget.sourceBytes)&&sha256(raw)===row.payloadSha256&&row.payloadSha256===event.sourceContentDigest&&row.offset===event.sourceOffset&&batch.payloadChecksum===semanticChecksum(batch),'retained source rawhash/checksum/offset');
 requireValue(commandRaw.length===command.bytes&&sha256(commandRaw)===command.payloadSha256&&commandBody.commandId===target&&entry?.state==='PUBLISHED'&&entry.offset===command.offset&&entry.payloadHash===command.payloadSha256,'actual durable first command');
 requireValue(batch.commandCount===1&&batch.outcomes?.length===1&&batch.shardId===opening.matcherShardId&&batch.partition===opening.partition,'retained source scope');const fact=batch.outcomes[0],life=fact.lifecycleCommand;
 requireValue(fact.commandId===target&&fact.runId===opening.runId&&fact.payloadHash===command.payloadSha256&&life?.commandId===target&&life.finiteBindingDigest===digest&&life.sourceProfileHash===opening.profileHash&&life.runId===opening.runId&&life.venueSessionId===opening.venueSessionId&&life.instrumentId===opening.instrumentId,'retained typed lifecycle command');
 const sourceCut=cuts.ranges.find(r=>r.role==='SOURCE'),captureCut=cuts.ranges.find(r=>r.role==='CAPTURE');requireValue(sourceCut?.partition===opening.partition&&sourceCut.beginningOffset==='0'&&sourceHistory.readCommittedEndOffset===sourceCut.readCommittedEndOffset&&BigInt(sourceCut.readCommittedEndOffset)>BigInt(row.offset)&&cuts.captureSourceGroup.nextOffset==='0'&&captureCut?.readCommittedEndOffset==='0','actual preEOS source/capture cuts');
 return {captureCommandId:target,sourceOffset:row.offset,sourcePayloadSha256:row.payloadSha256,sourceSemanticChecksum:batch.payloadChecksum,sourceBatchId:batch.batchId,sourceReadCommittedEndOffset:sourceHistory.readCommittedEndOffset,resumeOffset:event.resumeOffset};
}
export function validateRestoredCaptureProof(opening,digest,proof,history,cuts){
 requireValue(history.topic===opening.topics[2].name&&history.records.length===1,'one restored committed capture');const row=history.records[0],raw=Buffer.from(row.valueBase64,'base64'),f=protobufFields(raw),source=pbNested(f,3),members=pbMany(f,4),command=pbNested(protobufFields(members[0]?.value??Buffer.alloc(0)),3);
 requireValue(raw.length===row.bytes&&sha256(raw)===row.payloadSha256&&pbText(f,1)==='calcify-finite-lifecycle-capture-v1'&&pbText(f,2)===digest&&pbText(f,12)===sha256(Buffer.concat([bytes('calcify-finite-capture-content-v1\0'),...f.filter(x=>x.id!==12).map(x=>x.raw)])),'restored capture integrity');
 requireValue(pbInt(source,1)===String(opening.sourceGeneration)&&pbInt(source,2)===String(opening.partition)&&pbInt(source,3)===proof.sourceOffset&&pbText(source,5)===opening.topics[1].name&&pbText(source,6)===opening.topics[1].topicId&&pbText(source,7)===proof.sourceBatchId&&pbText(source,8)===proof.sourceSemanticChecksum&&pbText(f,9)===proof.sourcePayloadSha256&&pbText(command,5)===proof.captureCommandId&&pbText(command,3)===digest,'restored actual command/source provenance');
 requireValue(pbInt(f,13)==='1'&&pbInt(f,11)===proof.resumeOffset&&cuts.captureSourceGroup.nextOffset===proof.sourceReadCommittedEndOffset&&cuts.ranges.find(r=>r.role==='SOURCE')?.readCommittedEndOffset===proof.sourceReadCommittedEndOffset&&cuts.ranges.find(r=>r.role==='CAPTURE')?.readCommittedEndOffset===history.readCommittedEndOffset,'restored group and application cuts');return {capturePayloadSha256:row.payloadSha256,captureCommandId:proof.captureCommandId,sourceOffset:proof.sourceOffset,representedNextOffset:proof.resumeOffset};
}
export function validateAppLifecycleSample(sample,owner,armed,lease,service,running,id,image,requestedAt,now=Date.now()){
 requireValue(['runtime','matcher'].includes(service)&&typeof running==='boolean','app lifecycle target');
 requireValue(owner.schema==='calcify-finite-o2-monitor-owner-v1'&&owner.project===lease.project&&owner.sourceRevision===lease.sourceRevision&&Number.isSafeInteger(owner.pid)&&owner.pid>0&&owner.pid!==process.pid&&Number.isFinite(Date.parse(owner.processStartedAt)),'retirement monitor owner');
 requireValue(armed.schema==='calcify-finite-o2-monitor-armed-v1'&&armed.project===lease.project&&armed.sourceRevision===lease.sourceRevision&&armed.monitorPid===owner.pid&&armed.firstSample==='RESOURCE_SAMPLE_000001.json'&&Date.parse(armed.observedAt)<=requestedAt&&Date.parse(owner.processStartedAt)<=Date.parse(armed.observedAt),'retirement monitor armed');
 requireValue(sample.schema==='calcify-finite-o2-resource-sample-v1'&&sample.phase==='complete'&&Array.isArray(sample.pending)&&sample.pending.length===0&&sample.leaseProject===lease.project&&sample.sourceRevision===lease.sourceRevision&&sample.monitorPid===owner.pid,'retirement sample scope');validateSample(sample);
 const start=Date.parse(sample.startedAt),end=Date.parse(sample.endedAt);requireValue(Number.isFinite(start)&&Number.isFinite(end)&&start>=requestedAt&&end>=start&&end<=now&&now-end<=10000&&Date.parse(sample.observedAt)>=start&&Date.parse(sample.observedAt)<=end&&sample.durationMs>=0&&sample.durationMs<=4500&&sample.startGapMs>=0&&sample.startGapMs<=10000,'retirement sample freshness');
 requireValue(Array.isArray(sample.inspections)&&Array.isArray(sample.ownedIds),'retirement sample inventory');const apps=sample.inspections.filter(c=>c.Config?.Labels?.['com.docker.compose.service']===service),old=apps.find(c=>c.Id===id);requireValue(old&&apps.length===1,'retirement exact app inventory');checkOwned(old,lease,service,image);
 requireValue(old.State.Running===running&&old.State.Status===(running?'running':'exited')&&(running?Number.isSafeInteger(old.State.Pid)&&old.State.Pid>0:old.State.Pid===0)&&old.HostConfig.RestartPolicy.Name==='no','retirement app state');requireValue(sample.ownedIds.some(c=>c.id===id&&c.service===service&&c.present===true),'retirement owned app');const appProcess=sample.processes.filter(p=>p.id===id);requireValue(appProcess.length===1&&appProcess[0].service===service&&appProcess[0].running===running&&(running?appProcess[0].capBytes===old.HostConfig.Memory&&appProcess[0].configuredCapBytes===old.HostConfig.Memory:appProcess[0].memoryBytes===0&&appProcess[0].capBytes===0),'retirement app measurement');return old;
}
export function validateRuntimeRetirementSample(sample,owner,armed,lease,id,image,requestedAt,now=Date.now()){return validateAppLifecycleSample(sample,owner,armed,lease,'runtime',false,id,image,requestedAt,now);}
export function releaseSQL(digest,epoch,engine,capture,brokerCuts){
  requireValue(hashPattern.test(digest)&&decimal.test(epoch)&&hashPattern.test(engine)&&hashPattern.test(capture),'release identity');
  requireValue(brokerCuts?.commandGroup&&brokerCuts?.captureSourceGroup&&brokerCuts?.ranges?.length===5,'actual broker cuts required');
  // JSONB text serialization belongs to PostgreSQL, not JS opening canonicalizer.
  const cutExpr=`WITH s AS (SELECT boundary.calcify_finite_snapshot(${quoteSQL(digest)}) AS v), c AS (SELECT jsonb_build_object('activationEpoch',v->'run'->>'activationEpoch','recordedAttempts',v->'run'->>'recordedAttempts','possiblePublications',v->'run'->>'possiblePublications','commandPayloadBytes',v->'run'->>'commandPayloadBytes','attemptRecordBytes',v->'run'->>'attemptRecordBytes','journalHash',encode(sha256(convert_to((v->'journal')::text,'UTF8')),'hex'),'brokerCuts',${quoteSQL(JSON.stringify(brokerCuts))}::jsonb) AS cuts FROM s)`;
  return `BEGIN; ${cutExpr} SELECT boundary.calcify_finite_stages(${quoteSQL(digest)},${epoch},${quoteSQL(engine)},${quoteSQL(capture)},cuts) FROM c; ${cutExpr} SELECT boundary.calcify_finite_release(${quoteSQL(digest)},${epoch},${quoteSQL(engine)},${quoteSQL(capture)},cuts) FROM c; COMMIT;`;
}

export function validateBrokerProfile(config){
 requireValue(config.sasl_mechanisms?.includes('PLAIN')&&config.sasl_mechanisms?.includes('SCRAM')&&config.write_caching_default==='false'&&config.auto_create_topics_enabled===false,'broker safety config');
 for(const [key,value] of Object.entries({group_topic_partitions:1,transaction_coordinator_partitions:1,compacted_log_segment_size:8388608,transaction_coordinator_log_segment_size:8388608}))requireValue(config[key]===value,'broker finite geometry '+key);
 return config;
}
export function finiteTopicConfig(role){
 requireValue(['COMMAND','SOURCE','CAPTURE','OWNERSHIP','CAPTURE_CHANGELOG','canary','state-fit','create-positive'].includes(role),'finite topic role');
 return {'cleanup.policy':role==='CAPTURE_CHANGELOG'?'compact':'delete','retention.ms':'-1','retention.bytes':'-1','segment.bytes':['CAPTURE_CHANGELOG','state-fit'].includes(role)?'8388608':'1048576','max.message.bytes':'8388608','write.caching':'false','compression.type':'none'};
}
export function validatePgProfile(profile){
 for(const [key,value] of Object.entries({wal_segment_size:'1MB',min_wal_size:'8MB',max_wal_size:'32MB',fsync:'on',synchronous_commit:'on',full_page_writes:'on'}))requireValue(profile[key]===value,'finite PG profile '+key);
 return profile;
}
export class Supervisor {
  constructor(lease,{run=boundedCommand,docker='/usr/local/bin/docker',cleanupOnly=false}={}) {this.lease=validateLease(lease,Date.now(),{cleanupOnly});this.cleanupOnly=cleanupOnly;this.run=run;this.docker=docker;this.secrets=[];this.ordinal=0;this.env={...process.env};this.ids={};this.receiptTail=Promise.resolve();}
  writeReceipt(name,data){const next=this.receiptTail.then(()=>this._writeReceipt(name,data));this.receiptTail=next.catch(()=>{});return next;}
  async _writeReceipt(name,data){await mkdir(this.lease.proofRoot,{recursive:true});const existing=await readdir(this.lease.proofRoot);for(const file of existing){const n=Number(file.slice(0,4));if(Number.isInteger(n)&&n>this.ordinal)this.ordinal=n;}const out=join(this.lease.proofRoot,String(++this.ordinal).padStart(4,'0')+'-'+name+'.json');const b=bytes(JSON.stringify(data,null,2)+'\n');noSecretSpill(b,this.secrets);requireValue(await rawTree(this.lease.proofRoot)+b.length<LIMITS.proofBytes,'proof raw cap');await writeFile(out,b,{flag:'wx'});return {path:out,sha256:sha256(b)};}
  async command(argv,{input,timeoutMs=20000,maxBytes=2*1024**2,expectedCode=0,record='command'}={}){
    if(this.cleanupOnly)requireValue(['inspect','ps','stop'].includes(argv[0])||(argv[0]==='image'&&argv[1]==='inspect'),'cleanup-only operation');else validateLease(this.lease);
    await this.registerController();
    if(!this.cleanupOnly)await lstat(join(this.lease.proofRoot,'RESOURCE_ABORT.json')).then(()=>{throw Error('resource monitor aborted lease');},e=>{if(e.code!=='ENOENT')throw e;});
    const result=await this.run(this.docker,argv,{input,timeoutMs,maxBytes,secrets:this.secrets,env:this.env});
    await this.writeReceipt(record,result);requireValue(result.exit.code===expectedCode&&!result.exit.signal,'command exit');return result;
  }
  async registerController(){await mkdir(this.lease.proofRoot,{recursive:true,mode:0o700});await chmod(this.lease.proofRoot,0o700);const p=join(this.lease.proofRoot,'controller-state.json'),v={schema:'calcify-finite-o2-controller-state-v1',project:this.lease.project,pid:process.pid,processStartedAt:new Date(Date.now()-process.uptime()*1000).toISOString(),sourceRevision:this.lease.sourceRevision,observedAt:new Date().toISOString()};await writeFile(p,JSON.stringify(v)+'\n',{mode:0o600});}
  async generateSecrets(){
    await mkdir(this.lease.secretRoot,{mode:0o700});await chmod(this.lease.secretRoot,0o700);await mkdir(this.lease.stateRoot,{mode:0o700});
    for(const name of ['installer','runtime','registrar','operator','ingress','matcher','capture','observer','denied','service-token']){
      const value=randomBytes(32).toString('base64url');this.secrets.push(value);const filename=name.endsWith('token')?name:name+'-password';await writeFile(join(this.lease.secretRoot,filename),value+'\n',{mode:0o600,flag:'wx'});
    }
    const fixture=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8'));
    const clients=fixture.parties.map(p=>{const token=randomBytes(32).toString('base64url');this.secrets.push(token);return {clientId:p.participantId,token,actorId:p.participantId,participantId:p.participantId,accountId:p.accountId};});
    await writeFile(join(this.lease.secretRoot,'client-token'),JSON.stringify({schema:'calcify-finite-client-auth-v1',clients})+'\n',{mode:0o600,flag:'wx'});
    for(const role of ['runtime','registrar']){
      const pass=(await readFile(join(this.lease.secretRoot,role+'-password'),'utf8')).trim();
      await writeFile(join(this.lease.secretRoot,role+'.pgpass'),`postgres:5432:finite:calcify_finite_${role}:${pass}\n`,{mode:0o600,flag:'wx'});
    }
  }
  async loadSecrets(){await pathGuard(this.lease.secretRoot);for(const file of await readdir(this.lease.secretRoot)){const p=join(this.lease.secretRoot,file),s=await pathGuard(p,'file');requireValue((s.mode&0o077)===0,'private secret permissions');const value=(await readFile(p,'utf8')).trim();if(file==='client-token'&&this.lease.scope==='pg-test'&&!value.startsWith('{')){this.secrets.push(value);}else if(file==='client-token'){const auth=JSON.parse(value);requireValue(auth.schema==='calcify-finite-client-auth-v1'&&auth.clients?.length===2,'client auth file');for(const c of auth.clients)this.secrets.push(c.token);}else if(!file.endsWith('.pgpass'))this.secrets.push(value);}}
  async secret(name){return (await readFile(join(this.lease.secretRoot,name),'utf8')).trim();}
  async probe(q,status='ok'){
    return assertProbe(await this.probeRaw(q),status);
  }
  async probeRaw(q){
    if(['recreate-source','truncate-command-genesis'].includes(q.mode))await this.guardAdverse(q);
    const request={schema:'calcify-finite-probe-v1',prefix:this.lease.prefix,...q};
    const result=await this.command(['exec','-i',this.ids.broker,'/fixture/finite-probe'],{input:JSON.stringify(request),record:'probe-'+q.mode});
    const receipt=JSON.parse(result.stdout);requireValue(receipt.schema==='calcify-finite-probe-result-v1','raw probe schema');return receipt;
  }
  async auth(role){return {username:this.lease.prefix+'-'+role,password:await this.secret(role+'-password')};}
  compose(phase,...services){return ['compose','--project-name',this.lease.project,'--file',join(here,'finite-o2/compose.yml'),phase,...services];}
  async setupEnv(){
    const a=this.lease.artifacts??{};Object.assign(this.env,{FINITE_PREFIX:this.lease.prefix,FINITE_NETWORK_SUBNET:this.lease.networkSubnet,FINITE_SECRET_ROOT:this.lease.secretRoot,FINITE_STATE_ROOT:this.lease.stateRoot,FINITE_PROBE_BINARY:a.probeBinary,FINITE_MATCHER_BINARY:a.matcherBinary,FINITE_RUNTIME_DIST:a.distribution,FINITE_FAULT_DIST:a.faultProbeDistribution??a.distribution,FINITE_RUNTIME_ENTRYPOINT:'/fixture/platform-runtime/bin/platform-runtime',FINITE_RUNTIME_COMMAND:'',FINITE_RUNTIME_IMAGE:this.lease.images?.runtime,FINITE_MATCHER_IMAGE:this.lease.images?.matcher,FINITE_UID:String(process.getuid()),FINITE_GID:String(process.getgid()),FINITE_BINDING_DIGEST:'not-yet-registered',FINITE_MATCHER_ENV_FILE:join(this.lease.proofRoot,'matcher.env')});
  }
  async inspectOwned(service,image){
    const format='{"Id":{{json .Id}},"Image":{{json .Image}},"State":{{json .State}},"HostConfig":{"Memory":{{json .HostConfig.Memory}},"NanoCpus":{{json .HostConfig.NanoCpus}},"RestartPolicy":{{json .HostConfig.RestartPolicy}},"Privileged":{{json .HostConfig.Privileged}},"ReadonlyRootfs":{{json .HostConfig.ReadonlyRootfs}}},"Config":{"Labels":{{json .Config.Labels}},"Entrypoint":{{json .Config.Entrypoint}},"Cmd":{{json .Config.Cmd}},"User":{{json .Config.User}}},"Mounts":{{json .Mounts}},"NetworkSettings":{"Ports":{{json .NetworkSettings.Ports}},"Networks":{{json .NetworkSettings.Networks}}}}';
    const result=await this.command(['inspect','--format',format,this.ids[service]],{record:'sanitized-inspect-'+service});
    const row=JSON.parse(result.stdout);checkOwned(row,this.lease,service,image);return row;
  }
  async createPgTest(){
    requireValue(this.lease.scope==='pg-test','PG-only lease');await this.generateSecrets();
    const name=this.lease.project+'-postgres',volume=this.lease.project+'-postgres-data',network=this.lease.project+'-private';
    await this.writeReceipt('pg-test-intent',{schema:'calcify-finite-o2-pg-test-intent-v1',project:this.lease.project,container:name,network,volume,image:PG_IMAGE,memoryBytes:512*1024**2,cpus:1,publishedHost:'127.0.0.1',publishedPort:'dynamic',labels:{'reef.test':'calcify-finite-o2','com.docker.compose.project':this.lease.project,'com.docker.compose.service':'postgres'}});
    // PG-only host JDBC suite needs a private bridge with loopback publishing.
    // Full broker fixture uses Compose internal network and no published ports.
    await this.command(['network','create','--subnet',this.lease.networkSubnet,'--label','reef.test=calcify-finite-o2','--label','com.docker.compose.project='+this.lease.project,network]);
    await this.command(['volume','create','--label','reef.test=calcify-finite-o2','--label','com.docker.compose.project='+this.lease.project,volume]);
    const result=await this.command(['run','--detach','--name',name,'--network',network,'--memory','512m','--cpus','1','--label','reef.test=calcify-finite-o2','--label','com.docker.compose.project='+this.lease.project,'--label','com.docker.compose.service=postgres','--publish','127.0.0.1::5432','--mount','type=volume,source='+volume+',target=/var/lib/postgresql/data','--mount','type=bind,source='+join(this.lease.secretRoot,'installer-password')+',target=/run/secrets/installer-password,readonly','--env','POSTGRES_DB=finite','--env','POSTGRES_USER=fixture_installer','--env','POSTGRES_PASSWORD_FILE=/run/secrets/installer-password','--env','POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256',PG_IMAGE,'postgres','-c','max_connections=32','-c','shared_buffers=64MB','-c','min_wal_size=80MB','-c','max_wal_size=256MB']);
    this.ids.postgres=result.stdout.trim();requireValue(/^[0-9a-f]{64}$/.test(this.ids.postgres),'actual PG container ID');
    await this.writeReceipt('owned-pg-resource',{containerId:this.ids.postgres,name,volume,network,image:PG_IMAGE,memoryBytes:512*1024**2});
    return this.ids.postgres;
  }
  async initializeSql(migrationFile){
    requireValue(this.ids.postgres,'owned PG required');
    // Roles/passwords are fixture provisioning only. No secret-bearing SQL retained.
    const roleSql=`CREATE ROLE calcify_finite_owner NOLOGIN; CREATE ROLE calcify_finite_runtime LOGIN PASSWORD ${quoteSQL(await this.secret('runtime-password'))}; CREATE ROLE calcify_finite_registrar LOGIN PASSWORD ${quoteSQL(await this.secret('registrar-password'))}; CREATE SCHEMA boundary AUTHORIZATION calcify_finite_owner; CREATE SCHEMA runtime AUTHORIZATION calcify_finite_owner; SET ROLE calcify_finite_owner;`;
    const migrations=[join(ROOT,'scripts/dev/db/migrations/boundary/0005_stream_command_intake.sql'),join(ROOT,'scripts/dev/db/migrations/boundary/0011_stream_command_intake_scope_columns.sql'),migrationFile,...['0070_calcify_phase1_receipts.sql','0071_calcify_source_topic_identity.sql','0072_calcify_topic_identities.sql','0076_calcify_finite_topic_roles.sql'].map(n=>join(ROOT,'scripts/dev/db/migrations/runtime',n))];
    const sql=roleSql+'\n'+(await Promise.all(migrations.map(p=>readFile(p,'utf8')))).join('\n')+'\nRESET ROLE;\n';
    const result=await this.command(['exec','-i',this.ids.postgres,'psql','-X','-U','fixture_installer','--single-transaction','-d','finite','-v','ON_ERROR_STOP=1'],{input:sql,record:'pg-minimal-migrations'});
    return {exit:result.exit,migrations:await Promise.all(migrations.map(async p=>({path:p,sha256:sha256(await readFile(p))})))};
  }
  async initializeFreshDatabase(database,migrationFile){
    requireValue(this.ids.postgres&&/^finite_corrected_[a-z0-9_]+$/.test(database),'owned corrected DB required');
    await this.command(['exec','-i',this.ids.postgres,'psql','-X','-U','fixture_installer','-d','finite','-v','ON_ERROR_STOP=1'],{input:`CREATE DATABASE ${database};`,record:'corrected-database-create'});
    const migrations=[join(ROOT,'scripts/dev/db/migrations/boundary/0005_stream_command_intake.sql'),join(ROOT,'scripts/dev/db/migrations/boundary/0011_stream_command_intake_scope_columns.sql'),migrationFile];
    const sql='CREATE SCHEMA boundary AUTHORIZATION calcify_finite_owner; CREATE SCHEMA runtime AUTHORIZATION calcify_finite_owner; SET ROLE calcify_finite_owner;\n'+(await Promise.all(migrations.map(p=>readFile(p,'utf8')))).join('\n')+'\nRESET ROLE;\n';
    const result=await this.command(['exec','-i',this.ids.postgres,'psql','-X','--single-transaction','-U','fixture_installer','-d',database,'-v','ON_ERROR_STOP=1'],{input:sql,record:'corrected-database-migrations'});
    return {database,exit:result.exit,migrations:await Promise.all(migrations.map(async p=>({path:p,sha256:sha256(await readFile(p))})))};
  }
  async grantSql(database='finite') {
    requireValue(/^finite(?:_corrected_[a-z0-9_]+)?$/.test(database),'owned database');
    const runtime=['claim_owner(TEXT,BIGINT,TEXT)','begin_attempt(TEXT,BIGINT,JSONB)','authorize_send(TEXT,BIGINT,TEXT)','ack(TEXT,TEXT,BIGINT,BIGINT,BIGINT)','unknown(TEXT,TEXT,BIGINT)','stop(TEXT,BIGINT)','snapshot(TEXT)','receipt(TEXT,TEXT)'];
    const registrar=['register(TEXT,JSONB,BYTEA,TEXT)','stages(TEXT,BIGINT,TEXT,TEXT,JSONB)','release(TEXT,BIGINT,TEXT,TEXT,JSONB)','snapshot(TEXT)'];
    const sql=`GRANT USAGE ON SCHEMA boundary TO calcify_finite_runtime,calcify_finite_registrar; GRANT EXECUTE ON FUNCTION ${runtime.map(x=>'boundary.calcify_finite_'+x).join(',')} TO calcify_finite_runtime; GRANT EXECUTE ON FUNCTION ${registrar.map(x=>'boundary.calcify_finite_'+x).join(',')} TO calcify_finite_registrar;`;
    return this.command(['exec','-i',this.ids.postgres,'psql','-X','--single-transaction','-U','fixture_installer','-d',database,'-v','ON_ERROR_STOP=1'],{input:sql,record:'pg-scoped-function-grants'});
  }
  async verifyArtifacts(){
    requireValue(this.lease.scope==='full','full artifact lease');await this.registerController();const a=this.lease.artifacts;
    await pathGuard(a.sourceManifest,'file');const raw=await readFile(a.sourceManifest),m=JSON.parse(raw);
    requireValue(m.schema==='calcify-finite-o2-artifact-manifest-v1'&&m.sourceRevision===this.lease.sourceRevision&&m.sources?.length>=10&&m.buildReceipts?.length>=2,'source/build manifest');
    const seen=new Set();for(const f of m.sources){requireValue(typeof f.path==='string'&&!f.path.startsWith('/')&&!f.path.split('/').includes('..')&&!seen.has(f.path),'source path');seen.add(f.path);const p=join(ROOT,f.path);await pathGuard(p,'file');const b=await readFile(p);requireValue(b.length===f.bytes&&sha256(b)===f.sha256,'dirty source drift');}
    for(const p of ['scripts/dev/calcify-financial/finite-o2-proof.mjs','services/matching-engine/cmd/calcify-finite-probe/main.go','scripts/dev/db/migrations/boundary/0013_calcify_finite_ingress.sql','services/platform-runtime/src/main/kotlin/com/reef/platform/calcify/FiniteRuntime.kt'])requireValue(seen.has(p),'missing required source');
    for(const [key,path] of [['matcher',a.matcherBinary],['probe',a.probeBinary]]){requireValue(m[key]?.path===path,'binary manifest path');const b=await hashArtifactFile(path);requireValue(b.bytes===m[key].bytes&&b.sha256===m[key].sha256,'fresh binary drift');}
    const dist=await freezeArtifacts(a.distribution);requireValue(m.distribution?.root===dist.root&&JSON.stringify(m.distribution.files)===JSON.stringify(dist.files)&&m.distribution.manifestSha256===dist.manifestSha256,'distribution drift');
    requireValue(dist.files.some(f=>f.path.startsWith('lib/')&&f.path.endsWith('.jar'))&&dist.files.some(f=>f.path==='bin/platform-runtime'),'distribution missing');
    if(a.faultProbeDistribution){const f=await freezeArtifacts(a.faultProbeDistribution);requireValue(m.faultProbe?.root===f.root&&m.faultProbe?.manifestSha256===f.manifestSha256&&JSON.stringify(m.faultProbe.files)===JSON.stringify(f.files),'fault classpath drift');this.faultManifest=f;}
    this.artifactManifestSha256=sha256(raw);for(const f of m.buildReceipts){requireValue((await hashArtifactFile(f.path)).sha256===f.sha256,'build receipt drift');}
    return this.writeReceipt('verified-artifacts',{schema:m.schema,sourceRevision:m.sourceRevision,sourceManifestSha256:sha256(raw),sources:m.sources,matcher:m.matcher,probe:m.probe,distribution:dist,buildReceipts:m.buildReceipts});
  }
  async appLifecycleWitness(service,{running,timeoutMs=10000}={}){
    requireValue(['runtime','matcher'].includes(service)&&typeof running==='boolean','app lifecycle target');requireValue(Number.isSafeInteger(timeoutMs)&&timeoutMs>0&&timeoutMs<=10000,'retirement wait cap');
    const id=this.ids[service];requireValue(/^[0-9a-f]{64}$/.test(id),'retirement runtime ID');const image=JSON.parse((await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'lifecycle-image-'+service})).stdout),old=await this.inspectOwned(service,image);requireValue(old.Id===id&&old.State.Running===running&&old.State.Status===(running?'running':'exited')&&(running?Number.isSafeInteger(old.State.Pid)&&old.State.Pid>0:old.State.Pid===0)&&old.HostConfig.RestartPolicy.Name==='no','retirement old runtime exit');
    const requestedAt=Date.now(),deadline=requestedAt+timeoutMs;const proof=await pathGuard(this.lease.proofRoot);requireValue(proof.uid===process.getuid()&&(proof.mode&0o777)===0o700,'retirement private proof root');const privateRead=async name=>{const path=join(this.lease.proofRoot,name),st=await pathGuard(path,'file');requireValue(st.uid===process.getuid()&&(st.mode&0o777)===0o600&&st.size>0&&st.size<=262144,'retirement private monitor file');const raw=await readFile(path);requireValue(raw.length===st.size,'retirement monitor file changed');return {path,raw,value:JSON.parse(raw),sha256:sha256(raw)};};
    const owner=await privateRead('RESOURCE_MONITOR_OWNER.json'),armed=await privateRead('RESOURCE_MONITOR_ARMED.json');
    while(Date.now()<deadline){for(const name of ['RESOURCE_ABORT.json','RESOURCE_MONITOR_STOP','RESOURCE_MONITOR_STOPPED.json'])await lstat(join(this.lease.proofRoot,name)).then(()=>{throw Error('retirement monitor aborted or stopped');},e=>{if(e.code!=='ENOENT')throw e;});
      const names=(await readdir(this.lease.proofRoot)).filter(n=>/^RESOURCE_SAMPLE_[0-9]{6}\.json$/.test(n)).sort();if(names.length){const sample=await privateRead(names.at(-1));if(Date.parse(sample.value.startedAt)>=requestedAt){validateAppLifecycleSample(sample.value,owner.value,armed.value,this.lease,service,running,id,image,requestedAt);const ps=await this.run('/bin/ps',['-o','pid=,lstart=','-p',String(owner.value.pid)],{timeoutMs:Math.max(1,deadline-Date.now()),maxBytes:4096,secrets:this.secrets,env:this.env});requireValue(ps.exit.code===0&&!ps.exit.signal,'retirement monitor process');const match=ps.stdout.trim().match(/^(\d+)\s+(.+)$/);requireValue(match&&Number(match[1])===owner.value.pid&&Math.abs(Date.parse(match[2])-Date.parse(owner.value.processStartedAt))<=3000&&Date.now()<=deadline,'retirement monitor PID reused');for(const name of ['RESOURCE_ABORT.json','RESOURCE_MONITOR_STOP','RESOURCE_MONITOR_STOPPED.json'])await lstat(join(this.lease.proofRoot,name)).then(()=>{throw Error('retirement monitor aborted or stopped');},e=>{if(e.code!=='ENOENT')throw e;});return this.writeReceipt(service==='runtime'&&!running?'independent-runtime-retirement-witness':'independent-'+service+'-'+(running?'running':'exited')+'-witness',{service,running,appId:id,...(service==='runtime'?{runtimeId:id}:{}),requestedAt:new Date(requestedAt).toISOString(),sample:{path:sample.path,sha256:sample.sha256,bytes:sample.raw.length,body:sample.value},owner:{path:owner.path,sha256:owner.sha256},armed:{path:armed.path,sha256:armed.sha256},monitorProcess:ps,scope:'Existing complete independent monitor sample observes exact app lifecycle state before next controlled mutation; no supervisor-authored acknowledgement'});}}
      await new Promise(r=>setTimeout(r,100));
    }throw Error('retirement independent monitor timeout');
  }
  async runtimeRetirementWitness(options={}){return this.appLifecycleWitness('runtime',{...options,running:false});}
  async startServices(...services){
    const options=typeof services.at(-1)==='object'?services.pop():{},apps=services.filter(s=>['runtime','matcher'].includes(s)),policy=options.appStartup??'running';requireValue(Object.keys(options).every(k=>k==='appStartup')&&['running','expected-refusal'].includes(policy)&&services.length>0&&new Set(services).size===services.length&&services.every(s=>['runtime','matcher','broker','postgres'].includes(s)),'app startup policy');
    if(policy==='expected-refusal')requireValue(services.length===1&&(['topic-identity','command-history'].includes(this.lease.adverse)||this.lease.adverse==='scope'&&apps[0]==='matcher')&&apps.length===1,'dedicated refusal startup policy');
    requireValue(!apps.length||apps.length===services.length,'backend/app startup cannot share lifecycle operation');if(apps.length>1){for(const service of apps)await this.startServices(service);return;}
    if(apps.length&&this.ids[apps[0]]){if(apps[0]==='runtime')await this.runtimeRetirementWitness();else await this.appLifecycleWitness(apps[0],{running:false});}
    await this.command(this.compose('up','--detach','--no-build','--pull','never',...services),{timeoutMs:60000,record:'start-'+services.join('-')});
    for(const service of services){const result=await this.command(this.compose('ps','--all','--quiet',service),{record:'id-'+service});this.ids[service]=result.stdout.trim();requireValue(/^[0-9a-f]{64}$/.test(this.ids[service]),'actual compose ID');const imageResult=await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'image-'+service});await this.inspectOwned(service,JSON.parse(imageResult.stdout));}
    await this.writeReceipt('owned-resources',{schema:'calcify-finite-o2-owned-v1',project:this.lease.project,ids:this.ids,network:this.lease.project+'-private',volumes:[this.lease.project+'-broker-data',this.lease.project+'-postgres-data']});
    if(apps.length&&policy==='running')await this.appLifecycleWitness(apps[0],{running:true});
    // Dedicated refusal callers retain real logs/state then require running/exited witness before next mutation.
  }
  async waitFor(name,observe,accept,timeoutMs=30000){
    const deadline=Date.now()+timeoutMs;let observation;
    while(Date.now()<deadline){try{observation=await observe();if(accept(observation))return observation;}catch{}await new Promise(r=>setTimeout(r,500));}
    await this.writeReceipt('wait-expired-'+name,{name,timeoutMs,observation:observation??null});throw Error('fixture readiness deadline');
  }
  async sql(role,sql,{installer=false,record='sql-'+role}={}){
    const argv=['exec','-i'];if(!installer)argv.push('--env','PGPASSFILE=/run/secrets/'+role+'.pgpass');argv.push(this.ids.postgres,'psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-U',installer?'fixture_installer':'calcify_finite_'+role,'-d','finite');if(!installer)argv.push('-h','postgres');
    return (await this.command(argv,{input:sql,record})).stdout.trim();
  }
  async snapshot(){return JSON.parse(await this.sql('runtime',`SELECT boundary.calcify_finite_snapshot(${quoteSQL(this.digest)});`));}
  async cuts(){
    const o=this.opening,auth=await this.auth('operator'),command=await this.probe({mode:'group-offset',auth,topic:o.topics[0].name,group:o.commandConsumerGroup}),capture=await this.probe({mode:'group-offset',auth,topic:o.topics[1].name,group:o.captureApplicationId});
    requireValue(decimal.test(command.offset)&&decimal.test(capture.offset),'actual seeded/committed cuts');const ranges=[];
    for(const t of o.topics){const range=await this.probe({mode:'range',auth,topic:t.name});requireValue(range.beginningOffset==='0'&&BigInt(range.readCommittedEndOffset)<=BigInt(range.endOffset),'retained ranges');ranges.push({role:t.role,partition:0,beginningOffset:range.beginningOffset,endOffset:range.endOffset,readCommittedEndOffset:range.readCommittedEndOffset});}
    return {commandGroup:{groupId:o.commandConsumerGroup,nextOffset:command.offset},captureSourceGroup:{groupId:o.captureApplicationId,nextOffset:capture.offset},ranges};
  }
  async provisionBroker(){
    const operator=await this.auth('operator');const roles=['operator','ingress','matcher','capture','observer','denied'];
    await this.waitFor('private-admin',()=>this.probe({mode:'admin-read',auth:operator}),r=>r.httpStatus===200);await this.probe({mode:'users',auth:operator,users:[{username:operator.username,password:operator.password}]});
    for(const role of roles.slice(1)){const auth=await this.auth(role);await this.probe({mode:'users',auth:operator,users:[auth]});}
    const enabled=await this.probe({mode:'admin-auth',auth:operator});requireValue(enabled.httpStatus===200,'admin enforcement');
    const actual=await this.waitFor('broker-auth',()=>this.probe({mode:'admin-read',auth:operator}),r=>r.httpStatus===200&&r.config.admin_api_require_auth===true&&r.config.kafka_enable_authorization===true);
    validateBrokerProfile(actual.config);await this.writeReceipt('actual-broker-finite-geometry',{config:actual.config,scope:'Fresh RF1 finite physical profile; internal RF unchanged; no throughput claim'});
    const p=this.lease.prefix,topics=[p+'-commands',p+'-source',p+'-capture',p+'-ownership',p+'-capture-finite-lifecycle-changelog'];
    const retained=finiteTopicConfig('COMMAND');
    await this.probe({mode:'create-topics',auth:operator,topics:topics.slice(0,4),config:retained});await this.probe({mode:'create-topics',auth:operator,topics:[topics[4]],config:finiteTopicConfig('CAPTURE_CHANGELOG')});
    await this.probe({mode:'create-topics',auth:operator,topics:[p+'-canary'],config:finiteTopicConfig('canary')});await this.probe({mode:'create-topics',auth:operator,topics:[p+'-state-fit'],config:finiteTopicConfig('state-fit')});
    const grants=[];const grant=(role,kind,name,operation,pattern)=>grants.push({principal:'User:'+p+'-'+role,kind,name,operation,...(pattern?{pattern}:{})});
    for(const role of ['ingress','capture','matcher','observer']){for(const topic of topics){grant(role,'topic',topic,'describe');grant(role,'topic',topic,'describe-configs');}for(const group of [p+'-engine-p00',p+'-capture'])grant(role,'group',group,'describe');}
    for(const role of roles.slice(1)){grant(role,'topic',p+'-canary','write');grant(role,'topic',p+'-canary','describe');}
    grant('ingress','topic',topics[0],'write');grant('ingress','topic',topics[0],'read');
    for(const topic of [topics[0],topics[3]])grant('matcher','topic',topic,'read');for(const topic of [topics[1],topics[3]])grant('matcher','topic',topic,'write');
    for(const group of [p+'-engine-p00',p+'-ownership-group']){grant('matcher','group',group,'read');grant('matcher','group',group,'describe');}
    for(const op of ['write','describe'])grant('matcher','transactional-id',p+'-engine-p00-venue-events-v1',op);
    for(const topic of [topics[1],topics[2],topics[4]])grant('capture','topic',topic,'read');for(const topic of [topics[2],topics[4]])grant('capture','topic',topic,'write');grant('capture','group',p+'-capture','read');
    for(const op of ['write','describe'])grant('capture','transactional-id',p+'-capture-',op,'prefixed');
    for(const topic of topics)grant('observer','topic',topic,'read');
    // Each batch bounded; every grant literal except exact application transaction prefix.
    for(let i=0;i<grants.length;i+=40)await this.probe({mode:'grant',auth:operator,grants:grants.slice(i,i+40)});await this.probe({mode:'acls',auth:operator});
    for(const role of ['ingress','matcher','capture','observer','denied'])await this.probe({mode:'publish',auth:await this.auth(role),topic:p+'-canary',key:'positive-'+role,value:bytes('same-broker-positive-'+role).toString('base64')});
    for(const [role,topic] of [['ingress',topics[1]],['ingress',topics[2]],['ingress',topics[4]],['matcher',topics[0]],['matcher',topics[2]],['capture',topics[0]],['denied',topics[0]],['observer',topics[0]]])await this.probe({mode:'publish',auth:await this.auth(role),topic,key:'must-deny',value:bytes('unauthorized').toString('base64')},'denied');
    await this.probe({mode:'history',auth:await this.auth('matcher'),topic:topics[1]},'denied');await this.probe({mode:'history',auth:await this.auth('capture'),topic:topics[0]},'denied');
    await this.probe({mode:'create-topics',auth:operator,topics:[p+'-create-positive'],config:retained});
    for(const role of ['ingress','matcher','capture']){await this.probe({mode:'create-topics',auth:await this.auth(role),topics:[p+'-create-denied-'+role],config:retained},'denied');await this.probe({mode:'alter-canary',auth:await this.auth(role),topic:p+'-canary'},'denied');await this.probe({mode:'delete-canary',auth:await this.auth(role),topic:p+'-canary'},'denied');const admin=await this.probe({mode:'admin-denied',auth:await this.auth(role)});requireValue(admin.httpStatus===401||admin.httpStatus===403,'admin actual denial');}
    const finalAdmin=await this.probe({mode:'admin-read',auth:operator});requireValue(finalAdmin.config.write_caching_default==='false','denied mutation unchanged');
    await this.probe({mode:'authenticate',auth:operator,topics:[topics[0]]});await this.probe({mode:'authenticate',auth:{...operator,password:'wrong-fixture-password'},topics:[topics[0]]},'authentication');
    // Empty PLAIN credential authentication is distinct from canonical READ/WRITE denial.
    const empty=await this.probe({mode:'sasl-empty',auth:{username:'',password:''}},'authentication');
    await this.writeReceipt('actual-empty-credential-authentication-refusal',{empty,scope:'Empty PLAIN credential authentication; canonical READ/WRITE denial not claimed'});
    await this.probe({mode:'seed-group',auth:operator,topic:topics[0],group:p+'-engine-p00',offset:0});await this.probe({mode:'seed-group',auth:operator,topic:topics[1],group:p+'-capture',offset:0});
    const metadata=await this.probe({mode:'metadata',auth:operator,topics}),canaries=await this.probe({mode:'metadata',auth:operator,topics:[p+'-canary',p+'-state-fit',p+'-create-positive']});
    for(const row of [...metadata.topics,...canaries.topics]){const index=topics.indexOf(row.name),role=index>=0?['COMMAND','SOURCE','CAPTURE','OWNERSHIP','CAPTURE_CHANGELOG'][index]:row.name.slice(p.length+1);for(const [key,value] of Object.entries(finiteTopicConfig(role)))requireValue(row.config[key]===value,'actual finite topic profile '+role+' '+key);}
    await this.writeReceipt('actual-finite-topic-physical-profile',{metadata,canaries,scope:'Actual DescribeConfig exact per-role segments and retained policy; no throughput claim'});return metadata;
  }
  async registerBinding(metadata){
    const template=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8'));const reference={instrumentId:template.instrumentId,baseAssetId:template.baseAssetId,quoteCurrency:template.quoteCurrency};
    const referenceJson=JSON.stringify(reference);const row=JSON.parse(await this.sql('installer',`CREATE SCHEMA fixture AUTHORIZATION calcify_finite_owner; CREATE TABLE fixture.reference_assets(instrument_id TEXT PRIMARY KEY,base_asset_id TEXT NOT NULL,quote_currency TEXT NOT NULL); INSERT INTO fixture.reference_assets VALUES (${quoteSQL(reference.instrumentId)},${quoteSQL(reference.baseAssetId)},${quoteSQL(reference.quoteCurrency)}); SELECT json_build_object('instrumentId',instrument_id,'baseAssetId',base_asset_id,'quoteCurrency',quote_currency) FROM fixture.reference_assets;`,{installer:true,record:'actual-reference-seed'}));
    requireValue(JSON.stringify(row)===referenceJson,'reference seed readback');await this.writeReceipt('reference-authority',{row,sha256:sha256(JSON.stringify(row)),authority:'Isolated fixture seed only'});
    const {opening,canonical,digest}=instantiateOpening(template,this.lease.prefix,metadata,row);this.opening=opening;this.digest=digest;
    const registryBefore=await this.sql('installer',"SELECT json_build_object('source',source_topic,'topicId',source_topic_id,'receipts',(SELECT count(*) FROM runtime.calcify_commitment_receipts),'identities',(SELECT count(*) FROM runtime.calcify_topic_identities)) FROM runtime.calcify_source_generations WHERE source_generation=1;",{installer:true,record:'fresh-generation-before-rebind'});
    const before=JSON.parse(registryBefore);requireValue(before.source==='REEF_VENUE_EVENTS'&&before.topicId===null&&before.receipts===0&&before.identities===0,'fresh unused registry only');
    await this.sql('installer',`SET ROLE calcify_finite_owner; UPDATE runtime.calcify_source_generations SET source_topic=${quoteSQL(opening.topics[1].name)},source_topic_id=${quoteSQL(opening.topics[1].topicId)} WHERE source_generation=1 AND source_topic_id IS NULL; ${opening.topics.filter(t=>t.role!=='SOURCE').map(t=>`INSERT INTO runtime.calcify_topic_identities(source_generation,role,topic_name,topic_id) VALUES(1,${quoteSQL(t.role)},${quoteSQL(t.name)},${quoteSQL(t.topicId)});`).join(' ')} GRANT USAGE ON SCHEMA runtime TO calcify_finite_runtime; GRANT SELECT ON runtime.calcify_source_generations,runtime.calcify_topic_identities TO calcify_finite_runtime; RESET ROLE; SELECT json_build_object('generation',(SELECT row_to_json(g) FROM runtime.calcify_source_generations g WHERE source_generation=1),'identities',(SELECT json_agg(t ORDER BY role) FROM runtime.calcify_topic_identities t WHERE source_generation=1),'roleCheck',(SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='runtime.calcify_topic_identities'::regclass AND conname='calcify_topic_identities_role_check'));`,{installer:true,record:'actual-runtime-registry-rebind-readback'});
    const registered=await this.sql('registrar',`SELECT boundary.calcify_finite_register(${quoteSQL(digest)},${quoteSQL(JSON.stringify(opening))}::jsonb,decode(${quoteSQL(canonical.toString('hex'))},'hex'),${quoteSQL(this.lease.prefix+'-api')});`);requireValue(registered==='t','actual binding registration');
    return this.writeReceipt('registered-opening',{schema:'calcify-finite-o2-opening-proof-v1',opening,digest,canonicalHex:canonical.toString('hex'),metadata,reference:row});
  }
  async guardAdverse(q){
    const role=validateAdverse(q,this.lease,this.opening),s=await this.snapshot();requireValue(s.run.admissionState==='STOPPED'&&s.run.unresolvedPublications==='0','adverse stopped/resolved history');
    requireValue(role==='SOURCE'?s.run.possiblePublications==='0':s.run.possiblePublications==='1'&&s.journal.length===1&&s.journal[0].state==='PUBLISHED'&&s.journal[0].offset==='0','fresh bounded adverse history');
    for(const service of ['runtime','matcher']){if(!this.ids[service]){const absent=await this.command(['ps','--all','--quiet','--filter','label=com.docker.compose.project='+this.lease.project,'--filter','label=com.docker.compose.service='+service],{record:'adverse-never-started-'+service});requireValue(!absent.stdout.trim(),'unrecorded writer exists');continue;}const image=JSON.parse((await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'adverse-image-'+service})).stdout);const owned=await this.inspectOwned(service,image);requireValue(owned.State.Status==='exited'&&!owned.State.Running,'adverse writer not contained');}
    const competing=await this.command(['ps','--quiet','--filter','label=com.docker.compose.project='+this.lease.project,'--filter','label=reef.test=calcify-finite-o2'],{record:'adverse-competing-instance-inventory'});const active=competing.stdout.trim().split('\n').filter(Boolean);requireValue(active.every(id=>id===this.ids.broker.slice(0,id.length)||id===this.ids.postgres.slice(0,id.length)),'competing old writer');
    const metadata=await this.probe({mode:'metadata',auth:await this.auth('operator'),topics:[q.topic]});const actual=metadata.topics[0];requireValue(actual.uuid===q.topicId&&actual.lowOffset==='0'&&(role==='SOURCE'?actual.highWatermark==='0':actual.highWatermark==='1'),'actual adverse pre-cut');await this.writeReceipt('adverse-guard',{role,oldUuid:q.topicId,snapshot:s,metadata});
  }
  async adverseMutation(mode){const role=mode==='recreate-source'?'SOURCE':'COMMAND',topic=this.opening.topics.find(t=>t.role===role);return this.probe({mode,adverse:this.lease.adverse,auth:await this.auth('operator'),topic:topic.name,topicId:topic.topicId,...(role==='COMMAND'?{offset:1}:{})});}
  async matcherEnv(){
    const o=this.opening,p=this.lease.prefix;const entries={MATCHING_ENGINE_ADDR:':8081',MATCHING_ENGINE_ENABLE_GRPC:'0',MATCHING_ENGINE_CALCIFY_SOURCE_PROFILE:JSON.stringify(o.profile),MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT:'0',MATCHING_ENGINE_INSTRUMENT_QUOTES:JSON.stringify({[o.instrumentId]:o.quoteCurrency}),MATCHING_ENGINE_DIRECT_STREAM_ENABLED:'true',STREAM_ACK_LOG_PROVIDER:'redpanda',STREAM_ACK_PARTITION_COUNT:'1',MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS:'0',MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE:'1',MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE:'none',STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS:'broker:9092',STREAM_ACK_COMMAND_STREAM:o.topics[0].name,STREAM_ACK_SUBJECT_PREFIX:o.commandSubjectPrefix,MATCHING_ENGINE_EVENT_STREAM:o.topics[1].name,MATCHING_ENGINE_EVENT_SUBJECT_PREFIX:o.sourceSubjectPrefix,MATCHING_ENGINE_OWNERSHIP_TOPIC:o.topics[3].name,MATCHING_ENGINE_OWNERSHIP_GROUP:o.ownershipGroup,MATCHING_ENGINE_SHARD_ID:o.matcherShardId,MATCHING_ENGINE_DIRECT_STREAM_DURABLE_PREFIX:p+'-engine',MATCHING_ENGINE_CALCIFY_LIFECYCLE_ENABLED:'true',MATCHING_ENGINE_CALCIFY_FINITE_BINDING_DIGEST:this.digest,MATCHING_ENGINE_CALCIFY_BINDING_ENDPOINT:'http://runtime:8080',MATCHING_ENGINE_CALCIFY_SERVICE_TOKEN_FILE:'/run/secrets/service-token',MATCHING_ENGINE_CALCIFY_KAFKA_USERNAME:p+'-matcher',MATCHING_ENGINE_CALCIFY_KAFKA_PASSWORD_FILE:'/run/secrets/kafka-password',MATCHING_ENGINE_CALCIFY_KAFKA_TLS_ENABLED:'false'};
    const raw=Object.entries(entries).map(([k,v])=>k+'='+v).join('\n')+'\n';noSecretSpill(raw,this.secrets);await writeFile(this.env.FINITE_MATCHER_ENV_FILE,raw,{flag:'wx'});await this.writeReceipt('matcher-configuration',{entries,sha256:sha256(raw)});
  }
  async http(host,path,{body,bearer,headers,method='GET'}={}){
    const result=await this.probe({mode:'http',host,path,method,bearer:bearer??await this.secret('service-token'),headers:headers??{},...(body?{body}:{})});
    const raw=Buffer.from(result.bodyBase64,'base64');requireValue(raw.length===result.bytes&&sha256(raw)===result.bodySha256,'HTTP raw receipt');return {...result,raw,body:JSON.parse(raw)};
  }
  async readiness(){
    const nonce=randomBytes(16).toString('hex');const engine=await this.http('matcher:8081','/internal/calcify/finite-readiness?nonce='+nonce),capture=await this.http('runtime:8080','/internal/calcify/finite-capture-readiness?nonce='+nonce);
    requireValue(engine.httpStatus===200&&capture.httpStatus===200&&engine.bytes<=8192&&capture.bytes<=8192,'actual readiness HTTP');const e=engine.body,c=capture.body,s=await this.snapshot(),cuts=await this.cuts();
    requireValue(e.schema==='calcify-finite-engine-ready-v1'&&c.schema==='calcify-finite-capture-ready-v1'&&e.nonce===nonce&&c.nonce===nonce&&e.bindingDigest===this.digest&&c.bindingDigest===this.digest,'fresh nonce authority');
    requireValue(e.ownerEpoch===s.run.ownerEpoch&&c.ownerEpoch===s.run.ownerEpoch&&e.activationEpoch===s.run.activationEpoch&&c.activationEpoch===s.run.activationEpoch&&s.run.admissionState==='STOPPED'&&s.run.unresolvedPublications==='0','stable stopped owner');
    requireValue(e.commandGroup===this.opening.commandConsumerGroup&&e.matcherShardId===this.opening.matcherShardId&&e.transactionalId===this.opening.commandConsumerGroup+'-venue-events-v1'&&e.commandNextOffset===cuts.commandGroup.nextOffset&&e.sourceReadCommittedEndOffset===cuts.ranges[1].readCommittedEndOffset,'engine actual cuts');
    requireValue(c.applicationId===this.opening.captureApplicationId&&c.sourceGroupNextOffset===cuts.captureSourceGroup.nextOffset&&decimal.test(c.representedNextOffset)&&BigInt(c.representedNextOffset)<=BigInt(cuts.captureSourceGroup.nextOffset)&&c.captureReadCommittedEndOffset===cuts.ranges[2].readCommittedEndOffset&&c.fault===''&&c.retainedSuffix==='0'&&hashPattern.test(c.stateDigest)&&hashPattern.test(e.snapshotChecksum),'capture actual closure');
    const sourceHistory=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[1].name}),captureHistory=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[2].name});const represented=sourceHistory.records.length?String(BigInt(sourceHistory.records.at(-1).offset)+1n):'0';requireValue(c.representedNextOffset===represented&&c.completedRecords===String(sourceHistory.records.length)&&captureHistory.records.length===sourceHistory.records.length,'actual committed data membership');
    const second=await this.cuts();requireValue(JSON.stringify(second)===JSON.stringify(cuts),'cut moved during readiness observation');
    await this.writeReceipt('fresh-readiness',{nonce,engine:{...e,rawSha256:engine.bodySha256},capture:{...c,rawSha256:capture.bodySha256},snapshot:s,cuts,observation:'Separate fresh observations; no Kafka/SQL atomic snapshot claim'});
    return {engineHash:engine.bodySha256,captureHash:capture.bodySha256,epoch:s.run.ownerEpoch,cuts};
  }
  async saveContext(){return this.writeReceipt('context',{schema:'calcify-finite-o2-context-v1',leaseProject:this.lease.project,ids:this.ids,opening:this.opening,digest:this.digest,artifactManifestSha256:this.artifactManifestSha256,fault:this.runtimeFaultActive?this.lease.fault:null});}
  preparedIdentity(){return {project:this.lease.project,prefix:this.lease.prefix,proofRoot:this.lease.proofRoot,secretRoot:this.lease.secretRoot,stateRoot:this.lease.stateRoot,sourceManifestSha256:this.artifactManifestSha256,artifacts:this.lease.artifacts,images:this.lease.images};}
  async prepare(){
    requireValue(this.lease.scope==='full','full preparation lease');const path=join(this.lease.proofRoot,'PREPARED.json');await lstat(path).then(()=>{throw Error('case already prepared');},e=>{if(e.code!=='ENOENT')throw e;});await this.verifyArtifacts();await this.generateSecrets();await this.setupEnv();await mkdir(join(this.lease.stateRoot,'capture'),{mode:0o700});await this.registerController();const identity=this.preparedIdentity(),marker={schema:'calcify-finite-o2-prepared-v1',preparedAt:new Date().toISOString(),identity,identityHash:sha256(JSON.stringify(identity))};await writeFile(path,JSON.stringify(marker)+'\n',{mode:0o600,flag:'wx'});return this.writeReceipt('prepared-case',{...marker,markerPath:path,scope:'Private preparation only; no Compose, broker, SQL, admission or history acquired'});
  }
  async consumePreparation(){
    const path=join(this.lease.proofRoot,'PREPARED.json');let st;try{st=await pathGuard(path,'file');}catch(e){if(e.code==='ENOENT')return false;throw e;}requireValue((st.mode&0o777)===0o600&&st.size<=4096,'private bounded prepared marker');const raw=await readFile(path),marker=JSON.parse(raw),identity=this.preparedIdentity();requireValue(marker.schema==='calcify-finite-o2-prepared-v1'&&marker.identityHash===sha256(JSON.stringify(identity))&&JSON.stringify(marker.identity)===JSON.stringify(identity),'prepared case identity drift');
    for(const p of [this.lease.secretRoot,this.lease.stateRoot,join(this.lease.stateRoot,'capture')])requireValue(((await pathGuard(p)).mode&0o777)===0o700,'prepared private directory');await this.loadSecrets();await writeFile(join(this.lease.proofRoot,'PREPARED_CONSUMED.json'),JSON.stringify({schema:'calcify-finite-o2-prepared-consumed-v1',project:this.lease.project,markerSha256:sha256(raw),consumedAt:new Date().toISOString()})+'\n',{mode:0o600,flag:'wx'});await this.setupEnv();return true;
  }
  async postgresProfile(){
    await this.waitFor('postgres',()=>this.command(['exec',this.ids.postgres,'pg_isready','-h','127.0.0.1','-p','5432','-U','fixture_installer','-d','finite'],{record:'pg-ready'}),r=>r.exit.code===0,30000);
    const keys=['wal_segment_size','min_wal_size','max_wal_size','fsync','synchronous_commit','full_page_writes'],values=(await this.sql('installer',keys.map(k=>'SHOW '+k+';').join(' '),{installer:true,record:'actual-pg-durability-show'})).split('\n');requireValue(values.length===keys.length,'complete PG SHOW profile');const pgProfile=validatePgProfile(Object.fromEntries(keys.map((k,i)=>[k,values[i]])));await this.writeReceipt('actual-finite-pg-profile',{profile:pgProfile,scope:'Fresh WAL1/min8/max32 physical profile; fsync/synchronous_commit/full_page_writes ON. Prior112 SQL tests used WAL16/min80/max256; same-profile throughput not claimed'});
    return pgProfile;
  }
  async bootstrap({backendOnly=false,preMatcher=false}={}){
    requireValue(this.lease.scope==='full','full lease required');await this.verifyArtifacts();if(!await this.consumePreparation()){await this.generateSecrets();await this.setupEnv();await mkdir(join(this.lease.stateRoot,'capture'),{mode:0o700});}await writeFile(this.env.FINITE_MATCHER_ENV_FILE,'# reserved until real binding\n',{flag:'wx'});
    await this.writeReceipt('full-resource-intent',{project:this.lease.project,containers:['broker','postgres','runtime','matcher'].map(s=>({service:s,name:this.lease.project+'-'+s,image:this.lease.images[s],memoryMiB:{broker:2048,postgres:512,runtime:1024,matcher:128}[s]})),network:this.lease.project+'-private',subnet:this.lease.networkSubnet,volumes:[this.lease.project+'-broker-data',this.lease.project+'-postgres-data'],artifactPaths:this.lease.artifacts});await this.startServices('broker','postgres');await this.postgresProfile();

    await this.initializeSql(join(ROOT,'scripts/dev/db/migrations/boundary/0013_calcify_finite_ingress.sql'));await this.grantSql();const metadata=await this.provisionBroker();await this.registerBinding(metadata);if(this.lease.fault)await this.faultLaunch(this.lease.fault);
    // Replace only unstarted generated placeholder; no authoritative/history reset.
    await writeFile(this.env.FINITE_MATCHER_ENV_FILE,'');const old=this.env.FINITE_MATCHER_ENV_FILE;this.env.FINITE_MATCHER_ENV_FILE=old+'.registered';await this.matcherEnv();this.env.FINITE_BINDING_DIGEST=this.digest;
    if(backendOnly)return this.saveContext();
    await this.startServices('runtime');await this.waitFor('capture-genesis',()=>this.http('runtime:8080','/internal/calcify/finite-capture-readiness?nonce='+randomBytes(16).toString('hex')),r=>r.httpStatus===200,60000);
    if(preMatcher)return this.saveContext();
    await this.startServices('matcher');await this.waitFor('engine',()=>this.readiness(),r=>Boolean(r.engineHash),60000);await this.sample();return this.saveContext();
  }
  async restoreContext(path){const c=JSON.parse(await readFile(path,'utf8'));requireValue(c.schema==='calcify-finite-o2-context-v1'&&c.leaseProject===this.lease.project&&c.digest===sha256(bindingBytes(c.opening)),'context identity');this.ids=c.ids;this.opening=c.opening;this.digest=c.digest;await this.loadSecrets();await this.setupEnv();this.env.FINITE_BINDING_DIGEST=this.digest;this.env.FINITE_MATCHER_ENV_FILE+='.registered';await this.verifyArtifacts();requireValue(c.artifactManifestSha256===this.artifactManifestSha256,'source changed since live context');if(c.fault)await this.faultLaunch(c.fault,{existing:true});for(const service of Object.keys(this.ids)){const r=await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'context-image-'+service});await this.inspectOwned(service,JSON.parse(r.stdout));}return c;}
  async release(){const r=await this.readiness();const result=await this.sql('registrar',releaseSQL(this.digest,r.epoch,r.engineHash,r.captureHash,r.cuts),{record:'operator-stages-release'});requireValue(result==='t\nt','actual registrar stages/release');const s=await this.snapshot();requireValue(s.run.admissionState==='ACTIVE','release readback');const replay=await this.sql('registrar',releaseSQL(this.digest,r.epoch,r.engineHash,r.captureHash,r.cuts),{record:'actual-used-release-replay'});requireValue(replay==='f\nf','used receipt cannot rerelease');return this.writeReceipt('released',{snapshot:s,readiness:r,replay});}
  async proveFlow(){
    const template=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),clients=JSON.parse(await this.secret('client-token')).clients,commands=fixtureCommands(template,this.lease.prefix);
    for(const command of commands){const client=clients.find(c=>c.clientId===command.headers['X-Client-Id']);const result=await this.http('runtime:8080',command.path,{method:'POST',body:command.body,headers:command.headers,bearer:client.token});requireValue(result.httpStatus===202,'durable canonical acceptance');await this.sample();}
    const snap=await this.snapshot();requireValue(await this.sql('runtime',`SELECT boundary.calcify_finite_stop(${quoteSQL(this.digest)},${snap.run.ownerEpoch});`)==='t','stop admissions');
    await this.waitFor('drained-flow',()=>this.readiness(),r=>r.cuts.commandGroup.nextOffset==='12'&&r.cuts.captureSourceGroup.nextOffset===r.cuts.ranges[1].readCommittedEndOffset,60000);
    await this.writeReceipt('actual-drain-cost-cut',{observedAt:new Date().toISOString(),cuts:await this.cuts(),scope:'Finite12-command externally observed complete command/source/capture cuts; no throughput SLO or stage CPU claim'});
    const histories=[];for(const t of this.opening.topics.slice(0,3))histories.push(await this.probe({mode:'history',auth:await this.auth('observer'),topic:t.name}));
    const s=await this.snapshot();const proof=validateFlow(template,this.opening,this.digest,...histories,s.journal);await this.writeReceipt('actual-flow-proof',{proof,histories,snapshot:s});
    const original=await this.http('runtime:8080',commands[0].path,{method:'POST',body:commands[0].body,headers:commands[0].headers,bearer:clients.find(c=>c.clientId===commands[0].headers['X-Client-Id']).token});requireValue(original.httpStatus===202,'published idempotent retry');const after=await this.snapshot();requireValue(after.run.possiblePublications==='12','retry no republish');return this.saveContext();
  }
  async faultOpening({existing=false}={}){
    requireValue(this.opening&&this.opening.runId===this.lease.prefix&&sha256(bindingBytes(this.opening))===this.digest,'actual fault opening authority');const path=join(this.lease.stateRoot,'fault-opening.json'),raw=JSON.stringify(this.opening);requireValue(Buffer.byteLength(raw)<=Number(this.opening.budget.openingBytes),'fault opening cap');
    const parent=await pathGuard(this.lease.stateRoot);requireValue((parent.mode&0o777)===0o700,'private fault opening parent');if(!existing)await writeFile(path,raw,{mode:0o600,flag:'wx'});const st=await pathGuard(path,'file');requireValue((st.mode&0o777)===0o600&&st.size<=Number(this.opening.budget.openingBytes),'private bounded fault opening');const actual=await readFile(path,'utf8');requireValue(actual===raw&&sha256(bindingBytes(JSON.parse(actual)))===this.digest,'immutable samebinding fault opening');return this.writeReceipt('actual-fault-opening',{path,sha256:sha256(actual),bytes:Buffer.byteLength(actual),bindingDigest:this.digest,scope:'Nonsecret actual registered binding; immutable existing readonly state mount'});
  }
  async faultLaunch(fault,{existing=false}={}){
    requireValue(this.faultManifest,'frozen fault artifact');const mixed=validateFaultScope(fault,this.lease.prefix);if(mixed)await this.faultOpening({existing});
    const gate=join(this.lease.stateRoot,'gates');if(!existing)await mkdir(gate,{mode:0o700});const s=await pathGuard(gate);requireValue((s.mode&0o777)===0o700,'private fault gates');
    this.runtimeFaultActive=true;this.env.FINITE_RUNTIME_ENTRYPOINT='/opt/java/openjdk/bin/java';this.env.FINITE_RUNTIME_COMMAND='-Xms128m -Xmx768m -XX:ActiveProcessorCount=2 -XX:MaxDirectMemorySize=64m -XX:MaxMetaspaceSize=128m -XX:+ExitOnOutOfMemoryError -cp /fixture/fault/classes/kotlin/test:/fixture/fault/classes/kotlin/main:/fixture/fault/classes/java/main:/fixture/fault/resources/main:/fixture/fault/resolver-probe-deps/* com.reef.platform.calcify.FiniteO2FaultProbe /fixture/state/gates '+fault.boundary+' '+fault.commandId+(mixed?' captureCommandId='+fault.captureCommandId+' openingFile=/fixture/state/fault-opening.json':'');
  }
  async faultEvent(){
    const fault=this.lease.fault;requireValue(fault&&this.faultManifest,'fault lease');const gate=join(this.lease.stateRoot,'gates'),p=join(gate,fault.commandId+'-'+fault.boundary+'.event.json');await pathGuard(p,'file');const raw=await readFile(p);requireValue(raw.length<=4096,'fault event cap');const e=JSON.parse(raw),manifest=await readFile(join(gate,'class-manifest.txt'),'utf8');
    requireValue(e.schema==='calcify-finite-fault-probe-v1'&&e.bindingDigest===this.digest&&e.commandId===fault.commandId&&e.boundary===fault.boundary&&decimal.test(e.pid)&&e.classManifestHash===sha256(manifest),'actual fault boundary identity');
    const found=new Set();for(const line of manifest.trim().split('\n')){const split=line.lastIndexOf(' '),name=line.slice(0,split),hash=line.slice(split+1),role=name.split('/')[0],relative=name.slice(role.length+1);requireValue(['runtime','probe'].includes(role),'classpath manifest role');const path='classes/kotlin/'+(role==='runtime'?'main':'test')+'/'+relative;requireValue(this.faultManifest.files.some(f=>f.path===path&&f.sha256===hash),'loaded class manifest drift');found.add(role);}
    requireValue(found.size===2,'both actual class roots');await this.writeReceipt('actual-fault-boundary',{event:e,classManifestSha256:sha256(manifest),classManifest:manifest});return e;
  }
  async faultRequest(){
    requireValue(this.lease.fault,'fault namespace');const t=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),c=fixtureCommands(t,this.lease.prefix)[0],auth=JSON.parse(await this.secret('client-token')).clients.find(x=>x.clientId===c.headers['X-Client-Id']);
    return this.http('runtime:8080',c.path,{method:'POST',body:c.body,headers:c.headers,bearer:auth.token});
  }
  async containRuntime({kill=false}={}){
    const before=await this.snapshot();requireValue(await this.sql('runtime',`SELECT boundary.calcify_finite_stop(${quoteSQL(this.digest)},${before.run.ownerEpoch});`)==='t','selected current owner durable stop');
    const image=JSON.parse((await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images.runtime],{record:'contain-image'})).stdout),owned=await this.inspectOwned('runtime',image);requireValue(owned.State.Running,'old writer currently identified');
    await this.command(kill?['kill','--signal','KILL',this.ids.runtime]:['stop','--time','15',this.ids.runtime],{timeoutMs:25000,record:kill?'fault-sigkill':'drain-runtime-stop'});
    const exited=await this.inspectOwned('runtime',image);requireValue(exited.State.Running===false&&exited.State.Status==='exited'&&(!kill||exited.State.ExitCode===137),'actual old writer exit');
    const competitors=await this.command(['ps','--quiet','--filter','label=reef.test=calcify-finite-o2','--filter','label=com.docker.compose.project='+this.lease.project,'--filter','label=com.docker.compose.service=runtime'],{record:'old-instance-running-inventory'});requireValue(competitors.stdout.trim()==='','no competing old instance');
    const receipt=await this.writeReceipt('old-writer-contained',{schema:'calcify-finite-o2-contained-v1',project:this.lease.project,id:this.ids.runtime,sourceManifestSha256:this.artifactManifestSha256,image,ownedBefore:owned,exited,competingIds:[],snapshotBefore:before});await this.appLifecycleWitness('runtime',{running:false});return receipt;
  }
  async crash(){
    requireValue(this.lease.fault,'fresh crash namespace');const pending=this.faultRequest().then(async r=>{await this.writeReceipt('fault-request-http',{status:r.httpStatus,bodySha256:r.bodySha256});return {httpStatus:r.httpStatus};}).catch(async()=>{await this.writeReceipt('fault-request-transport',{scope:'Transport failed; not ACK or authorization evidence'});return {transportFailed:true};});
    const event=await this.waitFor('fault-boundary',()=>this.faultEvent(),r=>Boolean(r.boundary),15000);const contained=await this.containRuntime({kill:true});const response=await pending;requireValue(response.httpStatus!==202,'crash cannot claim accepted response');
    const snapshot=await this.snapshot(),history=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[0].name});await this.writeReceipt('crash-retained-proof',{event,contained,response,snapshot,history});return {contained,context:await this.saveContext()};
  }
  async lateAck(){
    requireValue(this.lease.fault?.boundary==='BROKER_ACK','same-context real ACK barrier required');const pending=this.faultRequest();const event=await this.waitFor('actual-ack-gate',()=>this.faultEvent(),r=>r.ack?.topic===this.opening.topics[0].name&&r.ack.partition===0&&decimal.test(r.ack.offset),15000);
    const response=await pending;requireValue(response.httpStatus===503,'timeout must refuse202');const before=await this.snapshot();requireValue(before.run.admissionState==='STOPPED'&&before.journal[0]?.state==='UNKNOWN','actual timeout uncertainty');
    const path=join(this.lease.stateRoot,'gates',event.commandId+'-'+event.boundary+'.release');await writeFile(path,'release same contained callback\n',{mode:0o600,flag:'wx'});
    const after=await this.waitFor('late-positive-marker',()=>this.snapshot(),s=>s.journal[0]?.state==='PUBLISHED'&&s.run.admissionState==='STOPPED');requireValue(after.run.possiblePublications===before.run.possiblePublications&&after.journal[0].ownerEpoch===before.journal[0].ownerEpoch,'late ACK monotonic no refund/resend');
    await this.writeReceipt('actual-late-ack',{event,response:{status:response.httpStatus,sha256:response.bodySha256},before,after});return this.saveContext();
  }
  async coldRepair(containmentPath,commandId){
    requireValue(commandId===this.lease.prefix+'-cmd-01','selected repair scope');const containment=JSON.parse(await readFile(containmentPath,'utf8'));requireValue(containment.schema==='calcify-finite-o2-contained-v1'&&containment.project===this.lease.project&&containment.id===this.ids.runtime&&containment.sourceManifestSha256===this.artifactManifestSha256,'actual containment receipt');
    const actual=await this.inspectOwned('runtime',containment.image);requireValue(!actual.State.Running&&actual.State.Status==='exited'&&actual.HostConfig.RestartPolicy.Name==='no','contained old writer readback');const running=await this.command(['ps','--quiet','--filter','label=com.docker.compose.project='+this.lease.project,'--filter','label=com.docker.compose.service=runtime'],{record:'repair-running-inventory'});requireValue(!running.stdout.trim(),'repair old instance competed');
    const s=await this.snapshot();requireValue(s.run.admissionState==='STOPPED','repair stays stopped');const entry=s.journal.find(j=>j.commandId===commandId);requireValue(entry&&['PUBLISHING','UNKNOWN'].includes(entry.state),'original unresolved charged publication');
    const metadata=await this.probe({mode:'metadata',auth:await this.auth('operator'),topics:this.opening.topics.map(t=>t.name)});for(const t of this.opening.topics)requireValue(metadata.topics.find(m=>m.name===t.name)?.uuid===t.topicId,'repair actual topic identity');
    const history=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[0].name}),records=history.records.filter(r=>JSON.parse(Buffer.from(r.valueBase64,'base64')).commandId===commandId);requireValue(records.length===1,'unique retained real command required');const record=records[0],payload=JSON.parse(Buffer.from(record.valueBase64,'base64')),key=Buffer.from(record.keyBase64,'base64'),subjectHeaders=record.headers.filter(h=>Buffer.from(h.keyBase64,'base64').toString()==='reef-stream-subject');requireValue(subjectHeaders.length===1,'unique canonical subject header');const subject=Buffer.from(subjectHeaders[0].valueBase64,'base64'),route={SubmitOrder:'/api/v1/orders/submit',ModifyOrder:'/api/v1/orders/modify',CancelOrder:'/api/v1/orders/cancel'}[entry.commandType];
    requireValue(route&&entry.routeHash===sha256(route)&&entry.payloadHash===record.payloadSha256&&entry.keyHash===sha256(key)&&entry.subjectHash===sha256(subject)&&key.toString().includes('|'+route+'|')&&payload.commandId===commandId&&payload.runId===this.opening.runId&&payload.venueSessionId===this.opening.venueSessionId&&payload.instrumentId===this.opening.instrumentId,'compiled route/payload/key/subject/scope correspondence');
    const cuts=await this.cuts();requireValue(JSON.stringify(cuts)===JSON.stringify(await this.cuts()),'contained stable cuts');requireValue(await this.sql('runtime',`SELECT boundary.calcify_finite_ack(${quoteSQL(this.digest)},${quoteSQL(commandId)},${entry.ownerEpoch},${record.offset},${BigInt(record.offset)+1n});`,{record:'operator-selected-original-ack'})==='t','durable original ACK repair');
    const after=await this.snapshot();requireValue(after.run.admissionState==='STOPPED'&&after.run.possiblePublications===s.run.possiblePublications&&after.journal.find(j=>j.commandId===commandId)?.state==='PUBLISHED','repair preserved charge/stopped authority');return this.writeReceipt('cold-positive-repair',{containmentPath,before:s,actual,metadata,record,cuts,after});
  }
  async restart(){
    const before=await this.snapshot();requireValue(before.run.unresolvedPublications==='0','restart refuses unresolved');if((await this.command(['inspect','--format','{{json .State.Running}}',this.ids.runtime],{record:'restart-old-runtime-state'})).stdout.trim()==='true'){await this.sql('runtime',`SELECT boundary.calcify_finite_stop(${quoteSQL(this.digest)},${before.run.ownerEpoch});`);await this.waitFor('restart-drain',()=>this.readiness(),r=>Boolean(r.engineHash),60000);await this.containRuntime();}else await this.runtimeRetirementWitness();
    const beforeCuts=await this.cuts();await this.command(['stop','--time','10',this.ids.matcher],{record:'restart-old-matcher-stop'});await this.appLifecycleWitness('matcher',{running:false});this.runtimeFaultActive=false;this.env.FINITE_RUNTIME_ENTRYPOINT='/fixture/platform-runtime/bin/platform-runtime';this.env.FINITE_RUNTIME_COMMAND='';await this.startServices('runtime');await this.waitFor('restored-capture',()=>this.http('runtime:8080','/internal/calcify/finite-capture-readiness?nonce='+randomBytes(16).toString('hex')),r=>r.httpStatus===200,60000);await this.startServices('matcher');const readiness=await this.waitFor('restored-engine',()=>this.readiness(),r=>Boolean(r.engineHash),60000),after=await this.snapshot();
    requireValue(after.run.recordedAttempts===before.run.recordedAttempts&&after.run.possiblePublications===before.run.possiblePublications&&after.run.commandPayloadBytes===before.run.commandPayloadBytes&&after.run.attemptRecordBytes===before.run.attemptRecordBytes&&JSON.stringify(after.journal)===JSON.stringify(before.journal),'restart counters/journal retained');requireValue(readiness.cuts.commandGroup.nextOffset===beforeCuts.commandGroup.nextOffset&&readiness.cuts.ranges[1].endOffset===beforeCuts.ranges[1].endOffset,'replay no committed source republish');await this.writeReceipt('same-binding-restart',{before,beforeCuts,after,readiness,scope:'No seed/reset/rebinding or automatic release'});return this.saveContext();
  }
  async proveBudget(){
    const t=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),commands=fixtureCommands(t,this.lease.prefix),clients=JSON.parse(await this.secret('client-token')).clients;
    const send=async c=>this.http('runtime:8080',c.path,{method:'POST',body:c.body,headers:c.headers,bearer:clients.find(x=>x.clientId===c.headers['X-Client-Id']).token});
    const before=await this.snapshot();requireValue(before.run.admissionState==='ACTIVE'&&before.run.possiblePublications==='12','canonical prefix budget continuation');
    for(let i=13;i<=16;i++){const c=structuredClone(commands[9]),id=this.lease.prefix+'-cmd-'+i;c.body.commandId=id;c.headers['Idempotency-Key']=id;requireValue((await send(c)).httpStatus===202,'last finite publication durable acceptance');}
    const exhausted=await this.snapshot();requireValue(exhausted.run.admissionState==='STOPPED'&&exhausted.run.possiblePublications==='16','actual publication stop');
    for(let i=0;i<8;i++){const c=structuredClone(commands[9]),id=this.lease.prefix+'-future-'+i;c.body.commandId=id;c.headers['Idempotency-Key']=id;requireValue((await send(c)).httpStatus===503,'future inputs refused');}
    const after=await this.snapshot();requireValue(after.run.recordedAttempts===exhausted.run.recordedAttempts&&after.run.possiblePublications==='16'&&after.run.unresolvedPublications==='0','future flood no budget reset');const readiness=await this.waitFor('exhausted-closure',()=>this.readiness(),r=>r.cuts.commandGroup.nextOffset==='16',60000);await this.writeReceipt('finite-budget-closure',{before,exhausted,after,readiness,futureRefusals:8});return this.saveContext();
  }
  async proveAttemptBudget(){
    const t=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),c=fixtureCommands(t,this.lease.prefix)[0],client=JSON.parse(await this.secret('client-token')).clients.find(x=>x.clientId===c.headers['X-Client-Id']);const send=command=>this.http('runtime:8080',command.path,{method:'POST',body:command.body,headers:command.headers,bearer:client.token});
    const before=await this.snapshot();requireValue(before.run.admissionState==='ACTIVE'&&before.run.recordedAttempts==='0'&&before.run.possiblePublications==='0','fresh attempt-budget namespace');const original=await send(c);requireValue(original.httpStatus===202,'original durable publication');
    for(let i=1;i<32;i++){const retry=await send(c);requireValue(retry.httpStatus===202&&retry.bodySha256===original.bodySha256,'exact published retry stable receipt');}
    const exhausted=await this.snapshot();requireValue(exhausted.run.admissionState==='STOPPED'&&exhausted.run.recordedAttempts==='32'&&exhausted.run.possiblePublications==='1','actual attempt stop independently from publication cap');
    for(let i=0;i<8;i++){const next=structuredClone(c),id=this.lease.prefix+'-future-'+i;next.body.commandId=id;next.headers['Idempotency-Key']=id;requireValue((await send(next)).httpStatus===503,'future fresh input refused');}
    const after=await this.snapshot();requireValue(after.run.recordedAttempts==='32'&&after.run.possiblePublications==='1'&&after.run.unresolvedPublications==='0','attempt flood no permit/reset/refund');const readiness=await this.waitFor('attempt-stop-closure',()=>this.readiness(),r=>r.cuts.commandGroup.nextOffset==='1',60000);return this.writeReceipt('actual-attempt-budget',{before,exhausted,after,readiness,exactRetries:31,futureRefusals:8,originalBodySha256:original.bodySha256});
  }
  async sqlDenied(role,statement){
    const r=await this.command(['exec','-i','--env','PGPASSFILE=/run/secrets/'+role+'.pgpass',this.ids.postgres,'psql','-X','-q','-t','-A','-v','ON_ERROR_STOP=1','-v','VERBOSITY=verbose','-U','calcify_finite_'+role,'-h','postgres','-d','finite'],{input:statement,expectedCode:3,record:'actual-sql-denial-'+role});requireValue(r.stderr.includes('42501')&&r.stderr.includes('permission denied'),'actual PG authorization denial');return {role,sqlSha256:sha256(statement),sqlState:'42501'};
  }
  async proveAuthority(){
    const denied=[];for(const statement of [`SELECT boundary.calcify_finite_register(${quoteSQL(this.digest)},'{}'::jsonb,decode('','hex'),'forbidden');`,`SELECT boundary.calcify_finite_stages(${quoteSQL(this.digest)},1,'${'a'.repeat(64)}','${'b'.repeat(64)}','{}'::jsonb);`,`SELECT boundary.calcify_finite_release(${quoteSQL(this.digest)},1,'${'a'.repeat(64)}','${'b'.repeat(64)}','{}'::jsonb);`,`UPDATE boundary.calcify_finite_runs SET attempts=0 WHERE FALSE;`,`SELECT * FROM boundary.calcify_finite_publications LIMIT 1;`,`CREATE TABLE boundary.finite_forbidden_runtime(x INTEGER);`])denied.push(await this.sqlDenied('runtime',statement));denied.push(await this.sqlDenied('registrar','UPDATE boundary.calcify_finite_runs SET attempts=0 WHERE FALSE;'));
    const s=await this.snapshot(),r=await this.readiness();const stale=JSON.parse(await this.sql('registrar',`WITH s AS(SELECT boundary.calcify_finite_snapshot(${quoteSQL(this.digest)}) v) SELECT json_build_object('stage',boundary.calcify_finite_stages(${quoteSQL(this.digest)},${BigInt(s.run.ownerEpoch)+1n},${quoteSQL(r.engineHash)},${quoteSQL(r.captureHash)},'{}'::jsonb),'release',boundary.calcify_finite_release(${quoteSQL(this.digest)},${s.run.ownerEpoch},${quoteSQL(r.engineHash)},${quoteSQL(r.captureHash)},'{}'::jsonb)) FROM s;`,{record:'actual-stale-registrar-refusals'}));requireValue(stale.stage===false&&stale.release===false,'actual stale registrar refusal');return this.writeReceipt('actual-authority-proof',{denied,stale,snapshot:s,readiness:r});
  }
  async proveApiIsolation(){
    const t=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),c=fixtureCommands(t,this.lease.prefix)[0],client=JSON.parse(await this.secret('client-token')).clients.find(x=>x.clientId===c.headers['X-Client-Id']),before=await this.snapshot(),cases=[];
    const check=async(host,path,args,status)=>{const r=await this.http(host,path,args);requireValue(r.httpStatus===status,'actual API isolation status');cases.push({host,path,httpStatus:r.httpStatus,bodySha256:r.bodySha256});};
    await check('matcher:8081','/health',{bearer:''},200);for(const path of ['/orders/submit','/orders/modify','/orders/cancel'])await check('matcher:8081',path,{method:'POST',body:c.body,bearer:client.token},403);
    await check('runtime:8080',c.path,{method:'POST',body:c.body,headers:c.headers,bearer:'wrong-fixture-client-token'},401);
    await check('runtime:8080',c.path,{method:'POST',body:{...c.body,actorId:'unauthorized-actor'},headers:c.headers,bearer:client.token},403);
    await check('runtime:8080',c.path,{method:'POST',body:{...c.body,runId:this.lease.prefix+'-different'},headers:c.headers,bearer:client.token},403);
    await check('runtime:8080','/internal/calcify/finite-bindings/'+this.digest+'?nonce='+randomBytes(16).toString('hex'),{bearer:client.token},401);await check('runtime:8080','/orders/submit',{method:'POST',body:c.body,headers:c.headers,bearer:client.token},404);
    const grpc=await this.probe({mode:'grpc-refused',auth:await this.auth('observer')});const after=await this.snapshot();requireValue(JSON.stringify(before)===JSON.stringify(after),'unauthorized API no durable intake');return this.writeReceipt('actual-api-isolation',{before,after,cases,grpc});
  }
  async missingManagedState(){
    requireValue(this.lease.adverse==='missing-managed-state','dedicated local-state restore case');const before=await this.snapshot();requireValue(before.run.admissionState==='STOPPED'&&BigInt(before.run.activationEpoch)>0n&&before.run.unresolvedPublications==='0','activated stopped resolved binding');
    const receipts=(await readdir(this.lease.proofRoot)).filter(n=>n.endsWith('-fresh-readiness.json')).sort();requireValue(receipts.length,'actual pre-loss readiness required');const baseline=JSON.parse(await readFile(join(this.lease.proofRoot,receipts.at(-1)),'utf8'));requireValue(baseline.capture.bindingDigest===this.digest&&baseline.snapshot.run.ownerEpoch===before.run.ownerEpoch,'same owned baseline');
    for(const service of ['runtime','matcher']){const image=JSON.parse((await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'local-state-image-'+service})).stdout);const owned=await this.inspectOwned(service,image);requireValue(owned.State.Status==='exited'&&!owned.State.Running,'local-state old writer exit');}
    const original=join(this.lease.stateRoot,'capture'),preserved=join(this.lease.stateRoot,'capture-preserved-before-loss');const manifest=await freezeArtifacts(original);await rename(original,preserved);await mkdir(original,{mode:0o700});await this.writeReceipt('local-managed-state-preserved',{before,baseline,original,preserved,manifest,action:'Original local cache retained intact; committed sameUUID managed changelog remains authoritative'});
    this.runtimeFaultActive=false;this.env.FINITE_RUNTIME_ENTRYPOINT='/fixture/platform-runtime/bin/platform-runtime';this.env.FINITE_RUNTIME_COMMAND='';await this.startServices('runtime');await this.waitFor('changelog-cold-restored-capture',()=>this.http('runtime:8080','/internal/calcify/finite-capture-readiness?nonce='+randomBytes(16).toString('hex')),r=>r.httpStatus===200,60000);await this.startServices('matcher');const readiness=await this.waitFor('cold-restored-readiness',()=>this.readiness(),r=>Boolean(r.engineHash),60000),after=await this.snapshot();
    requireValue(after.run.admissionState==='STOPPED'&&BigInt(after.run.ownerEpoch)>BigInt(before.run.ownerEpoch)&&after.run.activationEpoch===before.run.activationEpoch&&after.run.recordedAttempts===before.run.recordedAttempts&&after.run.possiblePublications===before.run.possiblePublications&&JSON.stringify(after.journal)===JSON.stringify(before.journal),'changelog restore retained authority');
    const latest=(await readdir(this.lease.proofRoot)).filter(n=>n.endsWith('-fresh-readiness.json')).sort().at(-1),restored=JSON.parse(await readFile(join(this.lease.proofRoot,latest),'utf8'));requireValue(restored.capture.stateDigest===baseline.capture.stateDigest&&restored.capture.completedRecords===baseline.capture.completedRecords&&restored.capture.representedNextOffset===baseline.capture.representedNextOffset,'actual committed managed-state recovery');await this.writeReceipt('actual-local-state-changelog-restore',{before,after,baseline,restored,readiness,preserved,scope:'Rebuildable local-state loss recovers from retained committed sameUUID changelog; authoritative-state absence not simulated here'});return this.saveContext();
  }
  async mixedPrefix(){
    requireValue(validateFaultScope(this.lease.fault,this.lease.prefix)&&this.lease.fault.captureCommandId===this.lease.prefix+'-cmd-01','dedicated deterministic mixed-prefix fault');const template=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8')),commands=fixtureCommands(template,this.lease.prefix),clients=JSON.parse(await this.secret('client-token')).clients;
    const post=c=>this.http('runtime:8080',c.path,{method:'POST',body:c.body,headers:c.headers,bearer:clients.find(x=>x.clientId===c.headers['X-Client-Id']).token});requireValue((await post(commands[0])).httpStatus===202,'first actual durable published command');
    const pending=post(commands[1]).then(r=>({httpStatus:r.httpStatus})).catch(()=>({transportFailed:true}));const event=await this.waitFor('mixed-second-publishing',()=>this.faultEvent(),e=>e.boundary==='PUBLISHING',15000);const capturePath=join(this.lease.stateRoot,'gates','capture-'+this.lease.fault.captureCommandId+'.event.json');const captureEvent=await this.waitFor('mixed-actual-precommit-capture',async()=>{await pathGuard(capturePath,'file');const raw=await readFile(capturePath);requireValue(raw.length<=4096,'capture event cap');const e=JSON.parse(raw);requireValue(e.schema==='calcify-finite-fault-probe-v1'&&e.pid===event.pid&&e.bindingDigest===this.digest&&e.classManifestHash===event.classManifestHash&&e.boundary==='CAPTURE_PROCESSED_BEFORE_COMMIT'&&e.sourceTopic===this.opening.topics[1].name&&e.sourceTopicId===this.opening.topics[1].topicId&&e.captureCommandId===this.lease.fault.captureCommandId&&e.commandIds?.includes(commands[0].body.commandId)&&decimal.test(e.sourceOffset)&&hashPattern.test(e.stateDigest),'actual samecomposition precommit event');return e;},e=>Boolean(e.stateDigest),15000);
    const before=await this.waitFor('mixed-unknown-before-crash',()=>this.snapshot(),s=>s.journal.length===2&&s.journal[0].state==='PUBLISHED'&&s.journal[1].state==='UNKNOWN'&&s.run.admissionState==='STOPPED',15000),cuts=await this.cuts();requireValue(cuts.captureSourceGroup.nextOffset==='0'&&cuts.ranges[2].readCommittedEndOffset==='0','first known source not capture committed');const commandHistory=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[0].name});requireValue(commandHistory.records.length===1,'second possible publication actually absent before containment');const sourceHistory=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[1].name}),sourceMetadata=await this.probe({mode:'metadata',auth:await this.auth('operator'),topics:[this.opening.topics[1].name]}),captureProof=validateCaptureCommandProof({opening:this.opening,digest:this.digest,fault:this.lease.fault,ingress:event,event:captureEvent,sourceHistory,sourceMetadata,commandHistory,journal:before.journal,cuts});const stableCuts=await this.cuts();requireValue(JSON.stringify(stableCuts)===JSON.stringify(cuts),'mixed preEOS cuts moved before kill');await this.writeReceipt('actual-mixed-preEOS-command-source-proof',{captureProof,captureEvent,sourceHistory,sourceMetadata,cuts,stableCuts});
    const contained=await this.containRuntime({kill:true});await this.command(['stop','--time','10',this.ids.matcher],{record:'mixed-old-matcher-stop'});await this.appLifecycleWitness('matcher',{running:false});const response=await pending;this.runtimeFaultActive=false;this.env.FINITE_RUNTIME_ENTRYPOINT='/fixture/platform-runtime/bin/platform-runtime';this.env.FINITE_RUNTIME_COMMAND='';await this.startServices('runtime');
    const captureHistory=await this.waitFor('mixed-known-prefix-drained',async()=>this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[2].name}),h=>h.records.length===1,60000),after=await this.snapshot(),afterCuts=await this.cuts();requireValue(after.run.admissionState==='STOPPED'&&after.run.unresolvedPublications==='1'&&after.run.recordedAttempts===before.run.recordedAttempts&&after.run.possiblePublications===before.run.possiblePublications&&JSON.stringify(after.journal)===JSON.stringify(before.journal)&&BigInt(afterCuts.captureSourceGroup.nextOffset)>0n,'known prefix drains without uncertainty repair/refund');const restoredCaptureProof=validateRestoredCaptureProof(this.opening,this.digest,captureProof,captureHistory,afterCuts);
    requireValue((await post(commands[2])).httpStatus===503,'unresolved new admission refused');const startup=await this.http('runtime:8080','/internal/calcify/finite-bindings/'+this.digest+'?nonce='+randomBytes(16).toString('hex'));requireValue(startup.httpStatus!==200,'unresolved activation witness refused');const release=await this.sql('registrar',releaseSQL(this.digest,after.run.ownerEpoch,'a'.repeat(64),'b'.repeat(64),afterCuts));requireValue(release==='f\nf','unresolved stages/release refused');const final=await this.snapshot(),retainedCommands=await this.probe({mode:'history',auth:await this.auth('observer'),topic:this.opening.topics[0].name});requireValue(final.run.recordedAttempts===before.run.recordedAttempts&&final.run.possiblePublications===before.run.possiblePublications&&retainedCommands.records.length===1&&final.journal[1].state==='UNKNOWN','no future attempt/send/release');await this.writeReceipt('actual-mixed-prefix-unresolved-drain',{before,cuts,stableCuts,event,captureEvent,captureProof,sourceHistory,sourceMetadata,contained,response,after,afterCuts,captureHistory,restoredCaptureProof,final,retainedCommands});return this.saveContext();
  }
  async retainedLedger(projects,hostRoots){
    requireValue(this.lease.scope==='pg-test'&&projects.length<=48&&hostRoots.length<=96,'bounded offline retained ledger');const volumes=[];
    for(const project of projects){requireValue(namePattern.test(project),'owned retained project');const existing=await this.command(['ps','--quiet','--filter','label=reef.test=calcify-finite-o2','--filter','label=com.docker.compose.project='+project],{record:'retained-stopped-project'});requireValue(!existing.stdout.trim(),'retained resources still active');
      const names=await this.command(['volume','ls','--quiet','--filter','label=reef.test=calcify-finite-o2','--filter','label=com.docker.compose.project='+project],{record:'retained-volume-inventory'});
      for(const name of names.stdout.trim().split('\n').filter(Boolean)){requireValue(name===project+'-postgres-data'||name===project+'-broker-data','exact retained volume');const inspect=await this.command(['volume','inspect','--format','{{json .}}',name],{record:'retained-volume-labels'}),v=JSON.parse(inspect.stdout);requireValue(v.Name===name&&v.Labels['reef.test']==='calcify-finite-o2'&&v.Labels['com.docker.compose.project']===project,'retained volume ownership');
        await this.writeReceipt('retained-reader-intent',{project,volume:name,image:PG_IMAGE,memoryBytes:33554432,nanoCpus:100000000,network:'none',readonly:true});const readerUser=name.endsWith('-postgres-data')?'postgres':this.lease.retainedVolumeUsers?.[name];requireValue(readerUser==='postgres'||/^[0-9]{1,5}:[0-9]{1,5}$/.test(readerUser),'actual prior broker volume UID readback required');const measured=await this.command(['run','--rm','--name',project+'-disk-reader','--network','none','--memory','32m','--cpus','0.1','--read-only','--cap-drop','ALL','--user',readerUser,'--security-opt','no-new-privileges','--label','reef.test=calcify-finite-o2','--label','com.docker.compose.project='+project,'--mount','type=volume,source='+name+',target=/owned-volume,readonly','--entrypoint','/usr/bin/du',PG_IMAGE,'-sk','/owned-volume'],{record:'retained-allocated-volume'});const n=Number(measured.stdout.trim().split(/\s/)[0])*1024;requireValue(Number.isSafeInteger(n)&&n>=0,'retained allocated volume');volumes.push({project,name,readerUser,allocatedBytes:n,observedAt:new Date().toISOString(),labels:v.Labels});}
    }
    const roots=[...new Set(hostRoots)].filter(p=>!hostRoots.some(parent=>parent!==p&&p.startsWith(parent+'/'))),hosts=[];
    for(const path of roots){requireValue(isAbsolute(path)&&(path.startsWith('/private/tmp/calcify-o2-')||path.startsWith(join(ROOT,'.planning/calcify-p3-o2-2026-10-10/'))),'exact retained own host root');await pathGuard(path).catch(()=>pathGuard(path,'file'));const r=await boundedCommand('/usr/bin/du',['-sk',path],{secrets:this.secrets});requireValue(r.exit.code===0,'retained host du exit');const n=Number(r.stdout.trim().split(/\s/)[0])*1024;requireValue(Number.isSafeInteger(n)&&n>=0,'retained host allocated');hosts.push({path,allocatedBytes:n});await this.writeReceipt('retained-host-allocated',r);}
    const ledger={schema:'calcify-finite-o2-retained-ledger-v1',observedAt:new Date().toISOString(),volumes,hostRoots:hosts,logAllowanceBytes:projects.length*16*1024**2,allocatedBytes:volumes.reduce((n,v)=>n+v.allocatedBytes,0)+hosts.reduce((n,v)=>n+v.allocatedBytes,0)+projects.length*16*1024**2,scope:'Only exact stopped owned resources; logs conservative allowance; working checkout/build-cache baseline excluded; no prune or history deletion'};requireValue(ledger.allocatedBytes<LIMITS.diskAbortBytes,'cumulative retained disk abort');return {ledger,receipt:await this.writeReceipt('retained-allocated-ledger',ledger)};
  }
  async sample(){
    const ledger=this.lease.retainedLedger;requireValue(ledger?.schema==='calcify-finite-o2-retained-ledger-v1'&&Number.isSafeInteger(ledger.allocatedBytes)&&ledger.allocatedBytes>=0,'frozen cumulative retained ledger required');let allocatedBytes=ledger.allocatedBytes+16*1024**2,guestFreeBytes;const processes=[];
    for(const [service,id] of Object.entries(this.ids)){
      const stats=await this.command(['stats','--no-stream','--format','{{json .MemUsage}}',id],{record:'actual-memory-'+service});const text=JSON.parse(stats.stdout).split('/')[0].trim(),m=text.match(/^([0-9.]+)(B|KiB|MiB|GiB)$/);requireValue(m,'memory units');const memoryBytes=Math.ceil(Number(m[1])*({B:1,KiB:1024,MiB:1024**2,GiB:1024**3}[m[2]]));const capBytes={broker:2048,postgres:512,runtime:1024,matcher:128}[service]*1024**2;processes.push({service,id,memoryBytes,capBytes});
      if(service==='broker'||service==='postgres'){const p=service==='broker'?'/var/lib/redpanda/data':'/var/lib/postgresql/data';const disk=await this.command(['exec',id,'du','-sk',p],{record:'actual-disk-'+service});const n=Number(disk.stdout.trim().split(/\s/)[0]);requireValue(Number.isSafeInteger(n),'allocated disk');allocatedBytes+=n*1024;if(service==='broker'){const df=await this.command(['exec',id,'df','-Pk',p],{record:'actual-guest-free'});guestFreeBytes=Number(df.stdout.trim().split('\n').at(-1).split(/\s+/)[3])*1024;}}
    }
    const candidates=[this.lease.stateRoot,this.lease.proofRoot,this.lease.secretRoot,this.lease.artifacts.distribution,this.lease.artifacts.matcherBinary,this.lease.artifacts.probeBinary,...(this.lease.artifacts.faultProbeDistribution?[this.lease.artifacts.faultProbeDistribution]:[])];const currentRoots=[...new Set(candidates)].filter(p=>!candidates.some(parent=>parent!==p&&p.startsWith(parent+'/'))&&!ledger.hostRoots.some(h=>p===h.path||p.startsWith(h.path+'/')));if(currentRoots.length){const measured=await boundedCommand('/usr/bin/du',['-sk',...currentRoots],{secrets:this.secrets});requireValue(measured.exit.code===0,'current own allocated disk');allocatedBytes+=measured.stdout.trim().split('\n').reduce((n,line)=>n+Number(line.split(/\s/)[0])*1024,0);}
    const s=validateSample({schema:'calcify-finite-o2-resource-sample-v1',observedAt:new Date().toISOString(),allocatedBytes,guestFreeBytes,proofBytes:await rawTree(this.lease.proofRoot),controllerRssBytes:process.memoryUsage().rss,processes:[...processes,{service:'controller-allowance',memoryBytes:process.memoryUsage().rss,capBytes:LIMITS.controllerBytes}],scope:'Observed samples; helper shares broker cgroup; disk is abort threshold, not quota; no peak claim'});return this.writeReceipt('resources',s);
  }
  async stopOwned(){
    this.cleanupOnly=true;
    const results=[];
    for(const service of ['runtime','matcher','postgres','broker']){
      const id=this.ids[service];if(!id)continue;
      try{if(this.lease.scope==='full'){const image=JSON.parse((await this.command(['image','inspect','--format','{{json .Id}}',this.lease.images[service]],{record:'cleanup-image-'+service})).stdout);await this.inspectOwned(service,image);}else{const labels=await this.command(['inspect','--format','{{json .Config.Labels}}',id],{record:'cleanup-ownership-'+service});const l=JSON.parse(labels.stdout);requireValue(l['reef.test']==='calcify-finite-o2'&&l['com.docker.compose.project']===this.lease.project&&l['com.docker.compose.service']===service,'cleanup ownership drift');}const result=await this.command(['stop','--time','10',id],{timeoutMs:20000,record:'stop-'+service});results.push({service,id,exit:result.exit});}catch{results.push({service,id,failed:true});}
    }
    await this.writeReceipt('cleanup',{schema:'calcify-finite-o2-cleanup-v1',results,volumesPreserved:true});return results;
  }
}

async function main(){
  const [phase,...args]=process.argv.slice(2);const options={};for(let i=0;i<args.length;i+=2){requireValue(args[i].startsWith('--')&&args[i+1],'CLI pair');options[args[i].slice(2)]=args[i+1];}
  if(phase==='plan'){const template=JSON.parse(await readFile(join(here,'finite-o2/fixture.json'),'utf8'));console.log(JSON.stringify({schema:'calcify-finite-o2-plan-v1',services:['broker','postgres','runtime','matcher'],limits:LIMITS,commands:template.commands.length,expected:template.expected,leaseRequired:true,scope:'Finite correctness; no throughput/production claim'},null,2));return;}
  requireValue(options.lease,'manager lease path required');const lease=JSON.parse(await readFile(options.lease,'utf8'));const s=new Supervisor(lease,{cleanupOnly:phase==='stop'});
  if(phase==='prepare'){console.log(JSON.stringify(await s.prepare()));return;}
  if(phase==='pg-test-start'){const id=await s.createPgTest();console.log(JSON.stringify({containerId:id,proofRoot:lease.proofRoot,secretsRoot:lease.secretRoot}));return;}
  if(phase==='pg-test-init'){requireValue(options.container&&options.migration,'owned PG and frozen migration required');s.ids.postgres=options.container;await s.loadSecrets();console.log(JSON.stringify(await s.initializeSql(resolve(options.migration))));return;}
  if(phase==='stop'){requireValue(options.resources,'frozen owned resources required');s.ids=JSON.parse(await readFile(options.resources,'utf8'));await s.loadSecrets();console.log(JSON.stringify(await s.stopOwned()));return;}
  if(['bootstrap','bootstrap-backends','bootstrap-pre-matcher'].includes(phase)){console.log(JSON.stringify(await s.bootstrap({backendOnly:phase==='bootstrap-backends',preMatcher:phase==='bootstrap-pre-matcher'})));return;}
  if(['readiness','release','prove-flow','sample','wire-fit','crash','late-ack','cold-repair','restart','prove-budget','prove-attempt-budget','prove-authority','prove-api-isolation','missing-managed-state','mixed-prefix'].includes(phase)){requireValue(options.context,'frozen context receipt required');await s.restoreContext(resolve(options.context));let result;if(phase==='readiness')result=await s.readiness();else if(phase==='release')result=await s.release();else if(phase==='prove-flow')result=await s.proveFlow();else if(phase==='sample')result=await s.sample();else if(phase==='crash')result=await s.crash();else if(phase==='late-ack')result=await s.lateAck();else if(phase==='cold-repair'){requireValue(options.containment&&options.command,'exact contained original command required');result=await s.coldRepair(resolve(options.containment),options.command);}else if(phase==='restart')result=await s.restart();else if(phase==='prove-budget')result=await s.proveBudget();else if(phase==='prove-attempt-budget')result=await s.proveAttemptBudget();else if(phase==='prove-authority')result=await s.proveAuthority();else if(phase==='prove-api-isolation')result=await s.proveApiIsolation();else if(phase==='missing-managed-state')result=await s.missingManagedState();else if(phase==='mixed-prefix')result=await s.mixedPrefix();else result=await s.probe({mode:'wire-fit',auth:await s.auth('operator'),topic:lease.prefix+'-state-fit'});console.log(JSON.stringify(result));return;}
  throw Error('Unknown/unimplemented live phase; no acceptance claim');
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(()=>{console.error('FINITE_O2_FIXTURE_FAILED; bounded receipts retained; inspect required gate');process.exitCode=1;});
