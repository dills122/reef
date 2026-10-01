import {spawn} from 'node:child_process';
import {mkdir,appendFile,writeFile,mkdtemp,rm,readdir,stat} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
const root=resolve(import.meta.dirname,'../../..'),runtime=join(root,'services/platform-runtime'),runId=randomUUID().slice(0,8);
const evidence=join(root,'docs/evidence/calcify-phase2-implementation',`recovery-${runId}`);await mkdir(evidence,{recursive:true});
const cp=['build/classes/kotlin/test','build/classes/kotlin/main','build/classes/java/main','build/resolver-probe-deps/*'].join(':'),broker='127.0.0.1:29192,127.0.0.1:29292,127.0.0.1:29392';
const java=join(process.env.JAVA_HOME ?? '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home','bin/java');
const base=['-Xms128m','-Xmx2g','-cp',cp,'com.reef.platform.calcify.CalcifyResolverBrokerProbe'];
const waves=Number(process.env.CALCIFY_RECOVERY_WAVES ?? '5000'),prefix=`p2-recovery-${runId}`,app=`${prefix}-app`,workers=[],dirs=[];
const result={runId,scope:'Cold local-state loss, production resolver/changelog on Redpanda RF3/fsync; two partitions/threads, 32MiB probe segments; paired fixture injection; no HTTP or availability guarantee',waves,rows:waves*200,restoreObjectiveMs:120000};
async function call(mode,...args) {
 const child=spawn(java,[...base,mode,broker,prefix,...args.map(String)],{cwd:runtime,stdio:['ignore','pipe','pipe']});let output='',error='';
 child.stdout.on('data',chunk=>output+=chunk);child.stderr.on('data',chunk=>error+=chunk);
 const timer=setTimeout(()=>child.kill('SIGKILL'),360000),code=await new Promise(r=>child.on('exit',r));clearTimeout(timer);
 await appendFile(join(evidence,'commands.log'),JSON.stringify({mode,args,code})+'\n'+output+error+'\n');
 if(code!==0)throw Error(`probe ${mode} failed${code}: ${error.slice(-2000)}`);
 return output.trim() ? JSON.parse(output.trim().split('\n').at(-1)) : null;
}
async function start(name) {
 const dir=await mkdtemp(join(tmpdir(),'reef-resolver-recovery-'));dirs.push(dir);
 const log=createWriteStream(join(evidence,`${name}.log`)),started=Date.now();
 const child=spawn(java,[...base,'worker',broker,prefix,app,dir,'','2'],{cwd:runtime,stdio:['ignore','pipe','pipe']});workers.push({child,log});
 child.stdout.pipe(log);child.stderr.pipe(log);
 const running=new Promise((resolveRunning,reject)=> {
  let tail='';const timer=setTimeout(()=>reject(Error('restore RUNNING deadline exceeded')),180000);
  child.stdout.on('data',chunk=>{tail=(tail+chunk).slice(-2000);if(tail.includes('STATE REBALANCING -> RUNNING')){clearTimeout(timer);resolveRunning(Date.now()-started)}});
  child.on('exit',code=>{clearTimeout(timer);reject(Error(`worker exited${code} before RUNNING`))});
 });
 return {child,dir,running};
}
async function stop(child) {if(child.exitCode!==null || child.signalCode!==null)return;const ended=new Promise(r=>child.once('exit',r));child.kill('SIGKILL');await ended}
async function bytes(dir) {let total=0;for(const entry of await readdir(dir,{withFileTypes:true})){const path=join(dir,entry.name);if(entry.isDirectory())total+=await bytes(path);else if(entry.isFile())total+=(await stat(path)).size}return total}
try {
 await call('init');result.seed=await call('paired-seed',waves,'aged');console.log(`seeded${result.rows} rows`);
 const first=await start('initial');result.initialRunningMs=await first.running;
 result.initial=(await call('measure',waves*100,240000)).result;
 if(!result.initial.pass)throw Error('initial cohort did not drain; representative full state unavailable');
 result.initialExact=(await call('paired-oracle',waves*100)).result;
 if(!result.initialExact.pass)throw Error('initial full-fact parity failed');
 result.localStateBytes=await bytes(first.dir);await stop(first.child);await rm(first.dir,{recursive:true,force:true});
 const restored=await start('cold-restored');result.coldRunningObservedMs=await restored.running;
 result.catchupSeed=await call('paired-seed',1,'aged',waves);
 result.after=(await call('measure',(waves+1)*100,30000)).result;
 result.afterExact=(await call('paired-oracle',(waves+1)*100)).result;
 result.pass=result.rows>=1000000 && result.after.pass && result.afterExact.pass && result.coldRunningObservedMs<=result.restoreObjectiveMs;
} catch(error) {result.error=String(error);result.pass=false}
finally {
 for(const {child,log} of workers){await stop(child);log.end()}
 for(const dir of dirs)await rm(dir,{recursive:true,force:true});
 await writeFile(join(evidence,'result.json'),JSON.stringify(result,null,2)+'\n');console.log(evidence);
}
