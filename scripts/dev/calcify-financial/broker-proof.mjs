import {spawn,execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {EXTERNAL_ARMS,validateRegistration,validateInspection,runExternalArm,createPhaseClient,hostMonotonicMs} from './lib/external-faults.mjs';
import {createWriteStream} from 'node:fs';
import {mkdir,readFile,writeFile,mkdtemp,appendFile,readdir} from 'node:fs/promises';
import {join,resolve} from 'node:path';
import {createHash,randomUUID} from 'node:crypto';
import {tmpdir} from 'node:os';

const root=resolve(import.meta.dirname,'../../..');
const runtime=join(root,'services/platform-runtime');
const mode=process.argv[2] ?? 'plan';
const phaseDirectory=process.argv[3]==='--fault-phase-directory'?process.argv[4]:null;
if(process.argv.length>3&&(!phaseDirectory||process.argv.length!==5))throw Error('expected --fault-phase-directory exact frozen directory');
if (!['plan','run-happy','run-core','run-golden','run-recovery','run-external','run-activation'].includes(mode)) throw Error('mode must be plan, run-happy, run-core, run-golden, run-recovery, run-external or run-activation');
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
const executeFile=promisify(execFile);
let commandOrdinal=0;
const results=[];
let faultClient,lastFaultGrant;
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
 config.pauseBeforeOrdinal=4;
 return config;
}
function activationQuartet(){
 const balances={buyerCash:'0',sellerCash:'0',buyerShares:'0',sellerShares:'0'};
 const rows=[['A','10'],['B','20'],['A','5'],['B','7'],['A','1'],['B','1']];
 return {schema:'financial-e3-cohort-v1',caseId:'activation-quartet',policy:fixtures.policy,genesis:{A:{balances},B:{balances}},
  inputs:rows.map(([domain,amount],inputOrdinal)=>({domain,mode:'EXECUTE',inputOrdinal,commit:inputOrdinal===3||inputOrdinal===5,
   input:{namespace:'activation-quartet',domain,actionId:`fund-${inputOrdinal}`,kind:'FUND',payload:{account:'buyer',asset:'USD_NANO',amount,authority:'opening-resource-owner'}}})),
  scope:'exact funding quartet then fresh suffix; isolated mixed snapshot request refuses before activation'};
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
async function workerRows(worker){
 const raw=await readFile(join(proof,`${worker.id}-worker.stdout.log`),'utf8');
 return raw.split('\n').flatMap(line=>{try{return [JSON.parse(line)];}catch{return [];}});
}
async function marker(worker,predicate,label){
 const deadline=Date.now()+60000;
 while(Date.now()<deadline){
  const rows=await workerRows(worker);const row=rows.find(predicate);if(row)return row;
  if(worker.spawnError||worker.child.exitCode!==null||worker.child.signalCode!==null)throw Error(`${label} missing before worker exit`);
  await new Promise(r=>setTimeout(r,100));
 }
 throw Error(`${label} barrier timed out`);
}
async function ready(worker){
 const deadline=Date.now()+60000;
 while(Date.now()<deadline){
  if(worker.spawnError||worker.child.exitCode!==null||worker.child.signalCode!==null)throw Error(`worker failed before ready: ${worker.spawnError??worker.child.exitCode??worker.child.signalCode}`);
  const rows=await workerRows(worker);
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
async function docker(registration,action){
 validateRegistration(registration);
 if(!registration.phaseProtocol||registration.phaseProtocol.directory!==phaseDirectory)throw Error('external broker mutation requires frozen supervisor phase protocol');
 faultClient??=createPhaseClient(registration);
 const before=()=>lastFaultGrant?.generations;
 async function operation(args){
  const receipt={command:['docker',...args],startedAt:new Date().toISOString()};
  const deadline=action==='start'?lastFaultGrant?.recoveryDeadlineMs:action==='stop'?lastFaultGrant?.stopDeadlineMs:null;
  const remaining=deadline?Math.floor(deadline-hostMonotonicMs()):30000;
  if(remaining<=0)throw Error('frozen broker mutation/recovery deadline expired');
  try{const {stdout,stderr}=await executeFile('docker',args,{timeout:Math.min(30000,remaining),maxBuffer:1048576});Object.assign(receipt,{stdout,stderr,code:0});return stdout;}
  catch(error){Object.assign(receipt,{stdout:error.stdout,stderr:error.stderr,code:error.code,error:String(error)});throw error;}
  finally{receipt.finishedAt=new Date().toISOString();await appendFile(join(proof,'docker-attempts.jsonl'),JSON.stringify(receipt)+'\n');}
 }
 const inspect=async()=>JSON.parse(await operation(['inspect',...registration.containers]));
 if(action==='stop'){
  lastFaultGrant=await faultClient.request('stop');
  validateInspection(registration,await inspect(),{generations:before()});
  await operation(['stop','--time','10',registration.targetContainer]);
  const stopped=validateInspection(registration,await inspect(),{targetStopped:true,generations:before()});
  lastFaultGrant=await faultClient.request('stopped');return {...stopped,phaseAcknowledgement:lastFaultGrant};
 }else if(action==='start'){
  validateInspection(registration,await inspect(),{targetStopped:true,generations:before()});
  lastFaultGrant=await faultClient.request('start');
  await operation(['start',registration.targetContainer]);
  let generation;
  while(hostMonotonicMs()<lastFaultGrant.recoveryDeadlineMs){
   const rows=await inspect();
   const observed=validateInspection(registration,rows,{generations:before(),recoveryGrant:lastFaultGrant,allowTargetStarting:true});
   const current=rows.find(row=>row.Id===registration.targetContainer).State.StartedAt;
   if(generation&&current!==generation)throw Error('second target START generation');generation=current;
   if(observed.targetHealthy){lastFaultGrant=await faultClient.request('recovered');if(current!==lastFaultGrant.targetGeneration)throw Error('supervisor/wrapper recovery generation mismatch');return {...observed,phaseAcknowledgement:lastFaultGrant};}
   await new Promise(r=>setTimeout(r,250));
  }
  throw Error('fixed broker recovery deadline expired');
 }
 return validateInspection(registration,await inspect(),{targetStopped:true,generations:before()});
}
async function externalArm(name,config,registration){
 const topic=`${prefix}-${name}`;let lastDir;
 const ackPath=join(proof,`${name}-controller-ack.json`);
 const result=await runExternalArm(name,{
  init:()=>command('init',topic,config),
  seed:count=>count===undefined?command('seed',topic,config):command('seed-prefix',topic,config,count),
  start:async fault=>{lastDir=fault==='stale-owner'||!lastDir?await directory():lastDir;
   // Takeover uses separate process-local state; original owner remains frozen in its registered directory.
   if(name==='stale-owner-resume-after-takeover'&&!fault)lastDir=await directory();
   return start(topic,config,lastDir,fault);},
  ready,stop,
  observe:(count,timeout)=>count?observePrefix(topic,config,count):observe(topic,config,timeout??60000),
  append:()=>command('append-suffix',topic,config),
  marker:(worker,label)=>marker(worker,row=>label==='producerBoundary'?row.producerBoundary!==undefined&&row.failureClass!==undefined:row.faultBoundary===label,label),
  crash:expectCrash,
  signal:async(worker,signal)=>{if(!worker.child.kill(signal))throw Error(`${signal} failed for registered worker`);await appendFile(join(proof,'process-signals.jsonl'),JSON.stringify({topic,workerId:worker.id,pid:worker.child.pid,signal,at:new Date().toISOString()})+'\n');},
  resume:worker=>new Promise((res,rej)=>worker.child.stdin.write('resume\n',error=>error?rej(error):res())),
  fencing:async worker=>{await marker(worker,row=>row.producerBoundary!==undefined&&row.failureClass!==undefined,'broker fencing');return workerRows(worker);},
  boundary:async row=>{try{await readFile(ackPath);throw Error('controller ACK already persisted');}catch(error){if(error.code!=='ENOENT')throw error;}await writeFile(join(proof,`${name}-fault-boundary.json`),JSON.stringify(row,null,2)+'\n',{flag:'wx'});},
  ack:row=>writeFile(ackPath,JSON.stringify(row,null,2)+'\n',{flag:'wx'}),
  outage:action=>docker(registration,action),
 });
 results.push({...result,topic});
 await writeFile(join(proof,'results.json'),JSON.stringify({prefix,scope:'bounded RF3/EOS external faults; no cluster-loss guarantee',results},null,2)+'\n');
}
async function activationArm(config){
 const topic=`${prefix}-activation-quartet`;
 await command('init',topic,config);await command('seed-prefix',topic,config,4);
 const first=await start(topic,config,await directory());await ready(first);
 const before=await observePrefix(topic,config,4);if(!before.pass)throw Error('quartet prefix incomplete');
 await stop(first,true);
 const isolated=await command('reconstruct-prefix',topic,config,60000,4);
 const restored=await start(topic,config,await directory());await ready(restored);
 const idle=await observePrefix(topic,config,4);
 if(!idle.pass||JSON.stringify(before.resultOffsets)!==JSON.stringify(idle.resultOffsets)||before.finalCommittedEndOffset!==idle.finalCommittedEndOffset||before.inputGroupCheckpoint!==idle.inputGroupCheckpoint)throw Error('quartet restore changed committed prefix');
 await command('append-suffix',topic,config);
 const after=await observe(topic,config);
 if(!after.pass||JSON.stringify(before.resultOffsets)!==JSON.stringify(after.resultOffsets.slice(0,4))||after.ownerSnapshots.A.balances.buyerCash!=='16'||after.ownerSnapshots.B.balances.buyerCash!=='28')throw Error('quartet fresh suffix mismatch');
 await stop(restored);
 results.push({name:'activation-quartet',topic,localStateLost:true,before,idle,after,isolated});
 await writeFile(join(proof,'results.json'),JSON.stringify({prefix,scope:'isolated mixed snapshot activation refusal plus real retained-log restore/fresh suffix',results},null,2)+'\n');
}
try{
 const core=await file('core-cohort',cohort(0));
 const staging=await file('stage-cohort',cohort(19,true));
 const happy=await file('happy-cohort',twoWaves());
 const boundRows=await command('mutation-plan',prefix,core);
 const mutations=boundRows.find(row=>row.mutations)?.mutations;
 if(!Array.isArray(mutations)||mutations.length===0)throw Error('bounded semantic mutation inventory missing');
 const hashes={};
 const runnerHashes={};
 for(const path of ['scripts/dev/calcify-financial/broker-proof.mjs','scripts/dev/calcify-financial/lib/external-faults.mjs','scripts/dev/calcify-financial/proof-supervisor.mjs'])runnerHashes[path]=createHash('sha256').update(await readFile(join(root,path))).digest('hex');
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
 const plan={preflight,schema:'financial-e3-plan-v1',prefix,broker,fixtureSha256:createHash('sha256').update(await readFile(join(root,'docs/evidence/calcify-financial-sprint1/fixtures.json'))).digest('hex'),sourceHashes:hashes,runnerHashes,classpath:cp,compiledHashes,domains:['A','B'],partitions:1,replication:3,commitIntervalMs:60000,staleOwnerOverrides:{commitIntervalMs:1000,transactionTimeoutMs:30000},inputsPerCore:4,mutationBoundaries:mutations,goldenCases:20,stagingInputs:JSON.parse(await readFile(staging,'utf8')).inputs.length,externalMatrix:EXTERNAL_ARMS,retainedResources:'input/result/changelog topics and disposable state directories; parent owns broker/resource cleanup'};
 await writeFile(join(proof,'plan.json'),JSON.stringify(plan,null,2)+'\n',{flag:'wx'});
 console.log(JSON.stringify({frozenPlan:join(proof,'plan.json'),mode}));
 if(mode==='plan')process.exitCode=0;
 else if(mode==='run-happy'){await arm('happy',happy,'',false,4);}
 else if(mode==='run-activation'){await activationArm(await file('activation-quartet-cohort',activationQuartet()));}
 else if(mode==='run-core'){
  await arm('happy',happy,'',false,4);
  for(const {index} of mutations)await arm(`mutation-${index}`,core,`mutation:${index}`);
  await arm('forward',core,'forward');await arm('serialization',core,'serialization');
  await arm('local-state-loss',happy,'',true,4);await arm('staged-phase-loss',staging,'',true,19);
 }else if(mode==='run-golden'){
  for(let i=0;i<fixtures.cases.length;i++)await arm(`golden-${i}`,await file(`golden-cohort-${i}`,cohort(i)));
 }else if(mode==='run-external'){
  const registration=validateRegistration(preflight.externalBrokerFault);
  for(const {name} of EXTERNAL_ARMS)await externalArm(name,happy,registration);
 }else if(mode==='run-recovery'){
  await arm('local-state-loss',happy,'',true,4);await arm('staged-phase-loss',staging,'',true,19);
 }
}catch(error){await writeFile(join(proof,'failure.json'),JSON.stringify({error:String(error),results},null,2)+'\n');throw error;}
finally{for(const worker of workers)if(worker.child.exitCode===null&&worker.child.signalCode===null)await stop(worker);}
console.log(JSON.stringify({proof,results:results.length,pass:mode!=='plan'}));
