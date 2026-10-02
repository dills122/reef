import {spawn} from 'node:child_process';
import {mkdir,appendFile,writeFile,mkdtemp,rm,readFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomUUID} from 'node:crypto';

const root=resolve(import.meta.dirname,'../../..');
const runtime=join(root,'services/platform-runtime');
const runId=randomUUID().slice(0,8);
const evidence=join(root,'docs/evidence/calcify-phase2-implementation',`broker-${runId}`);
await mkdir(evidence,{recursive:true});
const cp=['build/classes/kotlin/test','build/classes/kotlin/main','build/classes/java/main','build/resolver-probe-deps/*'].join(':');
const broker='127.0.0.1:29192,127.0.0.1:29292,127.0.0.1:29392';
const java=process.env.JAVA_HOME ? join(process.env.JAVA_HOME,'bin/java') : 'java';
const base=['-Xms128m','-Xmx768m','-cp',cp,'com.reef.platform.calcify.CalcifyResolverBrokerProbe'];
const workers=[];const directories=[];const results=[];
const delay=ms=>new Promise(r=>setTimeout(r,ms));
async function call(mode,prefix,...args) {
 const argv=[...base,mode,broker,prefix,...args.map(String)];
 const child=spawn(java,argv,{cwd:runtime,stdio:['ignore','pipe','pipe']});let output='',error='';
 child.stdout.on('data',chunk=>output+=chunk);child.stderr.on('data',chunk=>error+=chunk);
 const timer=setTimeout(()=>child.kill('SIGKILL'),40000);
 const code=await new Promise(r=>child.on('exit',r));clearTimeout(timer);
 await appendFile(join(evidence,'commands.log'),JSON.stringify({mode,prefix,args,code})+'\n'+output+error+'\n');
 if(code!==0) throw Error(`probe ${mode} failed (${code}): ${error.slice(-2000)}`);
 return output;
}
async function start(prefix,app,dir,crash='',name='worker') {
 const log=join(evidence,`${name}.log`);const stream=createWriteStream(log);
 const child=spawn(java,[...base,'worker',broker,prefix,app,dir,crash],{cwd:runtime,stdio:['ignore','pipe','pipe']});
 child.stdout.pipe(stream);child.stderr.pipe(stream);workers.push({child,stream,log});return child;
}
async function stop(child,hard=false) {
 if(child.exitCode!==null || child.signalCode!==null)return;
 const closed=new Promise(r=>child.once('exit',r));child.kill(hard?'SIGKILL':'SIGTERM');
 const timer=setTimeout(()=>child.kill('SIGKILL'),12000);await closed;clearTimeout(timer);
}
async function waitExit(child) {
 if(child.exitCode!==null)return;
 const ended=new Promise(r=>child.once('exit',r));const timer=setTimeout(()=>child.kill('SIGKILL'),30000);
 await ended;clearTimeout(timer);if(child.exitCode!==91)throw Error(`crash probe expected91 got ${child.exitCode}/${child.signalCode}`);
}
async function count(prefix,n) {
 const deadline=Date.now()+90000;
 while(Date.now()<deadline) {const out=await call('observe',prefix,n);const result=JSON.parse(out.trim().split('\n').at(-1)).result;if(result.pass)return result;await delay(500)}
 throw Error(`timeout waiting for ${prefix} count${n}`);
}
async function stateDir() {const dir=await mkdtemp(join(tmpdir(),'reef-resolver-'));directories.push(dir);return dir}
async function docker(...args) {
 const argv=['compose','-p','reef-calcify-resolver-test','-f',join(root,'scripts/dev/calcify-resolver/broker.compose.yml'),...args];
 const child=spawn('docker',argv,{cwd:root,stdio:['ignore','pipe','pipe']});let out='';child.stdout.on('data',c=>out+=c);child.stderr.on('data',c=>out+=c);
 const code=await new Promise(r=>child.on('exit',r));await appendFile(join(evidence,'commands.log'),JSON.stringify(argv)+'\n'+out+'\n');if(code!==0)throw Error(`docker failed${code}`);
}
try {
 for(const boundary of ['commit','state','forward','disk-loss']) {
  const prefix=`p2-${runId}-${boundary}`;const app=`${prefix}-app`;let dir=await stateDir();await call('init',prefix);await call('seed',prefix,1,0);
  const first=await start(prefix,app,dir,boundary==='disk-loss'?'':boundary,`${boundary}-first`);
  if(boundary==='disk-loss') {await count(prefix,130);await stop(first,true);dir=await stateDir()} else await waitExit(first);
  const before=JSON.parse((await call('observe',prefix,(boundary==='commit'||boundary==='disk-loss')?130:0)).trim().split('\n').at(-1)).result;
  if(boundary==='state'||boundary==='forward') {if(before.records!==0)throw Error(`${boundary} exposed uncommitted output`)}
  const started=Date.now();const restored=await start(prefix,app,dir,'',`${boundary}-restored`);await call('seed',prefix,2,0);const after=await count(prefix,260);
  results.push({boundary,before,after,restartAndWaveMs:Date.now()-started});await stop(restored);
  await writeFile(join(evidence,'results.json'),JSON.stringify({runId,scope:'Production resolver Redpanda RF3/write.caching=false; two partitions, small full-fact fixture; synthetic producer source/links',results},null,2)+'\n');
 }
 const prefix=`p2-${runId}-isolation`;const app=`${prefix}-app`;await call('init',prefix);await call('seed',prefix,1,0);await call('seed',prefix,1,1);
 const first=await start(prefix,app,await stateDir(),'','isolation-owner');await count(prefix,260);
 const second=await start(prefix,app,await stateDir(),'','isolation-second');await delay(18000);
 await call('poison',prefix);await call('seed',prefix,2,1);const isolated=await count(prefix,390);
 await stop(first,true);await call('seed',prefix,3,1);const promoted=await count(prefix,520);
 results.push({boundary:'poison-rebalance-promotion',isolated,promoted});await stop(second);
 await writeFile(join(evidence,'results.json'),JSON.stringify({runId,scope:'Production resolver Redpanda RF3/write.caching=false; two partitions, small full-fact fixture; synthetic producer source/links',results},null,2)+'\n');
 const loss=`p2-${runId}-broker-loss`;await call('init',loss);const worker=await start(loss,`${loss}-app`,await stateDir(),'','broker-loss');await call('seed',loss,1,0);await count(loss,130);
 await docker('stop','rp0');await call('seed',loss,2,0);const brokerLoss=await count(loss,260);await docker('start','rp0');await stop(worker);
 results.push({boundary:'broker-loss',after:brokerLoss});
 const retention=`p2-${runId}-retention`;await call('init',retention);await call('seed',retention,1,0);await call('retention',retention);
 const retained=await start(retention,`${retention}-app`,await stateDir(),'','retention-fault');await delay(12000);
 const retentionResult=JSON.parse((await call('raw-observe',retention,0)).trim().split('\n').at(-1)).result;
 if(!retentionResult.pass)throw Error('retention loss emitted context');await stop(retained);
 results.push({boundary:'retention-loss',after:retentionResult});
 const recreation=`p2-${runId}-recreation`;await call('init',recreation);const recreationDir=await stateDir();
 const live=await start(recreation,`${recreation}-app`,recreationDir,'','live-recreation');await call('seed',recreation,1,0);await count(recreation,130);
 const original=JSON.parse((await call('raw-observe',recreation,130)).trim().split('\n').at(-1)).result;
 await call('recreate',recreation);await delay(5000);await call('seed',recreation,2,0);
 const afterRecreation=JSON.parse((await call('raw-observe',recreation,130)).trim().split('\n').at(-1)).result;
 if(!afterRecreation.pass || afterRecreation.outputHash!==original.outputHash)throw Error('live recreation changed durable output');
 await stop(live);const restarted=await start(recreation,`${recreation}-app`,recreationDir,'','recreation-restored');await delay(12000);
 const restoredRecreation=JSON.parse((await call('raw-observe',recreation,130)).trim().split('\n').at(-1)).result;
 if(!restoredRecreation.pass || restoredRecreation.outputHash!==original.outputHash)throw Error('recreation restart changed durable output');await stop(restarted);
 results.push({boundary:'live-and-restored-topic-recreation',after:afterRecreation,restored:restoredRecreation});
 const zombie=`p2-${runId}-zombie`;await call('init',zombie);const owner=await start(zombie,`${zombie}-app`,await stateDir(),'','zombie-owner');await call('seed',zombie,1,0);await count(zombie,130);
 owner.kill('SIGSTOP');const successor=await start(zombie,`${zombie}-app`,await stateDir(),'','zombie-successor');await call('seed',zombie,2,0);const fenced=await count(zombie,260);
 owner.kill('SIGCONT');await delay(10000);await call('seed',zombie,3,0);const resumed=await count(zombie,390);await stop(owner);await stop(successor);
 results.push({boundary:'expired-owner-resumes',after:fenced,resumed});
 await writeFile(join(evidence,'results.json'),JSON.stringify({runId,scope:'Production resolver Redpanda RF3/write.caching=false; two partitions, small full-fact fixture; synthetic producer source/links',results,pass:true},null,2)+'\n');
 console.log(evidence);
} catch(error) {
 await writeFile(join(evidence,'failure.json'),JSON.stringify({runId,error:String(error),results},null,2)+'\n');throw error;
} finally {
 for(const {child,stream} of workers){child.kill('SIGCONT');await stop(child);stream.end()}
 for(const dir of directories)await rm(dir,{recursive:true,force:true});
 await docker('start','rp0');
}
