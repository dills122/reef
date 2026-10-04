import {spawn} from 'node:child_process';
import {createWriteStream} from 'node:fs';
import {mkdir,readFile,writeFile,mkdtemp,appendFile,readdir} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {tmpdir} from 'node:os';

const root=resolve(import.meta.dirname,'../../..');
const runtime=join(root,'services/platform-runtime');
const mode=process.argv[2] ?? 'plan';
if (!['plan','run-happy','run-core','run-golden','run-recovery'].includes(mode)) throw Error('mode must be plan, run-core, run-golden or run-recovery');
const broker=process.env.CALCIFY_FINANCIAL_BROKER ?? '127.0.0.1:39192,127.0.0.1:39292,127.0.0.1:39392';
const run=process.env.CALCIFY_FINANCIAL_RUN_ID ?? randomUUID().slice(0,8);
if(!/^[a-z0-9-]{1,40}$/.test(run))throw Error('invalid isolated run ID');
const prefix=`financial-s1-97924e15-${run}`;
const proof=resolve(process.env.CALCIFY_FINANCIAL_PROOF_DIR ?? join(root,'.planning/calcify-financial-proof/broker',run));
await mkdir(proof,{recursive:true});
const fixtures=JSON.parse(await readFile(join(root,'docs/evidence/calcify-financial-sprint1/fixtures.json'),'utf8'));
const java=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin/java'):'java';
const cp=process.env.CALCIFY_FINANCIAL_CLASSPATH ?? ['build/classes/kotlin/test','build/classes/kotlin/main','build/classes/java/main','build/resolver-probe-deps/*'].join(':');
const base=['-Xms128m','-Xmx768m','-cp',cp,'com.reef.platform.calcify.financial.FinancialBrokerProbe'];
const workers=[];
let commandOrdinal=0;
const results=[];
function scopeInput(input,domain){return {...structuredClone(input),domain};}
function cohort(caseIndex,stage=false){
 const c=fixtures.cases[caseIndex];
 const genesis={A:{balances:c.genesisBalances},B:{balances:c.genesisBalances}};
 const inputs=[];
 for(const domain of ['A','B'])for(const step of c.steps)inputs.push({domain,mode:stage?'STAGE':'EXECUTE',input:scopeInput(step.input,domain)});
 if(stage)for(const domain of ['A','B'])for(let i=0;i<c.steps.length;i++)inputs.push({domain,mode:'DRAIN'});
 return {schema:'financial-e3-cohort-v1',commitIntervalMs:60000,maxPollRecords:stage?1:128,caseId:c.id,policy:fixtures.policy,genesis,inputs:inputs.map((input,inputOrdinal)=>({...input,inputOrdinal,commit:stage||inputOrdinal===inputs.length-1})),scope:'synthetic unreserved gross DvP; two declared domains, one shared input partition'};
}
function twoWaves(){
 const config=cohort(0);
 const fresh=config.inputs.map(row=>{const copy=structuredClone(row);copy.input.actionId+='-fresh';copy.input.payload.executionId+='-fresh';return copy;});
 config.inputs.push(...fresh);
 config.inputs=config.inputs.map((row,inputOrdinal)=>({...row,inputOrdinal,commit:inputOrdinal===3||inputOrdinal===7}));
 return config;
}
async function file(name,config){const path=join(proof,`${name}.json`);await writeFile(path,JSON.stringify(config,null,2)+'\n',{flag:'wx'});return path;}
async function command(probeMode,topic,config,...args){
 const id=String(++commandOrdinal).padStart(4,'0');
 const argv=[...base,probeMode,broker,topic,config,...args.map(String)];
 const stdout=createWriteStream(join(proof,`${id}-${probeMode}.stdout.log`),{flags:'wx'});
 const stderr=createWriteStream(join(proof,`${id}-${probeMode}.stderr.log`),{flags:'wx'});
 const child=spawn(java,argv,{cwd:runtime,stdio:['ignore','pipe','pipe']});
 child.stdout.pipe(stdout);child.stderr.pipe(stderr);
 const timer=setTimeout(()=>child.kill('SIGKILL'),90000);
 const code=await new Promise((resolveExit,reject)=>{child.once('error',reject);child.once('exit',resolveExit);});
 clearTimeout(timer);
 await Promise.all([new Promise(r=>stdout.end(r)),new Promise(r=>stderr.end(r))]);
 await appendFile(join(proof,'attempts.jsonl'),JSON.stringify({ordinal:commandOrdinal,command:[java,...argv],cwd:runtime,code,stdout:`${id}-${probeMode}.stdout.log`,stderr:`${id}-${probeMode}.stderr.log`})+'\n');
 if(code!==0)throw Error(`${probeMode} exited ${code}; retained raw logs ${proof}`);
 const text=await readFile(join(proof,`${id}-${probeMode}.stdout.log`),'utf8');
 return text.trim().split('\n').filter(Boolean).map(line=>JSON.parse(line));
}
async function start(topic,config,dir,fault=''){
 const id=String(++commandOrdinal).padStart(4,'0');
 const argv=[...base,'worker',broker,topic,config,`${topic}-app`,dir,fault];
 const stdout=createWriteStream(join(proof,`${id}-worker.stdout.log`),{flags:'wx'});
 const stderr=createWriteStream(join(proof,`${id}-worker.stderr.log`),{flags:'wx'});
 const child=spawn(java,argv,{cwd:runtime,stdio:['pipe','pipe','pipe']});
 child.stdout.pipe(stdout);child.stderr.pipe(stderr);
 const worker={child,stdout,stderr,id,argv};workers.push(worker);
 child.once('error',error=>{worker.spawnError=error;});
 await appendFile(join(proof,'attempts.jsonl'),JSON.stringify({ordinal:commandOrdinal,command:[java,...argv],cwd:runtime,stateDir:dir,fault,started:true})+'\n');
 return worker;
}
async function stop(worker,hard=false){
 const child=worker.child;
 if(child.exitCode===null && child.signalCode===null){
  const done=new Promise(r=>child.once('exit',r));child.kill('SIGCONT');child.kill(hard?'SIGKILL':'SIGTERM');
  const timer=setTimeout(()=>child.kill('SIGKILL'),12000);await done;clearTimeout(timer);
 }
 await Promise.all([new Promise(r=>worker.stdout.end(r)),new Promise(r=>worker.stderr.end(r))]);
 await appendFile(join(proof,'attempts.jsonl'),JSON.stringify({ordinal:Number(worker.id),exitCode:child.exitCode,signal:child.signalCode,stopped:true})+'\n');
}
async function expectCrash(worker,code){
 const child=worker.child;
 if(child.exitCode===null && child.signalCode===null){
  const done=new Promise(r=>child.once('exit',r));const timer=setTimeout(()=>child.kill('SIGKILL'),60000);await done;clearTimeout(timer);
 }
 if(child.exitCode!==code)throw Error(`fault expected exit ${code}, actual ${child.exitCode}/${child.signalCode}`);
 await stop(worker);
}
async function observe(topic,config,timeout=60000){return (await command('observe',topic,config,timeout)).findLast(row=>row.result)?.result;}
async function directory(){return await mkdtemp(join(tmpdir(),'reef-financial-e3-'));}
async function ready(worker){
 const deadline=Date.now()+60000;
 while(Date.now()<deadline){
  if(worker.spawnError||worker.child.exitCode!==null)throw Error(`worker failed before ready: ${worker.spawnError??worker.child.exitCode}`);
  const raw=await readFile(join(proof,`${worker.id}-worker.stdout.log`),'utf8');
  const rows=raw.split('\n').flatMap(line=>{try{return [JSON.parse(line)];}catch{return [];}});
  if(rows.some(row=>row.frameworkRunningMs!==undefined)&&rows.some(row=>row.certifiedCatchupDomains!==undefined))return;
  await new Promise(r=>setTimeout(r,250));
 }
 throw Error('worker RUNNING/certified catchup barrier timed out');
}
async function observePrefix(topic,config,count){return (await command('observe-prefix',topic,config,60000,count)).findLast(row=>row.result)?.result;}
async function arm(name,config,fault='',loss=false,initialCount=null){
 const topic=`${prefix}-${name}`;
 await command('init',topic,config);
 if(initialCount!==null)await command('seed-prefix',topic,config,initialCount);else await command('seed',topic,config);
 let dir=await directory();const first=await start(topic,config,dir,fault);
 let before;
 if(fault){
  await expectCrash(first,fault==='serialization'||fault==='production'?1:91);
  before=await observe(topic,config,1000);
  if(before.coveredInputs!==0)throw Error(`${name} exposed uncommitted financial outputs`);
 }else{
  await ready(first);
  before=initialCount!==null?await observePrefix(topic,config,initialCount):await observe(topic,config);
  if(!before.pass)throw Error(`${name} incomplete happy path`);
  await stop(first,true);
 }
 if(loss)dir=await directory();
 if(initialCount!==null)await command('append-suffix',topic,config);
 const restored=await start(topic,config,dir);
 await ready(restored);
 const after=await observe(topic,config);
 if(!after.pass)throw Error(`${name} recovery output/oracle mismatch`);
 if(!fault&&initialCount===null&&before.finalCommittedEndOffset!==after.finalCommittedEndOffset)throw Error(`${name} restore republished results`);
 if(name==='happy'&&!before.consecutiveResultOffsets)throw Error('four core decisions were not grouped in one observed transaction');
 const isolated=await command('reconstruct',topic,config,60000);
 await stop(restored);
 const result={name,topic,fault,localStateLost:loss,before,after,isolated};results.push(result);
 await writeFile(join(proof,'results.json'),JSON.stringify({prefix,scope:'bounded RF3/EOS financial experiment; no live venue or whole-cluster durability claim',results},null,2)+'\n');
}
try{
 const core=await file('core-cohort',cohort(0));
 const staging=await file('stage-cohort',cohort(19,true));
 const happy=await file('happy-cohort',twoWaves());
 const boundRows=await command('mutation-plan',prefix,core);
 const mutations=boundRows.find(row=>row.mutations)?.mutations;
 if(!Array.isArray(mutations)||mutations.length===0)throw Error('bounded semantic mutation inventory missing');
 const hashes={};
 for(const path of (await readdir(join(runtime,'src/test/kotlin/com/reef/platform/calcify/financial'))).filter(name=>name.startsWith('Financial')&&name.endsWith('.kt')).sort())hashes[path]=createHash('sha256').update(await readFile(join(runtime,'src/test/kotlin/com/reef/platform/calcify/financial',path))).digest('hex');
 const compiledHashes={};
 for(const entry of cp.split(':')){
  if(entry.endsWith('*')){
   const dir=resolve(runtime,entry.slice(0,-1));
   for(const name of (await readdir(dir)).filter(name=>name.endsWith('.jar')).sort())compiledHashes[join(dir,name)]=createHash('sha256').update(await readFile(join(dir,name))).digest('hex');
  }else{
   const dir=join(resolve(runtime,entry),'com/reef/platform/calcify/financial');
   try{for(const name of (await readdir(dir)).filter(name=>name.startsWith('Financial')&&name.endsWith('.class')).sort())compiledHashes[join(dir,name)]=createHash('sha256').update(await readFile(join(dir,name))).digest('hex');}
   catch(error){if(error.code!=='ENOENT')throw error;}
  }
 }
 const preflight=process.env.CALCIFY_FINANCIAL_PREFLIGHT ? JSON.parse(await readFile(process.env.CALCIFY_FINANCIAL_PREFLIGHT,'utf8')) : null;
 if(mode!=='plan'&&!preflight)throw Error('actual runs require CALCIFY_FINANCIAL_PREFLIGHT frozen hardware/image/topic settings artifact');
 const plan={preflight,schema:'financial-e3-plan-v1',prefix,broker,fixtureSha256:createHash('sha256').update(await readFile(join(root,'docs/evidence/calcify-financial-sprint1/fixtures.json'))).digest('hex'),sourceHashes:hashes,classpath:cp,compiledHashes,domains:['A','B'],partitions:1,replication:3,commitIntervalMs:60000,inputsPerCore:4,mutationBoundaries:mutations,goldenCases:20,stagingInputs:JSON.parse(await readFile(staging,'utf8')).inputs.length,externalMatrix:['majority-one-broker-unavailable','stale-owner-resume-after-takeover','committed-output-before-controller-ack','producer-failure'],retainedResources:'input/result/changelog topics and disposable state directories; parent owns broker/resource cleanup'};
 await writeFile(join(proof,'plan.json'),JSON.stringify(plan,null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({frozenPlan:join(proof,'plan.json'),mode}));
 if(mode==='plan')process.exitCode=0;
 else if(mode==='run-happy'){await arm('happy',happy,'',false,4);}
 else if(mode==='run-core'){
  await arm('happy',happy,'',false,4);
  for(const {index} of mutations)await arm(`mutation-${index}`,core,`mutation:${index}`);
  await arm('forward',core,'forward');await arm('serialization',core,'serialization');
  await arm('local-state-loss',happy,'',true,4);await arm('staged-phase-loss',staging,'',true,19);
 }else if(mode==='run-golden'){
  for(let i=0;i<fixtures.cases.length;i++)await arm(`golden-${i}`,await file(`golden-cohort-${i}`,cohort(i)));
 }else if(mode==='run-recovery'){
  await arm('local-state-loss',happy,'',true,4);await arm('staged-phase-loss',staging,'',true,19);
 }
}catch(error){await writeFile(join(proof,'failure.json'),JSON.stringify({error:String(error),results},null,2)+'\n');throw error;}
finally{for(const worker of workers)if(worker.child.exitCode===null&&worker.child.signalCode===null)await stop(worker);}
console.log(JSON.stringify({proof,results:results.length,pass:mode!=='plan'}));
