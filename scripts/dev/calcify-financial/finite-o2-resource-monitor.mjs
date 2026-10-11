#!/usr/bin/env bun
import { lstat, realpath, readdir, readFile, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { performance } from 'node:perf_hooks';
import { Supervisor, ROOT, LIMITS, validateLease, checkOwned, validateSample, boundedCommand, noSecretSpill } from './finite-o2-proof.mjs';

export const MONITOR = Object.freeze({ periodMs:5000, deadlineMs:4500, maxGapMs:10000, startupMs:30000, maxIds:16, maxRoots:96, maxOutput:262144 });
const SERVICES = ['runtime','matcher','postgres','broker'];
const ID = /^[0-9a-f]{64}$/;
// Explicit projection: never read Config.Env, command arguments, or credentials.
export const INSPECT = '{"Id":{{json .Id}},"Image":{{json .Image}},"State":{"Running":{{json .State.Running}},"Status":{{json .State.Status}},"ExitCode":{{json .State.ExitCode}},"Pid":{{json .State.Pid}}},"HostConfig":{"Memory":{{json .HostConfig.Memory}},"NanoCpus":{{json .HostConfig.NanoCpus}},"RestartPolicy":{{json .HostConfig.RestartPolicy}},"Privileged":{{json .HostConfig.Privileged}},"ReadonlyRootfs":{{json .HostConfig.ReadonlyRootfs}},"LogConfig":{{json .HostConfig.LogConfig}}},"Config":{"Labels":{{json .Config.Labels}}},"Mounts":{{json .Mounts}},"NetworkSettings":{"Ports":{{json .NetworkSettings.Ports}},"Networks":{{json .NetworkSettings.Networks}}}}';
const need = (ok,code) => { if(!ok) throw Error(code); };
const integer = n => Number.isSafeInteger(n)&&n>=0;
const lines = s => s.trim().split('\n').filter(Boolean);
const FAILURE_STAGES = new Set(['initialize','lease','secrets','roots','owned_receipt','inventory','inventory_list','inventory_inspect','backend_state','controller_state','owned_stats','stats_post_inspect','stats_validate','backend_du','backend_df','host_du','controller_ps','proof_scan','measurements','controller_rss','resource_validate','sample_receipt','monitor_loop']);
const FAILURE_GUARDS = new Set(['startup_deadline','sample_deadline','sample_gap','disk abort','proof abort','guest disk floor','controller RSS','memory sample/cap','aggregate memory cap','stats_missing','stats_cap_drift','stats_state_race','stats_identity','stats_shape','stats_lifecycle_unstable','backend_lifecycle_changed','retired_identity_missing','retired_witness_missing','replacement_identity','inspect_state','docker_measurement_failed','inventory_identity','inspect_identity','inventory_service','inventory_receipt_conflict','owned_id_cap','duplicate_running_service','backend_not_running','controller_state_scope','private_json','private_file','path_alias','controller_identity','controller_ps_identity','controller_pid_reused','controller_ps_failed','controller_rss_value','host_du_failed','host_du_shape','du_value','df_value','memory_units','memory_value','log_cap_drift','owned_receipt_count','owned_receipt_file','owned_receipt_json','owned_receipt_scope','owned_receipt_identity','service_secrets_missing','proof_entry_cap','proof_file_type','proof_abort','receipt_size','image_identity','existing_resource_abort','container ID','ownership labels','image drift','published ports','Docker socket mount','actual resource caps','restart/privilege drift','private network drift','volume drift','bind mount drift','credential mount expansion','app root write expansion','expired lease','secret spill','command deadline','output cap']);
const failureDetails = new WeakMap();
export function failureGuard(code) {return FAILURE_GUARDS.has(code)?code.replace(/[^a-zA-Z0-9]+/g,'_').toLowerCase():'internal_unknown';}
async function observed(stage,work) {
  try{return await work();}catch(error){const e=error instanceof Error?error:Error('internal_unknown');if(!failureDetails.has(e))failureDetails.set(e,{stage:FAILURE_STAGES.has(stage)?stage:'internal_unknown',guard:failureGuard(e.message)});throw e;}
}
export function dedupRoots(roots) {
  need(Array.isArray(roots)&&roots.length<=MONITOR.maxRoots,'owned_root_count');
  const unique=[...new Set(roots)];
  for(const p of unique) need(typeof p==='string'&&isAbsolute(p)&&resolve(p)===p&&!/[\r\n\t\0]/.test(p)&&(p.startsWith('/private/tmp/calcify-o2-')||p.startsWith(join(ROOT,'.planning/calcify-p3-o2-2026-10-10/'))),'owned_root_scope');
  return unique.filter(p=>!unique.some(parent=>parent!==p&&p.startsWith(parent+'/'))).sort();
}
export function monitorLease(lease) {
  validateLease(lease);need(lease.scope==='full','full_lease_required');
  const l=lease.retainedLedger;
  need(l?.schema==='calcify-finite-o2-retained-ledger-v1'&&Number.isFinite(Date.parse(l.observedAt))&&Array.isArray(l.volumes)&&l.volumes.length<=74&&Array.isArray(l.hostRoots)&&l.hostRoots.length<=MONITOR.maxRoots,'retained_ledger');
  const names=new Set();let total=0;
  for(const v of l.volumes){need(/^calcify-o2-[a-z0-9-]+$/.test(v.project)&&[v.project+'-broker-data',v.project+'-postgres-data'].includes(v.name)&&!names.has(v.name)&&integer(v.allocatedBytes),'retained_volume');names.add(v.name);total+=v.allocatedBytes;}
  for(const h of l.hostRoots){need(integer(h.allocatedBytes),'retained_host');total+=h.allocatedBytes;}
  need(dedupRoots(l.hostRoots.map(h=>h.path)).length===l.hostRoots.length,'retained_host_overlap');
  need(integer(l.logAllowanceBytes??0)&&integer(l.allocatedBytes)&&l.allocatedBytes===total+(l.logAllowanceBytes??0),'retained_total');
  need(Array.isArray(lease.artifactRoots)&&lease.artifactRoots.length>0,'artifact_roots_required');
  const artifacts=[lease.artifacts.distribution,lease.artifacts.matcherBinary,lease.artifacts.probeBinary,...(lease.artifacts.faultProbeDistribution?[lease.artifacts.faultProbeDistribution]:[])];
  dedupRoots(lease.artifactRoots);
  for(const p of artifacts) need(lease.artifactRoots.some(root=>p===root||p.startsWith(root+'/')),'artifact_root_missing');
  return {roots:dedupRoots([...new Set([...l.hostRoots.map(h=>h.path),...lease.artifactRoots,lease.stateRoot,lease.proofRoot,lease.secretRoot])]),priorVolumeBytes:l.volumes.filter(v=>![lease.project+'-broker-data',lease.project+'-postgres-data'].includes(v.name)).reduce((n,v)=>n+v.allocatedBytes,0),logAllowanceBytes:(l.logAllowanceBytes??0)+MONITOR.maxIds*2*1024**2};
}
export function checkLogs(c) {need(c.HostConfig.LogConfig?.Type==='json-file'&&c.HostConfig.LogConfig.Config?.['max-size']==='1m'&&c.HostConfig.LogConfig.Config?.['max-file']==='2','log_cap_drift');}
export function memoryBytes(text) {
  const m=String(text).trim().match(/^([0-9]+(?:\.[0-9]+)?)\s*(B|KiB|MiB|GiB|kB|MB|GB)$/);
  need(m,'memory_units');const n=Math.ceil(Number(m[1])*({B:1,KiB:1024,MiB:1024**2,GiB:1024**3,kB:1000,MB:1000**2,GB:1000**3}[m[2]]));need(integer(n),'memory_value');return n;
}
function parsedStats(rows,containers) {
  const known=new Map(containers.map(c=>[c.Id,c])), seen=new Map();
  for(const row of rows){const id=row.ID??row.Container;need(ID.test(id)&&known.has(id)&&!seen.has(id),'stats_identity');const p=String(row.MemUsage).split('/');need(p.length===2,'stats_shape');const r={memoryBytes:memoryBytes(p[0]),reportedCapBytes:memoryBytes(p[1])};need(r.memoryBytes<=known.get(id).HostConfig.Memory,'memory sample/cap');seen.set(id,r);}
  return seen;
}
export function statsProcesses(rows,containers) {
  const seen=parsedStats(rows,containers);
  return containers.map(c=>{const service=c.Config.Labels['com.docker.compose.service'],running=c.State.Running===true,r=seen.get(c.Id);need(!running||r,'stats_missing');need(!r||r.reportedCapBytes===c.HostConfig.Memory||(!running&&r.reportedCapBytes===0),'stats_cap_drift');need(running||!r||r.memoryBytes===0,'stats_state_race');return {service,id:c.Id,running,memoryBytes:r?.memoryBytes??0,capBytes:running?c.HostConfig.Memory:0,configuredCapBytes:c.HostConfig.Memory};});
}
export function controllerRss(state,ps,own={pid:process.pid,rss:process.memoryUsage().rss}) {
  need(Number.isSafeInteger(state.pid)&&state.pid>0&&Number.isFinite(Date.parse(state.processStartedAt))&&integer(own.rss),'controller_identity');
  if(state.pid===own.pid) return {controllerRssBytes:own.rss,monitorRssBytes:own.rss,supervisorRssBytes:0,supervisorPid:state.pid,sameProcess:true};
  let rss=0;
  if(ps.exit.code===0&&ps.stdout.trim()){
    const m=ps.stdout.trim().match(/^(\d+)\s+(.+?)\s+(\d+)$/);need(m&&Number(m[1])===state.pid,'controller_ps_identity');
    need(Number.isFinite(Date.parse(m[2]))&&Math.abs(Date.parse(m[2])-Date.parse(state.processStartedAt))<=3000,'controller_pid_reused');rss=Number(m[3])*1024;need(integer(rss),'controller_rss_value');
  }else need(ps.exit.code===1&&!ps.stdout.trim()&&!ps.stderr.trim(),'controller_ps_failed');
  need(integer(own.rss+rss),'controller_rss_value');return {controllerRssBytes:own.rss+rss,monitorRssBytes:own.rss,supervisorRssBytes:rss,supervisorPid:state.pid,sameProcess:false};
}
export function measuredSample(data,timing) {
  need(integer(data.controllerRssBytes),'controller_rss_value');
  need(Number.isFinite(timing.durationMs)&&timing.durationMs>=0&&timing.durationMs<=MONITOR.deadlineMs,'sample_deadline');
  need(Number.isFinite(timing.startGapMs)&&timing.startGapMs>=0&&timing.startGapMs<=MONITOR.maxGapMs,'sample_gap');
  return validateSample({schema:'calcify-finite-o2-resource-sample-v1',...data,...timing,processes:[...data.processes,{service:'controller-allowance',memoryBytes:data.controllerRssBytes,capBytes:LIMITS.controllerBytes}],scope:`Actual Docker sampled working memory, not peak or physical RSS; service cgroup limits verified. Monitor plus supervisor RSS observed against ${LIMITS.controllerBytes/1024**2}MiB allowance; no physical controller cap. Prior stopped volume snapshots retained, host roots remeasured and deduplicated; logs conservative allowance, disk abort threshold not quota.`});
}
export function startupSample(data,timing) {
  need(data.phase==='startup'&&Array.isArray(data.pending)&&data.pending.includes('backend_volume_complete'),'startup_shape');
  // Missing backend measurements stay null/PENDING; never fabricate a successful full sample.
  if(data.guestFreeBytes!==null)need(integer(data.guestFreeBytes)&&data.guestFreeBytes>=20*1024**3,'guest disk floor');
  need(integer(data.allocatedBytes)&&data.allocatedBytes<LIMITS.diskAbortBytes,'disk abort');need(integer(data.proofBytes)&&data.proofBytes<LIMITS.proofBytes,'proof abort');
  need(integer(data.controllerRssBytes)&&data.controllerRssBytes<=LIMITS.controllerBytes,'controller RSS');
  need(timing.durationMs>=0&&timing.durationMs<=MONITOR.deadlineMs,'sample_deadline');need(timing.startGapMs>=0&&timing.startGapMs<=MONITOR.maxGapMs,'sample_gap');
  let caps=LIMITS.controllerBytes;for(const p of data.processes){need(integer(p.capBytes)&&integer(p.memoryBytes)&&p.memoryBytes<=p.capBytes,'memory sample/cap');caps+=p.capBytes;}need(caps<=LIMITS.memoryMiB*1024**2,'aggregate memory cap');
  return {schema:'calcify-finite-o2-resource-startup-sample-v1',...data,...timing,scope:'Startup only: actual available owned services and host allocations observed; missing backend volumes/guest floor explicitly pending. Working memory is sampled, no peak claim; controller RSS observational, no physical cap.'};
}
async function canonical(path,{privateFile=false}={}) {
  const s=await lstat(path);need(!s.isSymbolicLink()&&await realpath(path)===path,'path_alias');
  if(privateFile)need(s.isFile()&&s.uid===process.getuid()&&(s.mode&0o077)===0&&s.size<=32768,'private_file');return s;
}
async function privateJson(path) {
  // Supervisor append/write is finite but not rename-atomic; tolerate a short partial write.
  for(let attempt=0;attempt<3;attempt++) {await canonical(path,{privateFile:true});try{return JSON.parse(await readFile(path,'utf8'));}catch(e){if(attempt===2)throw Error('private_json');await new Promise(r=>setTimeout(r,10));}}
}
async function treeBytes(root) {
  let entries=0,total=0;
  async function visit(path){need(++entries<=8192,'proof_entry_cap');const s=await canonical(path);if(s.isDirectory()){for(const name of await readdir(path))await visit(join(path,name));}else {need(s.isFile(),'proof_file_type');total+=s.size;need(total<LIMITS.proofBytes,'proof_abort');}}
  await visit(root);return total;
}
export class ResourceMonitor {
  constructor(lease,{run=boundedCommand,docker='/usr/local/bin/docker',now=()=>Date.now(),mono=()=>performance.now()}={}) {
    this.lease=lease;this.accounting=monitorLease(lease);this.run=run;this.docker=docker;this.now=now;this.mono=mono;this.secrets=[];this.expected={};this.known=new Map();this.retiredWitness=new Map();this.sequence=0;this.previousStart=null;this.started=this.mono();this.deadline=null;this.backendObserved=false;
  }
  async execute(executable,argv,{cleanup=false}={}) {
    const remaining=cleanup?4000:Math.floor(this.deadline-this.mono());need(remaining>0,'sample_deadline');
    const result=await this.run(executable,argv,{timeoutMs:Math.min(remaining,4000),maxBytes:MONITOR.maxOutput,secrets:this.secrets});
    noSecretSpill(result.stdout,this.secrets);noSecretSpill(result.stderr,this.secrets);return result;
  }
  async dockerCommand(argv,options){const r=await this.execute(this.docker,argv,options);need(r.exit.code===0&&!r.exit.signal,'docker_measurement_failed');return r;}
  async initialize() {
    await this.refreshSecrets();
    await this.currentRoots();
    this.deadline=this.started+MONITOR.deadlineMs;
    const names=['broker','postgres','runtime','matcher'];
    const r=await this.dockerCommand(['image','inspect','--format','{{json .Id}}',...names.map(n=>this.lease.images[n])]);
    const ids=lines(r.stdout).map(line=>JSON.parse(line));need(ids.length===4&&ids.every(id=>/^sha256:[0-9a-f]{64}$/.test(id)),'image_identity');names.forEach((n,i)=>this.expected[n]=ids[i]);
    need(this.expected.runtime===this.lease.images.runtime&&this.expected.matcher===this.lease.images.matcher,'image_identity');
  }
  async refreshSecrets() {
    try{await canonical(this.lease.secretRoot);}catch(e){if(e.code==='ENOENT'&&!this.backendObserved)return;throw e;}
    const s=new Supervisor(this.lease);await s.loadSecrets();this.secrets=s.secrets;
  }
  async currentRoots() {
    const roots=[];for(const root of this.accounting.roots){try{await canonical(root);roots.push(root);}catch(e){if(e.code==='ENOENT'&&!this.backendObserved&&[this.lease.stateRoot,this.lease.secretRoot].includes(root))continue;throw e;}}return roots;
  }
  async ownedReceipt() {
    const files=(await readdir(this.lease.proofRoot)).filter(n=>/^\d{4,}-owned-resources\.json$/.test(n)).sort();need(files.length<=1024,'owned_receipt_count');
    // All append-only receipts retain replaced IDs; actual inventory determines presence.
    for(const file of files){const p=join(this.lease.proofRoot,file);await canonical(p);const stat=await lstat(p);need(stat.isFile()&&stat.size<=32768,'owned_receipt_file');let r;for(let a=0;a<3;a++){try{r=JSON.parse(await readFile(p,'utf8'));break;}catch{if(a===2)throw Error('owned_receipt_json');await new Promise(resolve=>setTimeout(resolve,10));}}
      need(r.schema==='calcify-finite-o2-owned-v1'&&r.project===this.lease.project&&r.ids&&Object.keys(r.ids).every(n=>SERVICES.includes(n)),'owned_receipt_scope');for(const [service,id]of Object.entries(r.ids)){need(ID.test(id)&&(!this.known.has(id)||this.known.get(id)===service),'owned_receipt_identity');this.known.set(id,service);}}
    need(this.known.size<=MONITOR.maxIds,'owned_id_cap');return files.at(-1)??null;
  }
  async inventory({cleanup=false}={}) {
    const r=await observed('inventory_list',()=>this.dockerCommand(['ps','--all','--no-trunc','--format','{"ID":{{json .ID}}}','--filter','label=reef.test=calcify-finite-o2','--filter','label=com.docker.compose.project='+this.lease.project],{cleanup}));
    const ids=lines(r.stdout).map(line=>JSON.parse(line).ID);need(ids.length<=MONITOR.maxIds&&new Set(ids).size===ids.length&&ids.every(id=>ID.test(id)),'inventory_identity');
    if(!ids.length)return [];
    const rows=await observed('inventory_inspect',()=>this.inspectIds(ids,{cleanup}));
    let failure;for(const c of rows){try{this.verifyEndpoint(c);const service=c.Config.Labels['com.docker.compose.service'];need(!this.known.has(c.Id)||this.known.get(c.Id)===service,'inventory_receipt_conflict');this.known.set(c.Id,service);}catch(e){failure??=e;}}if(failure)throw failure;
    need(this.known.size<=MONITOR.maxIds,'owned_id_cap');return rows;
  }
  async inspectIds(ids,options) {
    const inspect=await this.dockerCommand(['inspect','--format',INSPECT,...ids],options);const rows=lines(inspect.stdout).map(line=>JSON.parse(line));need(rows.length===ids.length&&new Set(rows.map(c=>c.Id)).size===rows.length&&rows.every(c=>ids.includes(c.Id)),'inspect_identity');return rows;
  }
  verifyEndpoint(c) {
    const service=c.Config?.Labels?.['com.docker.compose.service'];need(SERVICES.includes(service),'inventory_service');checkOwned(c,this.lease,service,this.expected[service]);checkLogs(c);
    need(typeof c.State?.Running==='boolean'&&integer(c.State.Pid)&&(c.State.Running?c.State.Pid>0:c.State.Pid===0),'inspect_state');
    if(c.State.Running)this.retiredWitness.delete(c.Id);
  }
  completedSample(sample,index) {
    this.sequence=index;this.lastCompletedObservation={sample:index,...this.failedObservation};
    if(sample.phase!=='complete')return;
    for(const c of sample.inspections){const service=c.Config.Labels['com.docker.compose.service'];this.verifyEndpoint(c);if(['runtime','matcher'].includes(service)&&!c.State.Running&&c.State.Pid===0&&c.State.Status==='exited'&&c.HostConfig.RestartPolicy.Name==='no')this.retiredWitness.set(c.Id,{service,completedAt:this.mono(),sample:index});}
  }
  async retiredReplacement(pre,rows) {
    // A failed fixed-ID inspect is not retirement proof. First stats must remain valid.
    await observed('stats_validate',()=>statsProcesses(rows,pre));
    const current=await observed('inventory',()=>this.inventory()),present=new Map(current.map(c=>[c.Id,c])),before=new Map(pre.map(c=>[c.Id,c]));
    const missing=pre.filter(c=>!present.has(c.Id));need(missing.length>0,'retired_identity_missing');const replaced=new Set();
    for(const c of missing){const service=c.Config.Labels['com.docker.compose.service'],w=this.retiredWitness.get(c.Id);need(['runtime','matcher'].includes(service)&&!c.State.Running&&c.State.Pid===0&&c.State.Status==='exited'&&c.HostConfig.RestartPolicy.Name==='no'&&w?.service===service&&this.mono()-w.completedAt>=0&&this.mono()-w.completedAt<=MONITOR.maxGapMs,'retired_witness_missing');replaced.add(service);}
    for(const service of SERVICES)need(current.filter(c=>c.Config.Labels['com.docker.compose.service']===service&&c.State.Running).length<=1,'duplicate_running_service');
    for(const c of pre){if(['broker','postgres'].includes(c.Config.Labels['com.docker.compose.service'])){const p=present.get(c.Id);need(c.State.Running&&p?.State.Running&&p.State.Pid===c.State.Pid,'backend_lifecycle_changed');}}
    for(const c of current){const service=c.Config.Labels['com.docker.compose.service'];need(before.has(c.Id)||replaced.has(service),'replacement_identity');if(before.has(c.Id))need(before.get(c.Id).Config.Labels['com.docker.compose.service']===service,'inventory_receipt_conflict');}
    need(['broker','postgres'].every(service=>current.filter(c=>c.Config.Labels['com.docker.compose.service']===service).length===1),'backend_lifecycle_changed');
    return {current,retiredIds:missing.map(c=>c.Id)};
  }
  async coherentStats(initial) {
    if(!initial.length)return {containers:initial,processes:[],rounds:0,discardedTransitions:[]};
    let pre=initial;const discardedTransitions=[];
    for(let round=1;round<=2;round++){
      this.statsDiagnostic={round,endpoints:[]};await observed('stats_validate',()=>{for(const c of pre)this.verifyEndpoint(c);});
      const stats=await observed('owned_stats',()=>this.dockerCommand(['stats','--no-stream','--no-trunc','--format','{{json .}}',...pre.map(c=>c.Id)]));
      let post;
      try{post=await observed('stats_post_inspect',async()=>{const rows=await this.inspectIds(pre.map(c=>c.Id));for(const c of rows)this.verifyEndpoint(c);return rows;});}
      catch(e){
        if(e.message!=='docker_measurement_failed'||round!==1)throw e;
        const replacement=await observed('stats_validate',()=>this.retiredReplacement(pre,lines(stats.stdout).map(line=>JSON.parse(line))));
        discardedTransitions.push(...replacement.retiredIds);this.statsDiagnostic.retiredIds=replacement.retiredIds;pre=replacement.current;need(this.mono()<=this.deadline,'sample_deadline');continue;
      }
      const result=await observed('stats_validate',()=>{
        const rows=lines(stats.stdout).map(line=>JSON.parse(line)),seen=parsedStats(rows,pre),changed=[];const after=new Map(post.map(c=>[c.Id,c]));
        for(const c of pre){const p=after.get(c.Id),service=c.Config.Labels['com.docker.compose.service'];need(p.Config.Labels['com.docker.compose.service']===service,'inventory_receipt_conflict');const r=seen.get(c.Id),transition=c.State.Running!==p.State.Running||c.State.Pid!==p.State.Pid;
          this.statsDiagnostic.endpoints.push({service,id:c.Id,beforeRunning:c.State.Running,beforePid:c.State.Pid,afterRunning:p.State.Running,afterPid:p.State.Pid,configuredCapBytes:c.HostConfig.Memory,statsPresent:Boolean(r),memoryBytes:r?.memoryBytes??null,reportedCapBytes:r?.reportedCapBytes??null});
          if(transition){need(['runtime','matcher'].includes(service),'backend_lifecycle_changed');if(r)need(r.reportedCapBytes===0||r.reportedCapBytes===c.HostConfig.Memory,'stats_cap_drift');changed.push(c.Id);}else statsProcesses(rows.filter(r=>(r.ID??r.Container)===c.Id),[c]);
        }
        if(changed.length){need(round===1,'stats_lifecycle_unstable');discardedTransitions.push(...changed);return null;}
        return {containers:post,processes:statsProcesses(rows,post),rounds:round,discardedTransitions};
      });
      need(this.mono()<=this.deadline,'sample_deadline');if(result)return result;pre=post;
    }
    throw Error('stats_lifecycle_unstable');
  }
  async collect() {
    this.failedObservation=null;this.failedTiming=null;this.failedDiagnostic=null;this.statsDiagnostic=null;
    try{
    await observed('lease',()=>validateLease(this.lease,this.now()));const start=this.mono(),wallStart=this.now();const gap=this.previousStart===null?start-this.started:start-this.previousStart;this.failedTiming={startGapMs:gap,startedAt:new Date(wallStart).toISOString()};need(gap<=MONITOR.maxGapMs,'sample_gap');this.previousStart=start;this.deadline=(this.sequence===0?this.started:start)+MONITOR.deadlineMs;
    await observed('secrets',()=>this.refreshSecrets());const roots=await observed('roots',()=>this.currentRoots());const receipt=await observed('owned_receipt',()=>this.ownedReceipt());let containers=await observed('inventory',()=>this.inventory());
    await observed('backend_state',()=>{
    for(const service of SERVICES)need(containers.filter(c=>c.Config.Labels['com.docker.compose.service']===service&&c.State.Running).length<=1,'duplicate_running_service');
    });
    const broker=containers.find(c=>c.Config.Labels['com.docker.compose.service']==='broker'&&c.State.Running),pg=containers.find(c=>c.Config.Labels['com.docker.compose.service']==='postgres'&&c.State.Running);await observed('backend_state',()=>{need(!this.backendObserved||broker&&pg,'backend_not_running');need(this.backendObserved||start-this.started<=MONITOR.startupMs,'startup_deadline');});const complete=Boolean(broker&&pg);if(complete)this.backendObserved=true;
    const state=await observed('controller_state',async()=>{
    let state=null;try{state=await privateJson(join(this.lease.proofRoot,'controller-state.json'));}catch(e){if(e.code!=='ENOENT'||containers.some(c=>c.State.Running))throw e;}if(state)need(state.schema==='calcify-finite-o2-controller-state-v1'&&state.project===this.lease.project&&state.sourceRevision===this.lease.sourceRevision,'controller_state_scope');if(containers.some(c=>c.State.Running)){need(roots.includes(this.lease.secretRoot)&&roots.includes(this.lease.stateRoot)&&this.secrets.length>0,'service_secrets_missing');}return state;
    });
    // Ancillary measurements run once alongside up to two stats rounds under one deadline.
    const tasks=[this.coherentStats(containers),observed('backend_du',()=>broker?this.dockerCommand(['exec',broker.Id,'du','-sk','/var/lib/redpanda/data']):Promise.resolve({stdout:'0'})),observed('backend_du',()=>pg?this.dockerCommand(['exec',pg.Id,'du','-sk','/var/lib/postgresql/data']):Promise.resolve({stdout:'0'})),observed('backend_df',()=>(broker||pg)?this.dockerCommand(['exec',(broker??pg).Id,'df','-Pk',broker?'/var/lib/redpanda/data':'/var/lib/postgresql/data']):Promise.resolve({stdout:''})),observed('host_du',()=>this.execute('/usr/bin/du',['-sk',...roots])),observed('controller_ps',()=>state?this.execute('/bin/ps',['-o','pid=,lstart=,rss=','-p',String(state.pid)]):Promise.resolve(null)),observed('proof_scan',()=>treeBytes(this.lease.proofRoot))];
    const results=await Promise.allSettled(tasks);const failed=results.find(r=>r.status==='rejected');if(failed)throw failed.reason;const [statsSampling,bdu,pdu,df,hdu,ps,proofBytes]=results.map(r=>r.value);containers=statsSampling.containers;
    const sample=await observed('resource_validate',()=>{
    need(hdu.exit.code===0&&!hdu.exit.signal,'host_du_failed');
    const duBytes=r=>{const values=lines(r.stdout).map(line=>Number(line.trim().split(/\s+/)[0])*1024);need(values.length>0&&values.every(integer),'du_value');return values.reduce((a,b)=>a+b,0);};
    need(lines(hdu.stdout).length===roots.length,'host_du_shape');
    const guestFreeBytes=(broker||pg)?Number(lines(df.stdout).at(-1)?.trim().split(/\s+/)[3])*1024:null;need(guestFreeBytes===null||integer(guestFreeBytes),'df_value');
    const rss=state?controllerRss(state,ps):{controllerRssBytes:process.memoryUsage().rss,monitorRssBytes:process.memoryUsage().rss,supervisorRssBytes:null,supervisorPid:null,supervisorPending:true};const end=this.mono();
    const sampleData={phase:complete?'complete':'startup',pending:complete?[]:['backend_volume_complete',...(guestFreeBytes===null?['guest_floor']:[])],observedAt:new Date(this.now()).toISOString(),monitorPid:process.pid,leaseProject:this.lease.project,sourceRevision:this.lease.sourceRevision,ownedReceipt:receipt,ownedIds:[...this.known].map(([id,service])=>({id,service,present:containers.some(c=>c.Id===id)})),inspections:containers,hostRoots:roots,allocatedBytes:this.accounting.priorVolumeBytes+this.accounting.logAllowanceBytes+duBytes(bdu)+duBytes(pdu)+duBytes(hdu),guestFreeBytes,proofBytes,...rss,processes:statsSampling.processes,statsSampling:{rounds:statsSampling.rounds,discardedTransitions:statsSampling.discardedTransitions}};const timing={startedAt:new Date(wallStart).toISOString(),endedAt:new Date(this.now()).toISOString(),durationMs:end-start,startGapMs:gap,initializationMs:this.sequence===0?start-this.started:0};
    this.failedObservation={allocatedBytes:sampleData.allocatedBytes,proofBytes,guestFreeBytes,controllerRssBytes:rss.controllerRssBytes,processes:sampleData.processes.map(p=>({service:p.service,id:p.id,memoryBytes:p.memoryBytes,capBytes:p.capBytes})),timing};
    return complete?measuredSample(sampleData,timing):startupSample(sampleData,timing);
    });need(this.mono()<=this.deadline,'sample_deadline');return sample;
    }catch(e){this.failedDiagnostic=failureDetails.get(e)??{stage:'measurements',guard:failureGuard(e.message)};throw e;}
  }
  async receipt(name,data,{exclusive=true}={}) {
    const body=JSON.stringify(data,null,2)+'\n';noSecretSpill(body,this.secrets);need(Buffer.byteLength(body)<=MONITOR.maxOutput,'receipt_size');await writeFile(join(this.lease.proofRoot,name),body,{mode:0o600,flag:exclusive?'wx':'w'});
  }
  async abort(code) {
    // Failure messages may contain external bytes: retain only fixed classifications.
    const accepted=['startup_deadline','sample_deadline','sample_gap','disk abort','proof abort','guest disk floor','controller RSS','memory sample/cap','aggregate memory cap'];const reason=accepted.includes(code)?code:'unsafe_or_missing_measurement';
    const diagnostic=this.failedDiagnostic??{stage:'internal_unknown',guard:failureGuard(code)};
    try{await this.receipt('RESOURCE_ABORT.json',{schema:'calcify-finite-o2-resource-abort-v1',project:this.lease.project,sourceRevision:this.lease.sourceRevision,observedAt:new Date(this.now()).toISOString(),monitorPid:process.pid,reason,lastCompletedSample:this.sequence,failedSample:this.sequence+1,diagnostic,statsDiagnostic:this.statsDiagnostic??null,failedTiming:this.failedTiming??null,failedObservation:this.failedObservation??null,lastCompletedObservation:this.lastCompletedObservation??null});}catch(e){if(e.code!=='EEXIST')throw e;}
    // Inventory failure never authorizes blind stops. Each retained ID gets fresh exact inspect.
    try{await this.inventory({cleanup:true});}catch{}
    const cleanup=[];
    for(const service of SERVICES)for(const [id,s]of this.known)if(s===service){try{const r=await this.dockerCommand(['inspect','--format',INSPECT,id],{cleanup:true}),row=JSON.parse(r.stdout);need(row.Id===id,'cleanup_identity');checkOwned(row,this.lease,service,this.expected[service]);const result=await this.dockerCommand(['stop','--time','2',id],{cleanup:true});cleanup.push({service,id,verified:true,stopped:result.exit.code===0});}catch{cleanup.push({service,id,verified:false,stopped:false});}}
    await this.receipt('RESOURCE_ABORT_CLEANUP.json',{schema:'calcify-finite-o2-resource-cleanup-v1',project:this.lease.project,observedAt:new Date(this.now()).toISOString(),cleanup,preservedVolumes:true});return cleanup;
  }
  async stopRequested() {try{await canonical(join(this.lease.proofRoot,'RESOURCE_MONITOR_STOP'),{privateFile:true});return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}}
  async runLoop() {
    // An existing monitor owner is not a resource failure and must not stop its lease.
    const root=await canonical(this.lease.proofRoot);need(root.isDirectory()&&root.uid===process.getuid()&&(root.mode&0o077)===0,'private_proof_root');
    await this.receipt('RESOURCE_MONITOR_OWNER.json',{schema:'calcify-finite-o2-monitor-owner-v1',project:this.lease.project,sourceRevision:this.lease.sourceRevision,pid:process.pid,processStartedAt:new Date(Date.now()-process.uptime()*1000).toISOString()});
    try{await observed('initialize',()=>this.initialize());while(true){try{await canonical(join(this.lease.proofRoot,'RESOURCE_ABORT.json'),{privateFile:true});throw Error('existing_resource_abort');}catch(e){if(e.code!=='ENOENT')throw e;}if(await this.stopRequested()){await this.receipt('RESOURCE_MONITOR_STOPPED.json',{schema:'calcify-finite-o2-monitor-stopped-v1',project:this.lease.project,monitorPid:process.pid,observedAt:new Date(this.now()).toISOString(),lastCompletedSample:this.sequence});return;}
      const sample=await this.collect(),sampleIndex=this.sequence+1;await observed('sample_receipt',async()=>{await this.receipt('RESOURCE_SAMPLE_'+String(sampleIndex).padStart(6,'0')+'.json',sample);need(this.mono()<=this.deadline,'sample_deadline');});this.completedSample(sample,sampleIndex);if(this.sequence===1)await this.receipt('RESOURCE_MONITOR_ARMED.json',{schema:'calcify-finite-o2-monitor-armed-v1',project:this.lease.project,sourceRevision:this.lease.sourceRevision,monitorPid:process.pid,observedAt:new Date(this.now()).toISOString(),firstSample:'RESOURCE_SAMPLE_000001.json',startupDeadlineMs:MONITOR.startupMs,fullResourceProof:sample.phase==='complete',pending:sample.pending});
      const wait=Math.max(0,MONITOR.periodMs-(this.mono()-this.previousStart));await new Promise(r=>setTimeout(r,wait));}
    }catch(e){this.failedDiagnostic??=failureDetails.get(e)??{stage:'monitor_loop',guard:failureGuard(e.message)};await this.abort(e.message);throw Error('resource_monitor_aborted');}
  }
}
if(import.meta.url===pathToFileURL(process.argv[1]??'').href){
  try{need(process.argv.length===3,'lease_path_required');const path=resolve(process.argv[2]);await canonical(path,{privateFile:true});const monitor=new ResourceMonitor(await privateJson(path));await monitor.runLoop();}catch{console.error('finite O2 resource monitor stopped; inspect owned abort receipts');process.exitCode=1;}
}
