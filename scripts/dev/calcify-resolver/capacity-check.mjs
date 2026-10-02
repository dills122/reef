import {spawn} from 'node:child_process';
import {mkdir,appendFile,writeFile,mkdtemp,rm} from 'node:fs/promises';
import {createWriteStream} from 'node:fs';
import {tmpdir} from 'node:os';
import {join,resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
const root=resolve(import.meta.dirname,'../../..'),runtime=join(root,'services/platform-runtime'),runId=randomUUID().slice(0,8);
const evidence=join(root,'docs/evidence/calcify-phase2-implementation',`capacity-${runId}`);await mkdir(evidence,{recursive:true});
const cp=['build/classes/kotlin/test','build/classes/kotlin/main','build/classes/java/main','build/resolver-probe-deps/*'].join(':');
const broker='127.0.0.1:29192,127.0.0.1:29292,127.0.0.1:29392';
const java=join(process.env.JAVA_HOME ?? '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home','bin/java');
const base=['-Xms128m','-Xmx2g','-cp',cp,'com.reef.platform.calcify.CalcifyResolverBrokerProbe'];
const waves=Number(process.env.CALCIFY_PROBE_WAVES ?? '200'),shapes=(process.env.CALCIFY_PROBE_SHAPES ?? 'hot,spread,skew').split(','),results=[];
async function call(mode,prefix,...args) {
 const child=spawn(java,[...base,mode,broker,prefix,...args.map(String)],{cwd:runtime,stdio:['ignore','pipe','pipe']});let output='',error='';
 child.stdout.on('data',chunk=>output+=chunk);child.stderr.on('data',chunk=>error+=chunk);
 const timer=setTimeout(()=>child.kill('SIGKILL'),180000),code=await new Promise(r=>child.on('exit',r));clearTimeout(timer);
 await appendFile(join(evidence,'commands.log'),JSON.stringify({mode,prefix,args,code})+'\n'+output+error+'\n');
 if(code!==0)throw Error(`probe ${mode} failed${code}: ${error.slice(-2000)}`);
 return output.trim() ? JSON.parse(output.trim().split('\n').at(-1)) : null;
}
for(const shape of shapes) {
 const prefix=`p2-cap-${runId}-${shape}`,app=`${prefix}-app`,dir=await mkdtemp(join(tmpdir(),'reef-resolver-capacity-'));let worker,stream;
 try {
  await call('init',prefix);const seed=await call('paired-seed',prefix,waves,shape);
  stream=createWriteStream(join(evidence,`${shape}-worker.log`));
  worker=spawn(java,[...(process.env.CALCIFY_PROBE_JFR==='1' ? [`-XX:StartFlightRecording=filename=${join(evidence,`${shape}.jfr`)},settings=profile,dumponexit=true,maxsize=32m`] : []),...base,'worker',broker,prefix,app,dir,'','2'],{cwd:runtime,stdio:['ignore','pipe','pipe']});worker.stdout.pipe(stream);worker.stderr.pipe(stream);
  const measured=await call('measure',prefix,waves*100,120000),exact=await call('paired-oracle',prefix,waves*100);
  results.push({shape,seed,measured:measured.result,exact:exact.result});
 } catch(error) {results.push({shape,error:String(error)})}
 finally {
  if(worker && worker.exitCode===null && worker.signalCode===null) {const ended=new Promise(r=>worker.once('exit',r));worker.kill('SIGTERM');const timer=setTimeout(()=>worker.kill('SIGKILL'),12000);await ended;clearTimeout(timer)}
  stream?.end();await rm(dir,{recursive:true,force:true});
  await writeFile(join(evidence,'results.json'),JSON.stringify({runId,scope:'Resolver-stage short backlog diagnostic; Redpanda RF3/fsync, 2 partitions/threads, 32MiB probe segments, full paired matcher fixture; no HTTP intake, no sustained qualification',waves,results},null,2)+'\n');
 }
}
console.log(evidence);
