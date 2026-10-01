import {spawn} from 'node:child_process';
import {mkdir,appendFile,writeFile,mkdtemp,rm,readFile} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
const root=resolve(import.meta.dirname,'../../..'),runtime=join(root,'services/platform-runtime'),runId=randomUUID().slice(0,8);
const evidence=join(root,'docs/evidence/calcify-phase2-implementation',`sustained-${runId}`);await mkdir(evidence,{recursive:true});
const cp=['build/classes/kotlin/test','build/classes/kotlin/main','build/classes/java/main','build/resolver-probe-deps/*'].join(':'),broker='127.0.0.1:29192,127.0.0.1:29292,127.0.0.1:29392';
const java=join(process.env.JAVA_HOME ?? '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home','bin/java'),base=['-Xms128m','-Xmx2g','-cp',cp,'com.reef.platform.calcify.CalcifyResolverBrokerProbe'];
const duration=Number(process.env.CALCIFY_SUSTAINED_SECONDS ?? '300'),pace=Number(process.env.CALCIFY_SUSTAINED_WAVES_PER_SECOND ?? '110'),waves=duration*pace,expected=waves*100;
const shapes=(process.env.CALCIFY_SUSTAINED_SHAPES ?? 'hot,spread,skew,aged').split(','),results=[];
const policy={durationSeconds:duration,offeredTradesPerSecond:pace*100,targetDurableRate:10000,maxEndGap:20000,maxDrainSeconds:90,requiredExactParity:true};
const hashes={};for(const name of ['calcify/CalcifyResolverProcessor.class','api/JsonCodecKt.class'])hashes[name]=createHash('sha256').update(await readFile(join(runtime,'build/classes/kotlin/main/com/reef/platform',name))).digest('hex');
await writeFile(join(evidence,'frozen-policy.json'),JSON.stringify({runId,policy,hashes,scope:'Production resolver stage, RF3/fsync, 2 threads/partitions, 128KiB/LZ4 batches, bounded8MiB state cache, 32MiB test segments; paired canonical source injected; no HTTP capacity claim'},null,2)+'\n');
function launch(mode,prefix,args=[],name=mode) {
 const stream=createWriteStream(join(evidence,`${name}.log`)),child=spawn(java,[...base,mode,broker,prefix,...args.map(String)],{cwd:runtime,stdio:['pipe','pipe','pipe']});let output='',line='',lastResult,markResolve,readyResolve;
 const ready=new Promise(r=>readyResolve=r),marked=new Promise(r=>markResolve=r);
 child.stdout.pipe(stream);child.stderr.pipe(stream);
 child.stdout.on('data',chunk=>{output+=chunk;line+=chunk;const lines=line.split('\n');line=lines.pop();for(const text of lines){if(text==='OBSERVER_READY' || text.includes('STATE REBALANCING -> RUNNING'))readyResolve();if(text.startsWith('{')){const value=JSON.parse(text);if(value.result)lastResult=value.result;if(value.mark)markResolve(value.mark)}}});
 const timer=setTimeout(()=>child.kill('SIGKILL'),900000);
 const done=new Promise((resolveDone,reject)=>child.on('exit',async code=>{clearTimeout(timer);stream.end();await appendFile(join(evidence,'commands.log'),JSON.stringify({mode,prefix,args,code})+'\n');if(code===0)resolveDone({result:lastResult,body:output.trim() ? output.trim().split('\n').at(-1) : null});else reject(Error(`${mode} exited${code}`))}));
 done.catch(()=>{});return {child,ready,marked,done,get result(){return lastResult}};
}
async function call(mode,prefix,...args){const task=launch(mode,prefix,args,`${prefix}-${mode}`);const out=await task.done;return out.body ? JSON.parse(out.body) : null}
async function stop(task){if(!task || task.child.exitCode!==null || task.child.signalCode!==null)return;const ended=new Promise(r=>task.child.once('exit',r));task.child.kill('SIGKILL');await ended}
for(const shape of shapes) {
 const prefix=`p2-sus-${runId}-${shape}`,app=`${prefix}-app`,dir=await mkdtemp(join(tmpdir(),'reef-resolver-sustained-'));let worker,observer;
 try {
  await call('init',prefix);worker=launch('worker',prefix,[app,dir,'',2],`${shape}-worker`);
  await Promise.race([worker.ready,worker.done.then(()=>{throw Error('worker exited before ready')})]);
  let agedBootstrap;
  if(shape==='aged'){await call('paired-seed',prefix,5000,'aged');agedBootstrap=(await call('measure',prefix,500000,240000)).result;if(!agedBootstrap.pass)throw Error('aged bootstrap incomplete')}
  observer=launch('measure',prefix,[expected,(duration+90)*1000,shape==='aged'],`${shape}-observer`);
  await Promise.race([observer.ready,observer.done.then(()=>{throw Error('observer exited before ready')})]);
  const seed=await call('paired-seed',prefix,waves,shape,shape==='aged'?5000:0,pace);
  observer.child.stdin.write('mark\n');
  const mark=await Promise.race([observer.marked,observer.done.then(out=>({records:out.result.records,elapsedMs:out.result.elapsedMs}))]);
  const measured=(await observer.done).result,exact=(await call('paired-oracle',prefix,expected+(shape==='aged'?500000:0))).result;
  const gap=expected-mark.records;
  const pass=duration>=300 && measured.pass && exact.pass && gap<=policy.maxEndGap && measured.durableRate>=policy.targetDurableRate;
  results.push({shape,agedBootstrap,seed,loadEndMark:mark,endGap:gap,measured,exact,pass});console.log(`${shape}: rate${measured.durableRate}, gap${gap}, pass${pass}`);
  if(!pass)break;
 } catch(error){results.push({shape,error:String(error),pass:false});break}
 finally {await stop(observer);await stop(worker);if(results.at(-1)?.exact?.pass)await call('cleanup',prefix);await rm(dir,{recursive:true,force:true});await writeFile(join(evidence,'results.json'),JSON.stringify({runId,policy,results,pass:results.length===shapes.length && results.every(x=>x.pass)},null,2)+'\n')}
}
console.log(evidence);
