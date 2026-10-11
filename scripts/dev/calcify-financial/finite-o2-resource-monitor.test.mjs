import { test } from 'bun:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { ResourceMonitor, MONITOR, INSPECT, dedupRoots, monitorLease, checkLogs, memoryBytes, statsProcesses, controllerRss, measuredSample, startupSample } from './finite-o2-resource-monitor.mjs';
import { Supervisor, RP_IMAGE, PG_IMAGE, checkOwned, LIMITS } from './finite-o2-proof.mjs';
const id = n => String(n).repeat(64), image='sha256:'+id(9);
const SERVICES_FOR_TEST=['runtime','matcher','postgres','broker'];
const result = (stdout='',code=0,stderr='',signal=null) => ({stdout,stderr,exit:{code,signal}});
async function fixture(){
  const root=await mkdtemp('/private/tmp/calcify-o2-monitor-test-'),proof=join(root,'calcify-o2-monitor-test-proof');await mkdir(proof,{mode:0o700});
  const artifacts=join(root,'artifacts');await mkdir(artifacts);for(const n of ['matcher','probe'])await writeFile(join(artifacts,n),'tiny');
  const lease={schema:'calcify-finite-o2-lease-v1',project:'calcify-o2-monitor-test',prefix:'calcify-o2-monitor-test',scope:'full',expiresAt:new Date(Date.now()+60000).toISOString(),sourceRevision:'a'.repeat(40),networkSubnet:'10.253.0.32/28',proofRoot:proof,stateRoot:join(root,'calcify-o2-monitor-test-state'),secretRoot:join(root,'calcify-o2-monitor-test-secrets'),images:{broker:RP_IMAGE,postgres:PG_IMAGE,runtime:image,matcher:image},artifacts:{sourceManifest:join(root,'manifest'),distribution:artifacts,matcherBinary:join(artifacts,'matcher'),probeBinary:join(artifacts,'probe')},artifactRoots:[artifacts],retainedLedger:{schema:'calcify-finite-o2-retained-ledger-v1',observedAt:new Date().toISOString(),volumes:[],hostRoots:[],logAllowanceBytes:0,allocatedBytes:0}};
  return {root,lease,close:()=>rm(root,{recursive:true,force:true})};
}
function container(lease,service,n,running=true){
  return {Id:id(n),Image:image,State:{Running:running,Pid:running?n:0,Status:running?'running':'exited'},Config:{Labels:{'reef.test':'calcify-finite-o2','com.docker.compose.project':lease.project,'com.docker.compose.service':service}},HostConfig:{Memory:{broker:2048,postgres:512,runtime:1024,matcher:128}[service]*1024**2,NanoCpus:{broker:2,postgres:1,runtime:2,matcher:1}[service]*1e9,RestartPolicy:{Name:'no'},Privileged:false,ReadonlyRootfs:true,LogConfig:{Type:'json-file',Config:{'max-size':'1m','max-file':'2'}}},Mounts:[],NetworkSettings:{Ports:{},Networks:{[lease.project+'-private']:{}}}};
}
const base=()=>({allocatedBytes:1,proofBytes:1,guestFreeBytes:20*1024**3,controllerRssBytes:1,processes:[{service:'broker',memoryBytes:1,capBytes:2048*1024**2}]});
const timing=()=>({durationMs:1,startGapMs:0});
test('fixed sampling policy and safe inspect omit secrets and process arguments',()=>{assert.equal(MONITOR.periodMs,5000);assert.equal(MONITOR.deadlineMs,4500);assert.equal(MONITOR.maxGapMs,10000);assert.equal(MONITOR.startupMs,30000);assert(!INSPECT.includes('Env'));assert(!INSPECT.includes('Cmd'));assert(!INSPECT.includes('Entrypoint'));});
test('canonical root dedup retains parent once, rejects broad or ambiguous roots',()=>{const root='/private/tmp/calcify-o2-test';assert.deepEqual(dedupRoots([root+'/runtime',root,root]),[root]);for(const p of ['/private/tmp','/private/tmp/../tmp/calcify-o2-test','/Users/dsteele'])assert.throws(()=>dedupRoots([p]));});
test('ledger exact totals and artifact coverage; current volumes replace prior snapshot',async()=>{const f=await fixture();try{const l=f.lease.retainedLedger;l.volumes=[{project:f.lease.project,name:f.lease.project+'-broker-data',allocatedBytes:100},{project:'calcify-o2-old',name:'calcify-o2-old-postgres-data',allocatedBytes:200}];l.allocatedBytes=300;const a=monitorLease(f.lease);assert.equal(a.priorVolumeBytes,200);assert.equal(a.logAllowanceBytes,32*1024**2);l.allocatedBytes++;assert.throws(()=>monitorLease(f.lease),/retained_total/);l.allocatedBytes--;f.lease.artifactRoots=[];assert.throws(()=>monitorLease(f.lease),/artifact_roots_required/);}finally{await f.close();}});
test('batched stats require every running ID, exact cap, no unknown or duplicate ID',async()=>{const f=await fixture();try{const a=container(f.lease,'broker',1),b=container(f.lease,'postgres',2);const rows=[{ID:a.Id,MemUsage:'100MiB / 2GiB'},{ID:b.Id,MemUsage:'20MiB / 512MiB'}];assert.equal(statsProcesses(rows,[a,b])[0].memoryBytes,100*1024**2);assert.throws(()=>statsProcesses(rows.slice(0,1),[a,b]),/stats_missing/);assert.throws(()=>statsProcesses([...rows,rows[0]],[a,b]),/stats_identity/);assert.throws(()=>statsProcesses([{ID:id(3),MemUsage:'0B / 2GiB'}],[a]),/stats_identity/);assert.throws(()=>statsProcesses([{ID:a.Id,MemUsage:'1MiB / 1GiB'}],[a]),/stats_cap_drift/);b.State.Running=false;assert.equal(statsProcesses(rows.slice(0,1),[a,b])[1].capBytes,0);assert.throws(()=>statsProcesses(rows,[a,b]),/stats_state_race/);assert.equal(memoryBytes('1.5MiB'),1572864);}finally{await f.close();}});
test('actual supervisor plus monitor RSS, absent PID, duplicate PID and PID reuse',()=>{const stamp='Sat Oct 10 15:00:00 2026',state={pid:123,processStartedAt:new Date(stamp).toISOString()};assert.equal(controllerRss(state,result('123 '+stamp+' 1000'),{pid:456,rss:20}).controllerRssBytes,1024020);assert.equal(controllerRss(state,result('',1),{pid:456,rss:20}).controllerRssBytes,20);assert.equal(controllerRss(state,result('unused'),{pid:123,rss:20}).controllerRssBytes,20);assert.throws(()=>controllerRss(state,result('123 Sat Oct 10 15:01:00 2026 1000'),{pid:456,rss:20}),/controller_pid_reused/);assert.throws(()=>controllerRss(state,result('124 '+stamp+' 1000'),{pid:456,rss:20}),/controller_ps_identity/);});
test('every resource limit and observed timing breach rejects complete sample',()=>{for(const [key,value]of [['allocatedBytes',LIMITS.diskAbortBytes],['proofBytes',LIMITS.proofBytes],['guestFreeBytes',20*1024**3-1],['controllerRssBytes',LIMITS.controllerBytes+1],['controllerRssBytes',-1]])assert.throws(()=>measuredSample({...base(),[key]:value},timing()));assert.throws(()=>measuredSample(base(),{durationMs:4501,startGapMs:0}),/sample_deadline/);assert.throws(()=>measuredSample(base(),{durationMs:1,startGapMs:10001}),/sample_gap/);assert.throws(()=>measuredSample({...base(),processes:[{memoryBytes:2,capBytes:1}]},timing()),/memory sample/);assert.throws(()=>measuredSample({...base(),processes:[{memoryBytes:1,capBytes:LIMITS.memoryMiB*1024**2-LIMITS.controllerBytes+1}]},timing()),/aggregate memory cap/);assert.match(measuredSample(base(),timing()).scope,/no physical controller cap/);});
test('startup remains explicit pending; still checks actual memory/disk/proof/floor/gap',()=>{const data={...base(),phase:'startup',pending:['backend_volume_complete','guest_floor'],guestFreeBytes:null,processes:[]};const s=startupSample(data,timing());assert.equal(s.schema,'calcify-finite-o2-resource-startup-sample-v1');assert.equal(s.guestFreeBytes,null);assert.throws(()=>startupSample({...data,guestFreeBytes:1},timing()),/guest disk floor/);assert.throws(()=>startupSample({...data,allocatedBytes:LIMITS.diskAbortBytes},timing()),/disk abort/);assert.throws(()=>startupSample(data,{durationMs:4501,startGapMs:0}),/sample_deadline/);});
test('complete and startup share diagnostic256MiB bound and aggregate3968MiB without product cap expansion',()=>{assert.equal(LIMITS.controllerBytes,268435456);assert.equal(LIMITS.memoryMiB,3968);const processes=Object.entries({broker:2048,postgres:512,runtime:1024,matcher:128}).map(([service,mib])=>({service,memoryBytes:mib*1024**2,capBytes:mib*1024**2}));const complete={...base(),controllerRssBytes:LIMITS.controllerBytes,processes},startup={...complete,phase:'startup',pending:['backend_volume_complete'],guestFreeBytes:null};assert.equal(measuredSample(complete,timing()).processes.at(-1).capBytes,LIMITS.controllerBytes);assert.equal(startupSample(startup,timing()).controllerRssBytes,LIMITS.controllerBytes);for(const [sample,validate]of [[complete,measuredSample],[startup,startupSample]]){assert.throws(()=>validate({...sample,controllerRssBytes:LIMITS.controllerBytes+1},timing()),/controller RSS|memory sample\/cap/);assert.throws(()=>validate({...sample,processes:processes.map((p,i)=>i===0?{...p,capBytes:p.capBytes+1}:p)},timing()),/aggregate memory cap/);assert.throws(()=>validate({...sample,processes:processes.map((p,i)=>i===3?{...p,memoryBytes:p.capBytes+1}:p)},timing()),/memory sample\/cap/);}});
test('no-ID initial bootstrap samples own RSS and host roots without fabricated backend proof',async()=>{const f=await fixture(),calls=[];try{const run=async(executable,argv)=>{calls.push([executable,argv]);if(argv[0]==='image')return result([1,2,9,9].map(n=>JSON.stringify('sha256:'+id(n))).join('\n'));if(argv[0]==='ps')return result('');if(executable==='/usr/bin/du')return result(argv.slice(1).map(p=>'4\t'+p).join('\n'));throw Error('unexpected_command');};const m=new ResourceMonitor(f.lease,{run});await m.initialize();const s=await m.collect();assert.equal(s.phase,'startup');assert.deepEqual(s.pending,['backend_volume_complete','guest_floor']);assert.equal(s.supervisorPending,true);assert.equal(s.ownedReceipt,null);assert(!calls.some(([,argv])=>argv[0]==='stats'));assert(!calls.some(([exe])=>exe==='/bin/ps'));}finally{await f.close();}});
test('cleanup never stops unverified labels or mismatched ID; valid stops ordered and volumes retained',async()=>{const f=await fixture(),calls=[];try{const rows=new Map(SERVICES_FOR_TEST.map((s,i)=>[id(i+1),container(f.lease,s,i+1)]));rows.get(id(2)).Config.Labels['com.docker.compose.project']='wrong-project';const run=async(exe,argv)=>{calls.push(argv);if(argv[0]==='ps')return result('');if(argv[0]==='inspect')return result(JSON.stringify(rows.get(argv.at(-1))));if(argv[0]==='stop')return result(argv.at(-1));throw Error('unexpected_command');};const m=new ResourceMonitor(f.lease,{run});m.expected=Object.fromEntries(SERVICES_FOR_TEST.map(s=>[s,image]));for(const [i,s]of SERVICES_FOR_TEST.entries())m.known.set(id(i+1),s);const cleanup=await m.abort('external-secret-value');assert.deepEqual(calls.filter(a=>a[0]==='stop').map(a=>a.at(-1)),[id(1),id(3),id(4)]);assert.equal(cleanup.find(r=>r.id===id(2)).verified,false);assert(!calls.some(a=>a.includes('rm')||a.includes('volume')));const abort=await readFile(join(f.lease.proofRoot,'RESOURCE_ABORT.json'),'utf8');assert(!abort.includes('external-secret-value'));assert.match(abort,/unsafe_or_missing_measurement/);}finally{await f.close();}});
test('secret output rejected before receipt, private stop request and wrapper abort fail closed',async()=>{const f=await fixture();try{const m=new ResourceMonitor(f.lease,{run:async()=>result('TEST_PRIVATE_SECRET')});m.secrets=['TEST_PRIVATE_SECRET'];m.deadline=performance.now()+4000;await assert.rejects(()=>m.execute('/bin/ps',[]),/secret/);await assert.rejects(()=>m.receipt('test.json',{raw:'TEST_PRIVATE_SECRET'}),/secret/);await writeFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_STOP'),'stop',{mode:0o600});assert.equal(await m.stopRequested(),true);const calls=[];m.initialize=async()=>{};m.stopRequested=async()=>false;m.collect=async()=>{throw Error('sample_gap');};m.abort=async code=>calls.push(code);await assert.rejects(()=>m.runLoop(),/resource_monitor_aborted/);assert.deepEqual(calls,['sample_gap']);}finally{await f.close();}});
test('ownership verifier enforces actual CPU, network, image and secret mount constraints',async()=>{const f=await fixture();try{for(const change of [c=>c.HostConfig.NanoCpus=1,c=>c.Image='sha256:'+id(8),c=>c.NetworkSettings.Networks={wrong:{}},c=>c.Mounts=[{Type:'bind',Source:f.lease.secretRoot,Destination:'/secret',RW:false}]]){const c=container(f.lease,'broker',1);change(c);assert.throws(()=>checkOwned(c,f.lease,'broker',image));}}finally{await f.close();}});

test('discovery before receipt, two to four services, replacement ID and one stats call per sample',async()=>{
  const f=await fixture(),calls=[];try{
    await mkdir(f.lease.stateRoot);await mkdir(f.lease.secretRoot,{mode:0o700});await writeFile(join(f.lease.secretRoot,'matcher-password'),'TEST_PRIVATE_SECRET\n',{mode:0o600});
    await writeFile(join(f.lease.proofRoot,'controller-state.json'),JSON.stringify({schema:'calcify-finite-o2-controller-state-v1',project:f.lease.project,sourceRevision:f.lease.sourceRevision,pid:process.pid,processStartedAt:new Date(Date.now()-process.uptime()*1000).toISOString()}),{mode:0o600});
    let rows=[container(f.lease,'broker',1),container(f.lease,'postgres',2)];
    const run=async(executable,argv)=>{calls.push([executable,argv]);if(executable==='/usr/bin/du')return result(argv.slice(1).map(p=>'4\t'+p).join('\n'));if(executable==='/bin/ps')return result('');if(argv[0]==='image')return result([image,image,image,image].map(JSON.stringify).join('\n'));if(argv[0]==='ps')return result(rows.map(c=>JSON.stringify({ID:c.Id})).join('\n'));if(argv[0]==='inspect')return result(rows.map(JSON.stringify).join('\n'));if(argv[0]==='stats')return result(rows.filter(c=>c.State.Running).map(c=>JSON.stringify({ID:c.Id,MemUsage:'1MiB / '+({broker:'2GiB',postgres:'512MiB',runtime:'1GiB',matcher:'128MiB'}[c.Config.Labels['com.docker.compose.service']])})).join('\n'));if(argv[0]==='exec')return result(argv[2]==='du'?'4\t'+argv[4]:'Filesystem 1024-blocks Used Available Capacity Mounted\nowned 50000000 1000 40000000 1% /owned');throw Error('unexpected_command');};
    const m=new ResourceMonitor(f.lease,{run});await m.initialize();const first=await m.collect();assert.equal(first.phase,'complete');assert.equal(first.ownedReceipt,null);assert.equal(first.processes.length,3);assert.equal(first.controllerRssBytes,first.monitorRssBytes);m.sequence++;
    rows.push(container(f.lease,'runtime',3),container(f.lease,'matcher',4));await writeFile(join(f.lease.proofRoot,'0001-owned-resources.json'),JSON.stringify({schema:'calcify-finite-o2-owned-v1',project:f.lease.project,ids:Object.fromEntries(rows.map(c=>[c.Config.Labels['com.docker.compose.service'],c.Id]))}),{mode:0o600});
    const second=await m.collect();assert.equal(second.processes.length,5);assert.equal(second.ownedReceipt,'0001-owned-resources.json');m.sequence++;
    rows[3].State={Running:false,Pid:0,Status:'exited'};rows.push(container(f.lease,'matcher',5));const third=await m.collect();assert.equal(third.ownedIds.length,5);assert.equal(third.processes.filter(p=>p.running).length,4);assert.equal(third.processes.find(p=>p.id===id(4)).capBytes,0);
    const stats=calls.filter(([,argv])=>argv[0]==='stats');assert.equal(stats.length,3);assert.deepEqual(stats[2][1].slice(5),rows.map(c=>c.Id));assert(stats.every(([,argv])=>argv.includes('--no-trunc')&&argv.includes('--no-stream')));
    rows[0].State={Running:false,Pid:0,Status:'exited'};await assert.rejects(()=>m.collect(),/backend_not_running/);
  }finally{await f.close();}
});
test('initialization to first sample arms once, stop handshake follows next iteration',async()=>{
  const f=await fixture();try{const m=new ResourceMonitor(f.lease);let checks=0;m.initialize=async()=>{};m.collect=async()=>{m.previousStart=performance.now()-5000;m.deadline=performance.now()+4500;return startupSample({...base(),phase:'startup',pending:['backend_volume_complete','guest_floor'],guestFreeBytes:null,processes:[]},timing());};m.stopRequested=async()=>++checks===2;await m.runLoop();const armed=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_ARMED.json'),'utf8'));assert.equal(armed.fullResourceProof,false);assert.equal(armed.startupDeadlineMs,30000);assert.deepEqual(armed.pending,['backend_volume_complete','guest_floor']);const stopped=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_STOPPED.json'),'utf8'));assert.equal(stopped.lastCompletedSample,1);}finally{await f.close();}
});
test('actual gap refuses before another measurement and initial deadline cannot be bypassed by late backends',async()=>{
  const f=await fixture();try{let clock=0;const m=new ResourceMonitor(f.lease,{mono:()=>clock});m.previousStart=0;clock=10001;await assert.rejects(()=>m.collect(),/sample_gap/);assert.equal(m.failedTiming.startGapMs,10001);const late=new ResourceMonitor(f.lease,{mono:()=>0});late.mono=()=>31000;late.previousStart=26000;late.refreshSecrets=async()=>{};late.currentRoots=async()=>[];late.ownedReceipt=async()=>null;late.inventory=async()=>[container(f.lease,'broker',1),container(f.lease,'postgres',2)];await assert.rejects(()=>late.collect(),/startup_deadline/);}finally{await f.close();}
});

test('duplicate monitor owner refuses without aborting another controller',async()=>{const f=await fixture();try{await writeFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_OWNER.json'),'{}',{mode:0o600});const m=new ResourceMonitor(f.lease);let cleanup=false;m.abort=async()=>cleanup=true;await assert.rejects(()=>m.runLoop(),e=>e.code==='EEXIST');assert.equal(cleanup,false);}finally{await f.close();}});

test('actual log caps verified and retained-ID allowance covers every bounded container',async()=>{const f=await fixture();try{const c=container(f.lease,'broker',1);checkLogs(c);c.HostConfig.LogConfig.Config['max-file']='3';assert.throws(()=>checkLogs(c),/log_cap_drift/);assert.equal(monitorLease(f.lease).logAllowanceBytes,MONITOR.maxIds*2*1024**2);}finally{await f.close();}});

test('retained volume enumeration supports74 exact items, rejects75 and lost ownership or sum',async()=>{const f=await fixture();try{const l=f.lease.retainedLedger;l.volumes=Array.from({length:74},(_,i)=>{const project='calcify-o2-prior-'+String(Math.floor(i/2)).padStart(2,'0');return {project,name:project+(i%2?'-broker-data':'-postgres-data'),allocatedBytes:i+1};});l.allocatedBytes=l.volumes.reduce((n,v)=>n+v.allocatedBytes,0);assert.equal(monitorLease(f.lease).priorVolumeBytes,2775);l.volumes.push({project:'calcify-o2-extra',name:'calcify-o2-extra-postgres-data',allocatedBytes:1});l.allocatedBytes++;assert.throws(()=>monitorLease(f.lease),/retained_ledger/);l.volumes.pop();l.allocatedBytes--;l.volumes[73].name='unrelated-volume';assert.throws(()=>monitorLease(f.lease),/retained_volume/);}finally{await f.close();}});
test('retained host input cap96 and union96 guard reject97 instead of truncating',async()=>{const f=await fixture();try{const l=f.lease.retainedLedger;l.hostRoots=Array.from({length:92},(_,i)=>({path:'/private/tmp/calcify-o2-retained-root-'+i,allocatedBytes:i}));l.allocatedBytes=l.hostRoots.reduce((n,h)=>n+h.allocatedBytes,0);assert.equal(monitorLease(f.lease).roots.length,96);l.hostRoots.push({path:'/private/tmp/calcify-o2-retained-extra',allocatedBytes:1});l.allocatedBytes++;assert.throws(()=>monitorLease(f.lease),/owned_root_count/);l.hostRoots=Array.from({length:97},(_,i)=>({path:'/private/tmp/calcify-o2-retained-root-'+i,allocatedBytes:0}));l.allocatedBytes=0;assert.throws(()=>monitorLease(f.lease),/retained_ledger/);assert.equal(MONITOR.maxRoots,96);assert.equal(MONITOR.maxIds,16);assert.equal(MONITOR.maxOutput,262144);assert.equal(LIMITS.diskAbortBytes,3*1024**3+640*1024**2);}finally{await f.close();}});

async function samplingFixture({transition=()=>{},statsRows,inspectRows,statsFinish=()=>{},inspectFinish=()=>{},statsExit=0,inspectExit=()=>0}={}) {
  const f=await fixture(),calls=[];let clock=0,statsCalls=0,inspectCalls=0;
  await mkdir(f.lease.stateRoot);await mkdir(f.lease.secretRoot,{mode:0o700});await writeFile(join(f.lease.secretRoot,'matcher-password'),'TEST_PRIVATE_SECRET\n',{mode:0o600});
  await writeFile(join(f.lease.proofRoot,'controller-state.json'),JSON.stringify({schema:'calcify-finite-o2-controller-state-v1',project:f.lease.project,sourceRevision:f.lease.sourceRevision,pid:process.pid,processStartedAt:new Date(Date.now()-process.uptime()*1000).toISOString()}),{mode:0o600});
  const rows=SERVICES_FOR_TEST.map((s,i)=>container(f.lease,s,i+1));
  const normalStats=()=>rows.filter(c=>c.State.Running).map(c=>({ID:c.Id,MemUsage:'1MiB / '+({broker:'2GiB',postgres:'512MiB',runtime:'1GiB',matcher:'128MiB'}[c.Config.Labels['com.docker.compose.service']])}));
  const run=async(executable,argv,options)=>{
    calls.push({executable,argv,timeoutMs:options.timeoutMs});
    if(executable==='/usr/bin/du')return result(argv.slice(1).map(p=>'4\t'+p).join('\n'));
    if(executable==='/bin/ps')return result('');
    if(argv[0]==='ps')return result(rows.map(c=>JSON.stringify({ID:c.Id})).join('\n'));
    if(argv[0]==='inspect'){inspectCalls++;const response=inspectRows?inspectRows(rows,inspectCalls):rows;const text=response.map(c=>JSON.stringify(c)).join('\n');inspectFinish(inspectCalls,n=>clock=n);return result(text,inspectExit(rows,inspectCalls,argv));}
    if(argv[0]==='stats'){statsCalls++;const response=statsRows?statsRows(rows,statsCalls,normalStats()):normalStats();transition(rows,statsCalls);statsFinish(statsCalls,n=>clock=n);return result(typeof response==='string'?response:response.map(c=>JSON.stringify(c)).join('\n'),statsExit);}
    if(argv[0]==='exec')return result(argv[2]==='du'?'4\t'+argv[4]:'Filesystem 1024-blocks Used Available Capacity Mounted\nowned 50000000 1000 40000000 1% /owned');
    throw Error('unexpected_command');
  };
  const m=new ResourceMonitor(f.lease,{run,mono:()=>clock});m.expected=Object.fromEntries(SERVICES_FOR_TEST.map(s=>[s,image]));
  return {...f,m,rows,calls,setClock:n=>clock=n,statsCount:()=>statsCalls};
}

for(const kind of ['stop','stop-zero','start','pid'])test('same-ID '+kind+' during stats retries only stats and endpoint inspect',async()=>{
  const stopping=kind.startsWith('stop');const f=await samplingFixture({transition:(rows,round)=>{if(round!==1)return;const c=rows[0];c.State=stopping?{Running:false,Pid:0,Status:'exited'}:{Running:true,Pid:101,Status:'running'};},statsRows:(rows,round,normal)=>kind==='stop-zero'&&round===1?[{ID:rows[0].Id,MemUsage:'0B / 0B'},...normal.slice(1)]:normal,statsFinish:(round,set)=>set(round*2000)});
  try{if(kind==='start')f.rows[0].State={Running:false,Pid:0,Status:'exited'};const s=await f.m.collect();assert.equal(s.statsSampling.rounds,2);assert.deepEqual(s.statsSampling.discardedTransitions,[f.rows[0].Id]);assert.equal(s.durationMs,4000);assert.equal(f.m.deadline,4500);assert.equal(f.calls.filter(c=>c.argv[0]==='stats').length,2);assert.equal(f.calls.filter(c=>c.argv[0]==='inspect').length,3);assert.equal(f.calls.filter(c=>c.executable==='/usr/bin/du').length,1);assert.equal(f.calls.filter(c=>c.executable==='/bin/ps').length,1);assert.equal(f.calls.filter(c=>c.argv[0]==='exec').length,3);assert.equal(s.processes.find(p=>p.id===f.rows[0].Id).running,!stopping);assert.deepEqual(s.inspections.find(c=>c.Id===f.rows[0].Id).State,f.rows[0].State);}finally{await f.close();}
});

test('stable stopped endpoints accept absent/zero stats with exact configured cap only',async()=>{
  for(const mem of [null,'0B / 0B','0B / 1GiB','0B / 128MiB']){
    const f=await samplingFixture({statsRows:(rows,round,normal)=>mem===null?normal:[...normal,{ID:rows[0].Id,MemUsage:mem}]});
    try{f.rows[0].State={Running:false,Pid:0,Status:'exited'};if(mem==='0B / 128MiB')await assert.rejects(()=>f.m.collect(),/stats_cap_drift/);else{const s=await f.m.collect(),p=s.processes.find(p=>p.id===f.rows[0].Id);assert.equal(p.memoryBytes,0);assert.equal(p.capBytes,0);assert.equal(s.statsSampling.rounds,1);}}finally{await f.close();}
  }
});

for(const kind of ['missing','cap','unknown','duplicate','malformed','units','over-cap','docker','docker-exit'])test('stable '+kind+' stats fail closed without retry',async()=>{
  const f=await samplingFixture({statsExit:kind==='docker-exit'?1:0,statsRows:(rows,round,normal)=>{if(kind==='missing')return normal.slice(1);if(kind==='cap')return [{ID:rows[0].Id,MemUsage:'1MiB / 128MiB'},...normal.slice(1)];if(kind==='unknown')return [...normal,{ID:id(8),MemUsage:'0B / 0B'}];if(kind==='duplicate')return [...normal,normal[0]];if(kind==='malformed')return '{';if(kind==='units')return [{ID:rows[0].Id,MemUsage:'??? / 1GiB'},...normal.slice(1)];if(kind==='over-cap')return [{ID:rows[0].Id,MemUsage:'2GiB / 1GiB'},...normal.slice(1)];if(kind==='docker-exit')return normal;throw Error('TEST_PRIVATE_SECRET external Docker failure');}});
  try{await assert.rejects(()=>f.m.collect());assert.equal(f.statsCount(),1);assert.equal(f.m.failedObservation,null);assert(!JSON.stringify(f.m.failedDiagnostic).includes('TEST_PRIVATE_SECRET'));assert.equal(f.m.failedDiagnostic.stage,kind.startsWith('docker')?'owned_stats':'stats_validate');}finally{await f.close();}
});

for(const kind of ['backend','backend-broker','ownership','configured-cap','log','disappeared','pid-invalid','repeated','over-cap-transition','stable-missing-other','wrong-reported-cap','missing-after-transition'])test('transition '+kind+' remains fatal',async()=>{
  const f=await samplingFixture({transition:(rows,round)=>{
    if(kind==='backend')rows[2].State={Running:false,Pid:0,Status:'exited'};
    else if(kind==='backend-broker')rows[3].State.Pid=999;
    else if(kind==='ownership')rows[0].Config.Labels['com.docker.compose.project']='wrong-project';
    else if(kind==='configured-cap')rows[0].HostConfig.Memory=128*1024**2;
    else if(kind==='log')rows[0].HostConfig.LogConfig.Config['max-file']='3';
    else if(kind==='pid-invalid')rows[0].State.Pid=-1;
    else if(kind==='repeated'||round===1)rows[0].State={Running:true,Pid:100+round,Status:'running'};
  },inspectRows:(rows,count)=>kind==='disappeared'&&count===2?rows.slice(1):rows,statsRows:(rows,round,normal)=>{
    if(kind==='over-cap-transition')return [{ID:rows[0].Id,MemUsage:'2GiB / 1GiB'},...normal.slice(1)];
    if(kind==='stable-missing-other')return normal.filter(r=>r.ID!==rows[1].Id);
    if(kind==='wrong-reported-cap')return [{ID:rows[0].Id,MemUsage:'1MiB / 128MiB'},...normal.slice(1)];return normal;
  }});
  try{if(kind==='missing-after-transition'){const original=f.m.run;f.m.run=async(exe,argv,options)=>{const r=await original(exe,argv,options);if(argv[0]==='stats'&&f.statsCount()===2)r.stdout=r.stdout.split('\n').slice(1).join('\n');return r;};}await assert.rejects(()=>f.m.collect());assert.equal(f.statsCount(),['repeated','missing-after-transition'].includes(kind)?2:1);assert.equal(f.m.failedObservation,null);assert.notEqual(f.m.failedDiagnostic.guard,'internal_unknown');}finally{await f.close();}
});

for(const finish of [4499,4500,4501])test('one retry preserves original deadline at'+finish+'ms',async()=>{
  const f=await samplingFixture({transition:(rows,round)=>{if(round===1)rows[0].State.Pid=100;},statsFinish:(round,set)=>set(round===1?2000:4400),inspectFinish:(count,set)=>{if(count===3)set(finish);}});
  try{if(finish<=4500){const s=await f.m.collect();assert.equal(s.durationMs,finish);}else await assert.rejects(()=>f.m.collect(),/sample_deadline/);assert.equal(f.m.deadline,4500);assert.equal(f.statsCount(),2);const retry=f.calls.filter(c=>c.argv[0]==='stats')[1];assert.equal(retry.timeoutMs,2500);}finally{await f.close();}
});

for(const gap of [10000,10001])test('actual collect gap '+gap+'ms keeps fixed policy',async()=>{
  const f=await samplingFixture();try{f.m.sequence=1;f.m.previousStart=0;f.setClock(gap);if(gap===10000){const s=await f.m.collect();assert.equal(s.startGapMs,gap);}else{await assert.rejects(()=>f.m.collect(),/sample_gap/);assert.equal(f.statsCount(),0);}}finally{await f.close();}
});

test('failed round clears old observation; abort retains explicit historical observation and safe guard',async()=>{
  const f=await samplingFixture({statsRows:(rows,round,normal)=>round===1?normal:normal.slice(1)});
  try{await f.m.collect();f.m.sequence=1;f.m.lastCompletedObservation={sample:1,...f.m.failedObservation};const history=structuredClone(f.m.lastCompletedObservation);await assert.rejects(()=>f.m.collect(),/stats_missing/);assert.equal(f.m.failedObservation,null);assert.deepEqual(f.m.lastCompletedObservation,history);f.m.inventory=async()=>[];f.m.known.clear();await f.m.abort('stats_missing');const a=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_ABORT.json'),'utf8'));assert.equal(a.failedObservation,null);assert.equal(a.failedSample,2);assert.deepEqual(a.lastCompletedObservation,history);assert.deepEqual(a.diagnostic,{stage:'stats_validate',guard:'stats_missing'});assert.equal(a.statsDiagnostic.round,1);assert.equal(a.statsDiagnostic.endpoints[0].statsPresent,false);}finally{await f.close();}
});

test('external secret-bearing exception emits fixed classification only in abort receipt',async()=>{
  const f=await samplingFixture({statsRows:()=>{throw Error('TEST_PRIVATE_SECRET external exception');}});
  try{await assert.rejects(()=>f.m.collect());f.m.inventory=async()=>[];f.m.known.clear();await f.m.abort('TEST_PRIVATE_SECRET external exception');const text=await readFile(join(f.lease.proofRoot,'RESOURCE_ABORT.json'),'utf8'),a=JSON.parse(text);assert(!text.includes('TEST_PRIVATE_SECRET'));assert.deepEqual(a.diagnostic,{stage:'owned_stats',guard:'internal_unknown'});assert.equal(a.reason,'unsafe_or_missing_measurement');}finally{await f.close();}
});

for(const finish of [4500,4501])test('receipt publication at'+finish+'ms advances completed history only within deadline',async()=>{
  const f=await samplingFixture();try{
    const history={sample:1,allocatedBytes:1};f.m.sequence=1;f.m.lastCompletedObservation=history;f.m.initialize=async()=>{};let checks=0;f.m.stopRequested=async()=>++checks===2;f.m.inventory=async()=>[];f.m.known.clear();
    f.m.collect=async()=>{f.m.failedObservation={allocatedBytes:2};f.m.previousStart=-5000;f.m.deadline=4500;return measuredSample(base(),timing());};
    const receipt=f.m.receipt.bind(f.m);f.m.receipt=async(name,data,options)=>{await receipt(name,data,options);if(name.startsWith('RESOURCE_SAMPLE_'))f.setClock(finish);};
    if(finish===4500){await f.m.runLoop();assert.equal(f.m.sequence,2);assert.deepEqual(f.m.lastCompletedObservation,{sample:2,allocatedBytes:2});}
    else{await assert.rejects(()=>f.m.runLoop(),/resource_monitor_aborted/);assert.equal(f.m.sequence,1);assert.deepEqual(f.m.lastCompletedObservation,history);const a=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_ABORT.json'),'utf8'));assert.equal(a.lastCompletedSample,1);assert.equal(a.failedSample,2);assert.deepEqual(a.diagnostic,{stage:'sample_receipt',guard:'sample_deadline'});}
    assert.equal(JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_SAMPLE_000002.json'),'utf8')).schema,'calcify-finite-o2-resource-sample-v1');
  }finally{await f.close();}
});

async function replacementFixture(kind='valid',finish=4000) {
  const f=await samplingFixture({inspectExit:(rows,count,argv)=>argv.slice(3).some(id=>!rows.some(c=>c.Id===id))?1:0,transition:(rows,count)=>{
    if(count===2){const old=rows.shift(),replacement=container(f.lease,'runtime',6);rows.push(replacement);
      if(kind==='backend')rows.find(c=>c.Config.Labels['com.docker.compose.service']==='broker').State.Pid=999;
      if(kind==='duplicate')rows.push(container(f.lease,'runtime',7));
      if(kind==='image')replacement.Image='sha256:'+id(8);
      if(kind==='log')replacement.HostConfig.LogConfig.Config['max-file']='3';
      if(kind==='ownership')replacement.Config.Labels['com.docker.compose.project']='wrong-project';
      if(kind==='unrelated')rows.splice(rows.findIndex(c=>c.Config.Labels['com.docker.compose.service']==='matcher'),1);
      if(kind==='repeat')f.removed=old;
    }else if(count===3&&kind==='repeat')rows.splice(rows.findIndex(c=>c.Id===id(6)),1);
  },statsRows:(rows,count,normal)=>{
    if(count===2&&kind==='malformed-first')return '{';
    if(count===2&&kind==='cap-first')return [...normal,{ID:id(1),MemUsage:'0B / 128MiB'}];
    if(count===2&&kind==='memory-first')return [...normal,{ID:id(1),MemUsage:'2GiB / 1GiB'}];
    if(count===2&&kind==='unknown-first')return [...normal,{ID:id(8),MemUsage:'0B / 0B'}];
    if(count===3&&kind==='missing-current')return normal.filter(r=>r.ID!==id(6));return normal;
  },statsFinish:(count,set)=>{if(count>=2)set(count===2?2000:4400);},inspectFinish:(count,set)=>{if(count===6)set(finish);}});
  f.rows[0].State={Running:false,Pid:0,Status:'exited'};const stopped=await f.m.collect();
  await f.m.receipt('RESOURCE_SAMPLE_000001.json',stopped);f.m.completedSample(stopped,1);f.setClock(0);return f;
}

for(const finish of [4000,4499,4500,4501])test('own published stopped witness permits one retired-ID resample at'+finish+'ms',async()=>{
  const f=await replacementFixture('valid',finish);try{
    assert.equal(f.m.retiredWitness.get(id(1)).sample,1);if(finish<=4500){const s=await f.m.collect();assert.equal(s.statsSampling.rounds,2);assert.deepEqual(s.statsSampling.discardedTransitions,[id(1)]);assert(s.ownedIds.some(p=>p.id===id(1)&&!p.present));assert(s.processes.some(p=>p.id===id(6)&&p.running));assert.equal(s.durationMs,finish);}else await assert.rejects(()=>f.m.collect(),/sample_deadline/);
    assert.equal(f.statsCount(),3);assert.equal(f.m.deadline,4500);assert.equal(f.calls.filter(c=>c.executable==='/usr/bin/du').length,2);assert.equal(f.calls.filter(c=>c.argv[0]==='exec').length,6);
  }finally{await f.close();}
});

for(const kind of ['backend','duplicate','image','log','ownership','unrelated','repeat','malformed-first','cap-first','memory-first','unknown-first','missing-current','historical-running','no-witness','expired-witness'])test('retired-ID '+kind+' fails closed',async()=>{
  const f=await replacementFixture(kind);try{
    if(kind==='historical-running')f.rows[0].State={Running:true,Pid:111,Status:'running'};
    if(kind==='no-witness')f.m.retiredWitness.clear();
    if(kind==='expired-witness')f.m.retiredWitness.get(id(1)).completedAt=-10001;
    await assert.rejects(()=>f.m.collect());assert.equal(f.m.failedObservation,null);assert.equal(f.statsCount(),['repeat','missing-current'].includes(kind)?3:2);if(kind==='historical-running')assert(!f.m.retiredWitness.has(id(1)));assert(!JSON.stringify(f.m.failedDiagnostic).includes('TEST_PRIVATE_SECRET'));
  }finally{await f.close();}
});

test('unpublished stopped sample creates no retirement witness and running observation invalidates old witness',async()=>{
  const f=await samplingFixture();try{f.rows[0].State={Running:false,Pid:0,Status:'exited'};const s=await f.m.collect();assert(!f.m.retiredWitness.has(id(1)));await f.m.receipt('RESOURCE_SAMPLE_000001.json',s);f.m.completedSample(s,1);assert(f.m.retiredWitness.has(id(1)));f.rows[0].State={Running:true,Pid:123,Status:'running'};await f.m.collect();assert(!f.m.retiredWitness.has(id(1)));}finally{await f.close();}
});

test('initial inventory inspect failure keeps fail-closed even with historical stopped witness',async()=>{
  const f=await replacementFixture();try{const original=f.m.run;f.m.run=async(exe,argv,options)=>argv[0]==='inspect'?result('',1):original(exe,argv,options);const before=f.statsCount();await assert.rejects(()=>f.m.collect(),/docker_measurement_failed/);assert.equal(f.statsCount(),before);assert.equal(f.m.failedDiagnostic.stage,'inventory_inspect');}finally{await f.close();}
});

async function independentLifecycleFixture() {
  const f=await fixture();await mkdir(f.lease.stateRoot);await mkdir(f.lease.secretRoot,{mode:0o700});await writeFile(join(f.lease.secretRoot,'matcher-password'),'TEST_PRIVATE_SECRET\n',{mode:0o600});
  const initial=SERVICES_FOR_TEST.map((s,i)=>container(f.lease,s,i+1)),moduleURL=new URL('./finite-o2-resource-monitor.mjs',import.meta.url).href;
  const childCode=`
    import { ResourceMonitor } from ${JSON.stringify(moduleURL)};
    import { spawnSync } from 'node:child_process';
    const lease=${JSON.stringify(f.lease)},rows=${JSON.stringify(initial)},image=${JSON.stringify(image)};
    const result=(stdout='',code=0)=>({stdout,stderr:'',exit:{code,signal:null}}),mutations=[],calls=[];let ungated=0,pidGeneration=100;
    function mutate(service,running){const c=rows.find(c=>c.Config.Labels['com.docker.compose.service']===service);c.State={Running:running,Pid:running?++pidGeneration:0,Status:running?'running':'exited'};mutations.push({service,running,at:Date.now()});}
    process.on('message',message=>{if(message.command==='mutate')mutate(message.service,message.running);if(message.command==='ungated')ungated=1;if(message.request)process.send({request:message.request,rows,mutations,calls});});
    const run=async(executable,argv,options)=>{
      calls.push({executable,argv,timeoutMs:options.timeoutMs});
      if(executable==='/usr/bin/du')return result(argv.slice(1).map(p=>'4\\t'+p).join('\\n'));
      if(executable==='/bin/ps'){const birth=spawnSync(executable,['-o','lstart=','-p',argv.at(-1)],{encoding:'utf8'});return birth.status===0?result(argv.at(-1)+' '+birth.stdout.trim()+' 1024'):result('',1);}
      if(argv[0]==='image')return result(Array.from({length:argv.length-4},()=>JSON.stringify(image)).join('\\n'));
      if(argv[0]==='ps')return result(rows.map(c=>JSON.stringify({ID:c.Id})).join('\\n'));
      if(argv[0]==='inspect')return result(rows.filter(c=>argv.slice(3).includes(c.Id)).map(c=>JSON.stringify(c)).join('\\n'));
      if(argv[0]==='stats'){const before=rows.filter(c=>c.State.Running).map(c=>({ID:c.Id,MemUsage:'1MiB / '+({broker:'2GiB',postgres:'512MiB',runtime:'1GiB',matcher:'128MiB'}[c.Config.Labels['com.docker.compose.service']])}));await new Promise(r=>setTimeout(r,80));if(ungated===1){mutate('runtime',false);ungated=2;}else if(ungated===2){mutate('matcher',false);ungated=0;}return result(before.map(c=>JSON.stringify(c)).join('\\n'));}
      if(argv[0]==='exec')return result(argv[2]==='du'?'4\\t'+argv[4]:'Filesystem 1024-blocks Used Available Capacity Mounted\\nowned 50000000 1000 40000000 1% /owned');
      if(argv[0]==='stop'){const c=rows.find(c=>c.Id===argv.at(-1));c.State={Running:false,Pid:0,Status:'exited'};return result(c.Id);}
      throw Error('unexpected_command');
    };
    const monitor=new ResourceMonitor(lease,{run}),receipt=monitor.receipt.bind(monitor);
    monitor.receipt=async(name,data,options)=>{await receipt(name,data,options);if(name==='RESOURCE_MONITOR_ARMED.json')process.send({event:'armed',pid:process.pid});if(name.startsWith('RESOURCE_SAMPLE_'))process.send({event:'published',name,startedAt:data.startedAt});};
    try{await monitor.runLoop();process.send({event:'done',mutations,calls});}catch(e){process.send({event:'aborted',error:e.message,mutations,calls});}
  `;
  // Bun test parses dates in UTC; real ps and independent child use same timezone.
  const child=spawn(process.execPath,['--eval',childCode],{env:{...process.env,TZ:'UTC'},stdio:['ignore','pipe','pipe','ipc']}),requests=new Map(),events=[];let requestNumber=0,stderr='';child.stderr.on('data',b=>stderr+=b);child.stdout.resume();
  let armedResolve,armedReject,abortResolve;const armed=new Promise((resolve,reject)=>{armedResolve=resolve;armedReject=reject;}),aborted=new Promise(resolve=>abortResolve=resolve);
  child.on('message',message=>{events.push(message);if(message.request){requests.get(message.request)?.(message);requests.delete(message.request);}if(message.event==='armed')armedResolve(message);if(message.event==='aborted')abortResolve(message);});
  child.on('exit',(code,signal)=>{if(!events.some(e=>e.event==='armed'))armedReject(Error('independent monitor failed '+code+' '+signal+' '+stderr));});
  const request=message=>new Promise((resolve,reject)=>{const number=++requestNumber;requests.set(number,resolve);child.send({...message,request:number},error=>{if(error){requests.delete(number);reject(error);}});});
  const s=new Supervisor(f.lease,{run:async(executable,argv,options)=>{
    if(executable==='/bin/ps'){const p=spawnSync(executable,argv,{encoding:'utf8',env:options.env}),owner=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_OWNER.json'),'utf8'));assert.equal(p.status,0,p.error?.message);const actual=p.stdout.trim().match(/^(\d+)\s+(.+)$/);assert(actual,'real monitor ps shape');assert(Math.abs(Date.parse(actual[2])-Date.parse(owner.processStartedAt))<=3000,JSON.stringify({ps:p.stdout,owner:owner.processStartedAt}));return {stdout:p.stdout,stderr:p.stderr,exit:{code:p.status,signal:p.signal}};}
    if(argv[0]==='image')return result(JSON.stringify(image));
    if(argv[0]==='inspect'){const state=await request({command:'read'});return result(state.rows.filter(c=>argv.slice(3).includes(c.Id)).map(c=>JSON.stringify(c)).join('\n'));}
    throw Error('unexpected_supervisor_command');
  }});s.env.TZ='UTC';s.ids=Object.fromEntries(initial.map(c=>[c.Config.Labels['com.docker.compose.service'],c.Id]));await s.registerController();
  let armedTimer;try{await Promise.race([armed,new Promise((resolve,reject)=>{armedTimer=setTimeout(()=>reject(Error('independent monitor ARM timeout '+stderr)),5000);})]);}catch(e){child.kill('SIGKILL');await f.close();throw e;}finally{clearTimeout(armedTimer);}
  return {...f,s,child,events,request,aborted,close:async()=>{const exited=new Promise(resolve=>child.once('exit',resolve));child.kill('SIGKILL');await exited;await f.close();}};
}

test('actual independent publication reproduces ungated double-stop abort and blocks next mutation',async()=>{
  const f=await independentLifecycleFixture();try{
    const owner=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_MONITOR_OWNER.json'),'utf8'));assert.equal(owner.pid,f.child.pid);assert.notEqual(owner.pid,process.pid);
    await f.request({command:'ungated'});const failed=await Promise.race([f.aborted,new Promise((resolve,reject)=>setTimeout(()=>reject(Error('ungated abort timeout')),10000))]);
    const abort=JSON.parse(await readFile(join(f.lease.proofRoot,'RESOURCE_ABORT.json'),'utf8'));assert.equal(abort.diagnostic.guard,'stats_lifecycle_unstable');assert.equal(abort.statsDiagnostic.round,2);assert.equal(abort.lastCompletedSample,1);assert.equal(abort.failedSample,2);assert.deepEqual(failed.mutations.map(m=>[m.service,m.running]),[['runtime',false],['matcher',false]]);
    const matcher=abort.statsDiagnostic.endpoints.find(e=>e.service==='matcher');assert.equal(matcher.beforeRunning,true);assert.equal(matcher.afterRunning,false);assert.equal(matcher.afterPid,0);
    await assert.rejects(async()=>{await f.s.appLifecycleWitness('runtime',{running:false});await f.request({command:'mutate',service:'runtime',running:true});},/resource monitor aborted lease|monitor aborted or stopped/);
    const state=await f.request({command:'read'});assert.equal(state.mutations.length,2);assert(!f.events.some(e=>e.event==='published'&&e.name==='RESOURCE_SAMPLE_000002.json'));
  }finally{await f.close();}
},20000);

test('actual app barriers serialize distinct published stopped and running samples before next mutation',async()=>{
  const f=await independentLifecycleFixture();try{
    const witnesses=[];for(const [service,running]of [['runtime',false],['matcher',false],['runtime',true],['matcher',true]]){
      const before=await f.request({command:'mutate',service,running}),mutation=before.mutations.at(-1);const pending=f.s.appLifecycleWitness(service,{running});
      await new Promise(resolve=>setTimeout(resolve,120));assert.equal((await f.request({command:'read'})).mutations.length,before.mutations.length);
      const receipt=await pending,witness=JSON.parse(await readFile(receipt.path,'utf8')),sample=witness.sample.body;witnesses.push(witness);
      assert.equal(witness.service,service);assert.equal(witness.running,running);assert.equal(sample.phase,'complete');assert.deepEqual(sample.pending,[]);assert.equal(sample.monitorPid,f.child.pid);assert.notEqual(sample.monitorPid,process.pid);assert(Date.parse(sample.startedAt)>=Date.parse(witness.requestedAt));assert(Date.parse(witness.requestedAt)>=mutation.at);assert(sample.durationMs<=4500);assert(sample.startGapMs<=10000);assert.equal(sample.statsSampling.rounds,1);
      const observed=sample.inspections.find(c=>c.Config.Labels['com.docker.compose.service']===service),measured=sample.processes.find(p=>p.id===observed.Id);assert.equal(observed.State.Running,running);assert.equal(measured.running,running);assert(running?observed.State.Pid>0:observed.State.Pid===0);assert.equal(measured.capBytes,running?observed.HostConfig.Memory:0);
      assert.deepEqual(JSON.parse(await readFile(witness.sample.path,'utf8')),sample);
    }
    assert.deepEqual(witnesses.map(w=>w.sample.path.split('/').at(-1)),['RESOURCE_SAMPLE_000002.json','RESOURCE_SAMPLE_000003.json','RESOURCE_SAMPLE_000004.json','RESOURCE_SAMPLE_000005.json']);
    const starts=witnesses.map(w=>Date.parse(w.sample.body.startedAt));assert(starts.every((n,i)=>i===0||n>starts[i-1]));
    assert.equal(MONITOR.periodMs,5000);assert.equal(MONITOR.deadlineMs,4500);assert.equal(MONITOR.maxGapMs,10000);assert.equal(LIMITS.diskAbortBytes,3*1024**3+640*1024**2);assert.equal(LIMITS.memoryMiB,3968);assert.equal(LIMITS.controllerBytes,268435456);
    const state=await f.request({command:'read'});assert.equal(state.mutations.length,4);assert(state.calls.every(c=>c.timeoutMs>0&&c.timeoutMs<=4000));assert(!f.events.some(e=>e.event==='aborted'));
  }finally{await f.close();}
},40000);

test('exact duplicate artifacts keep near-limit union96 without dropping owned roots',async()=>{const f=await fixture();try{const l=f.lease.retainedLedger;l.hostRoots=[{path:f.lease.artifactRoots[0],allocatedBytes:1},...Array.from({length:92},(_,i)=>({path:'/private/tmp/calcify-o2-union-retained-'+i,allocatedBytes:i+2}))];l.allocatedBytes=l.hostRoots.reduce((n,h)=>n+h.allocatedBytes,0);f.lease.artifactRoots=l.hostRoots.slice(0,7).map(h=>h.path);const actual=monitorLease(f.lease);assert.equal(actual.roots.length,96);assert.deepEqual(actual.roots,[...l.hostRoots.map(h=>h.path),f.lease.stateRoot,f.lease.proofRoot,f.lease.secretRoot].sort());l.hostRoots.push({path:'/private/tmp/calcify-o2-union-distinct-extra',allocatedBytes:1});l.allocatedBytes++;assert.throws(()=>monitorLease(f.lease),/owned_root_count/);}finally{await f.close();}});
test('union normalization preserves each input bound, root scope and retained overlap refusal',async()=>{const f=await fixture();try{const artifact=f.lease.artifactRoots[0];assert.throws(()=>dedupRoots(Array.from({length:97},()=>artifact)),/owned_root_count/);f.lease.artifactRoots=Array.from({length:96},()=>artifact);assert.equal(monitorLease(f.lease).roots.length,4);f.lease.artifactRoots.push(artifact);assert.throws(()=>monitorLease(f.lease),/owned_root_count/);f.lease.artifactRoots=[artifact,'/private/tmp'];assert.throws(()=>monitorLease(f.lease),/owned_root_scope/);f.lease.artifactRoots=[artifact];const l=f.lease.retainedLedger;l.hostRoots=[{path:artifact,allocatedBytes:1},{path:artifact,allocatedBytes:1}];l.allocatedBytes=2;assert.throws(()=>monitorLease(f.lease),/retained_host_overlap/);}finally{await f.close();}});
