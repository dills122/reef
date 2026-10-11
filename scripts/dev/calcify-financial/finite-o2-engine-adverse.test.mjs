import {describe,test,expect} from 'bun:test';
import {CASES,validateCaseState,assertObserver,assertExited,assertUnreachable,assertNoNewRecords,assertLostGenesis,assertCommandHistoryCut,assertSuffixMembership,runEngineAdverse} from './finite-o2-engine-adverse.mjs';
import {mkdtemp,writeFile,rm} from 'node:fs/promises';
import {join} from 'node:path';

const snapshot=(n)=>({run:{admissionState:'STOPPED',unresolvedPublications:'0',possiblePublications:String(n)},journal:Array.from({length:n},(_,i)=>({state:'PUBLISHED',offset:String(i),streamSequence:String(i+1)}))});
const lease=(kind)=>({scope:'full',adverse:kind});
const observer={schema:'calcify-finite-probe-result-v1',mode:'matcher-observe',status:'ok',data:{ClusterID:'actual',Topics:[1,2,3,4,5],Cuts:{}}};
const record=(offset,value)=>({offset:String(offset),payloadSha256:'actual-hash-'+offset,keyBase64:'a2V5',valueBase64:Buffer.from(JSON.stringify(value)).toString('base64'),headers:[]});
const flow=()=>({commands:{records:Array.from({length:12},(_,i)=>record(i,{commandId:'cmd-'+i}))},source:{records:Array.from({length:12},(_,i)=>record(i,{outcomes:[{commandId:'cmd-'+i,result:{trades:i===2?[{tradeId:'trade-0'},{tradeId:'trade-1'}]:i===7?[{tradeId:'trade-2'}]:[]}}]}))}});
const lostGenesis=()=>({schema:'calcify-finite-probe-result-v1',mode:'history',status:'error',code:'history_lost_genesis',data:{codec:-1,fetchCount:0,payloadBytes:0,phase:'lost_genesis',position:'0',readCommittedEndOffset:'-1',recordCount:0}});
const commandCut=()=>{
  const opening={topics:[{role:'COMMAND',name:'commands',topicId:'bound-uuid'}]},mutation={adverse:'command-history',topic:'commands',oldUuid:'bound-uuid',newUuid:'bound-uuid',beginBefore:'0',endBefore:'1',beginAfter:'1',endAfter:'1'};
  const before={histories:[{records:[record(0,{commandId:'one'})]},{records:[record(0,{source:'one'})]},{records:[record(0,{capture:'one'})]}],ranges:[{topic:'commands',beginningOffset:'0',endOffset:'1',readCommittedEndOffset:'1'},{topic:'source',beginningOffset:'0',endOffset:'3',readCommittedEndOffset:'3'},{topic:'capture',beginningOffset:'0',endOffset:'3',readCommittedEndOffset:'3'}],command:{offset:'1'},capture:{offset:'3'}};
  const after={...structuredClone(before),commandHistoryRefusal:lostGenesis(),commandMetadata:{topics:[{name:'commands',uuid:'bound-uuid',partitions:1,lowOffset:'1',highWatermark:'1'}]}};after.histories[0]=null;after.ranges[0].beginningOffset='1';
  return {opening,mutation,before,afterMutation:structuredClone(after),after};
};

test('COMMAND lost genesis accepts only exact typed negative, never transport/auth/codec or records substitute',()=>{
  expect(()=>assertLostGenesis(lostGenesis())).not.toThrow();
  for(const change of [r=>r.schema='wrong',r=>r.mode='range',r=>r.status='ok',r=>r.code='operation_failed',r=>r.code='history_unsupported_codec',r=>r.data.phase='deadline',r=>r.data.recordCount=1,r=>r.data.position='1',r=>r.data.readCommittedEndOffset='1',r=>r.data.fetchCount=1,r=>r.data.payloadBytes=1,r=>r.data.codec=0]){const r=lostGenesis();change(r);expect(()=>assertLostGenesis(r)).toThrow('exact COMMAND lost-genesis');}
});
test('COMMAND intentional trim needs actual exact cut and same bound UUID while SOURCE/CAPTURE histories/cuts remain unchanged',()=>{
  const good=commandCut();expect(()=>assertCommandHistoryCut(good.opening,good.mutation,good.before,good.afterMutation,good.after)).not.toThrow();
  for(const change of [f=>f.mutation.beginAfter='2',f=>f.mutation.endAfter='2',f=>f.mutation.oldUuid='other',f=>f.mutation.newUuid='other',f=>f.mutation.topic='source',f=>f.before.ranges[0].beginningOffset='1',f=>f.after.ranges[0].readCommittedEndOffset='-1',f=>f.after.commandMetadata.topics[0].uuid='other',f=>f.after.commandMetadata.topics[0].lowOffset='0',f=>f.after.commandMetadata.topics[0].partitions=2,f=>f.after.command.offset='2',f=>f.after.capture.offset='4',f=>f.after.ranges[1].endOffset='4',f=>f.afterMutation.histories[1].records.push(record(1,{new:'source'})),f=>f.after.histories[2].records[0]=record(0,{rewritten:'capture'}),f=>f.after.commandHistoryRefusal.code='history_bad_record']){const f=commandCut();change(f);expect(()=>assertCommandHistoryCut(f.opening,f.mutation,f.before,f.afterMutation,f.after)).toThrow();}
});

describe('finite engine adverse evidence gates',()=>{
  test('four fresh cases require exact stopped resolved journal and dedicated lease',()=>{
    expect(CASES.length).toBe(4);
    for(const [kind,n] of [['topic-identity',0],['command-history',1],['scope',0],['published-suffix',12]])expect(validateCaseState(kind,lease(kind),snapshot(n))).toBe(String(n));
    for(const change of [s=>s.run.admissionState='ACTIVE',s=>s.run.unresolvedPublications='1',s=>s.journal[0].state='UNKNOWN',s=>s.journal[0].streamSequence='2']){
      const s=snapshot(1);change(s);expect(()=>validateCaseState('command-history',lease('command-history'),s)).toThrow();
    }
    expect(()=>validateCaseState('topic-identity',lease('command-history'),snapshot(0))).toThrow();
    const s=snapshot(12);s.journal[1].offset='0';expect(()=>validateCaseState('published-suffix',lease('published-suffix'),s)).toThrow();
  });
  test('exact production error code required; authentication, transport, deadline and wrong refusal cannot pass',()=>{
    expect(()=>assertObserver(observer)).not.toThrow();
    const refusal={...observer,status:'refused',code:'topic_identity_mismatch',data:undefined};
    expect(()=>assertObserver(refusal,{refusal:'topic_identity_mismatch'})).not.toThrow();
    for(const [status,code] of [['error','deadline'],['authentication','58'],['denied','29'],['error','operation_failed'],['refused','history_missing_prefix']])expect(()=>assertObserver({...refusal,status,code},{refusal:'topic_identity_mismatch'})).toThrow();
    expect(()=>assertObserver({...observer,data:{}})).toThrow();
    expect(()=>assertObserver({...observer,mode:'metadata'})).toThrow();
  });
  test('actual exited nonzero process plus closed listener required, HTTP authorization refusal insufficient',()=>{
    expect(()=>assertExited({Running:false,Status:'exited',ExitCode:1})).not.toThrow();
    for(const state of [{Running:false,Status:'exited',ExitCode:0},{Running:true,Status:'running',ExitCode:1},{Running:false,Status:'created',ExitCode:1}])expect(()=>assertExited(state)).toThrow();
    const closed={schema:'calcify-finite-probe-result-v1',mode:'http',status:'error',code:'operation_failed'};
    expect(()=>assertUnreachable(closed)).not.toThrow();
    for(const change of [{status:'ok',data:{httpStatus:403}},{code:'deadline'},{mode:'authenticate'}])expect(()=>assertUnreachable({...closed,...change})).toThrow();
  });
  test('canonical membership equality ignores raw offset movement but catches new or rewritten data',()=>{
    const a={records:[record(0,{commandId:'one'})],endOffset:'1'},b={...a,endOffset:'3'};
    expect(()=>assertNoNewRecords([a],[b])).not.toThrow();
    expect(()=>assertNoNewRecords([a],[{records:[...a.records,record(2,{commandId:'two'})]}])).toThrow();
    expect(()=>assertNoNewRecords([a],[{records:[record(0,{commandId:'changed'})]}])).toThrow();
  });
  test('suffix proof rejects duplicate/missing outcomes, wrong trade count and prefix loss',()=>{
    const f=flow();expect(()=>assertSuffixMembership(f.commands,f.source,{records:f.source.records.slice(0,6)})).not.toThrow();
    const duplicate=flow();duplicate.source.records[11]=record(11,{outcomes:[{commandId:'cmd-0',result:{trades:[]}}]});expect(()=>assertSuffixMembership(duplicate.commands,duplicate.source,{records:[]})).toThrow();
    const reordered=flow();[reordered.source.records[0],reordered.source.records[1]]=[reordered.source.records[1],reordered.source.records[0]];expect(()=>assertSuffixMembership(reordered.commands,reordered.source,{records:[]})).toThrow();
    const missing=flow();missing.source.records.pop();expect(()=>assertSuffixMembership(missing.commands,missing.source,{records:[]})).toThrow();
    const trades=flow();trades.source.records[2]=record(2,{outcomes:[{commandId:'cmd-2',result:{trades:[]}}]});expect(()=>assertSuffixMembership(trades.commands,trades.source,{records:[]})).toThrow();
    expect(()=>assertSuffixMembership(f.commands,f.source,{records:[record(0,{outcomes:[{commandId:'wrong'}],trades:[]} )]})).toThrow();
  });
  test('suffix counts production outcome.result trades, never misleading top-level trades or missing nested facts',()=>{
    const actual=flow();expect(()=>assertSuffixMembership(actual.commands,actual.source,{records:[]})).not.toThrow();
    for(const missingResult of [false,true]){
      const f=flow();f.source.records=f.source.records.map((r,i)=>{const batch=JSON.parse(Buffer.from(r.valueBase64,'base64'));batch.trades=i<3?[{tradeId:'misleading-'+i}]:[];if(missingResult)delete batch.outcomes[0].result;else batch.outcomes[0].result={};return record(i,batch);});
      expect(()=>assertSuffixMembership(f.commands,f.source,{records:[]})).toThrow();
    }
  });
});

const deferred=()=>{let resolve,reject;const promise=new Promise((ok,no)=>{resolve=ok;reject=no;});return {promise,resolve,reject};};
async function orchestrationFixture(kind='topic-identity',{runtimeRunning=true,runtimeExit=1,runtimeOOM=false}={}){
  const root=await mkdtemp('/private/tmp/calcify-adverse-orchestration-'),calls=[],states={runtime:true,matcher:true};
  const envFile=join(root,'matcher.env');await writeFile(envFile,'MATCHING_ENGINE_SHARD_ID=bound-shard\n',{mode:0o600});
  let mutated=false;
  const s={lease:{scope:'full',adverse:kind,prefix:'calcify-o2-test',proofRoot:root,images:{runtime:'runtime-image',matcher:'matcher-image'}},ids:{runtime:'runtime-id',matcher:'matcher-id'},digest:'bound-digest',artifactManifestSha256:'held-manifest',secrets:[],env:{FINITE_MATCHER_ENV_FILE:envFile},opening:{matcherShardId:'bound-shard',topics:[{name:'commands'},{name:'source'},{name:'capture'}],commandConsumerGroup:'commands-group',captureApplicationId:'capture-group'},
    snapshot:async()=>snapshot(0),auth:async()=>({}),probe:async q=>q.mode==='history'?{records:[]}:q.mode==='group-offset'?{offset:'0'}:{},
    probeRaw:async q=>{calls.push(q.mode);return q.mode==='http'?{schema:'calcify-finite-probe-result-v1',mode:'http',status:'error',code:'operation_failed'}:mutated?{...observer,status:'refused',code:'topic_identity_mismatch'}:observer;},
    inspectOwned:async service=>({State:{Running:states[service],Status:states[service]?'running':'exited',ExitCode:service==='runtime'?runtimeExit:1,OOMKilled:service==='runtime'?runtimeOOM:false}}),
    command:async(argv,options)=>{calls.push(options.record);if(argv[0]==='stop')states.matcher=false;return {stdout:argv[0]==='logs'?'Calcify finite startup failed to start matching-engine direct stream runner; finite effective routing/config mismatch':'',stderr:''};},
    containRuntime:async()=>{calls.push('contain-runtime');states.runtime=false;return {};},
    adverseMutation:async()=>{calls.push('mutation');mutated=true;return {};},
    startServices:async(service,policy)=>{calls.push('start-'+service);if(policy?.appStartup!=='expected-refusal')throw Error('negative start policy missing');states[service]=service==='runtime'?runtimeRunning:false;},
    appLifecycleWitness:async(service,{running})=>{calls.push('witness-'+service+'-'+running);expect(states[service]).toBe(running);return {path:'independent-'+service+'-'+running};},
    waitFor:async(name,observe,accept,timeoutMs)=>{calls.push('wait-'+name);let value=await observe();if(name==='actual-runtime-refusal'){expect(timeoutMs).toBe(45000);calls.push('runtime-first-'+value.State.Running);if(!accept(value)){expect(value.State.Running).toBe(true);expect(calls.includes('adverse-runtime-startup-log')).toBe(false);expect(calls.includes('witness-runtime-true')).toBe(false);states.runtime=false;value=await observe();}}expect(accept(value)).toBe(true);return value;},
    writeReceipt:async(name,value)=>{calls.push(name);return value;},sample:async()=>{calls.push('sample');return {};}};
  return {s,calls,close:()=>rm(root,{recursive:true,force:true})};
}

for(const fail of [false,true])test('matcher stop full-sample barrier '+(fail?'failure blocks':'delays')+' next runtime mutation',async()=>{
  const f=await orchestrationFixture(),entered=deferred(),gate=deferred(),original=f.s.appLifecycleWitness;
  f.s.appLifecycleWitness=async(service,state)=>{const result=await original(service,state);if(service==='matcher'&&!state.running){entered.resolve();await gate.promise;}return result;};
  const work=runEngineAdverse(f.s,'topic-identity');const outcome=work.then(value=>({value}),error=>({error}));
  try{await entered.promise;expect(f.calls.includes('contain-runtime')).toBe(false);expect(f.calls.includes('mutation')).toBe(false);if(fail)gate.reject(Error('independent monitor refused'));else gate.resolve();const result=await outcome;if(fail){expect(result.error.message).toBe('independent monitor refused');expect(f.calls.includes('contain-runtime')).toBe(false);}else{expect(result.error).toBeUndefined();expect(f.calls.includes('mutation')).toBe(true);}}finally{gate.resolve();await outcome;await f.close();}
});

for(const runtimeRunning of [false,true])test('negative runtime startup '+(runtimeRunning?'transient running waits for exit':'already exited')+' before logs and independent exited witness',async()=>{
  const f=await orchestrationFixture('topic-identity',{runtimeRunning});
  try{await runEngineAdverse(f.s,'topic-identity');expect(f.calls.includes('runtime-first-'+runtimeRunning)).toBe(true);expect(f.calls.includes('witness-runtime-true')).toBe(false);expect(f.calls.indexOf('wait-actual-runtime-refusal')).toBeLessThan(f.calls.indexOf('adverse-runtime-startup-log'));expect(f.calls.indexOf('adverse-runtime-startup-log')).toBeLessThan(f.calls.indexOf('witness-runtime-false'));expect(f.calls.indexOf('witness-runtime-false')).toBeLessThan(f.calls.indexOf('start-matcher'));expect(f.calls.indexOf('adverse-matcher-refusal-log')).toBeLessThan(f.calls.lastIndexOf('witness-matcher-false'));expect(f.calls.lastIndexOf('http')).toBeLessThan(f.calls.lastIndexOf('witness-matcher-false'));}finally{await f.close();}
});

for(const reason of ['runtime observation failed','fixture readiness deadline'])test('runtime refusal '+reason+' blocks logs, matcher and final proof',async()=>{
  const f=await orchestrationFixture(),wait=f.s.waitFor;
  f.s.waitFor=async(name,...args)=>{if(name==='actual-runtime-refusal')throw Error(reason);return wait(name,...args);};
  try{await expect(runEngineAdverse(f.s,'topic-identity')).rejects.toThrow(reason);for(const call of ['adverse-runtime-startup-log','witness-runtime-true','start-matcher','actual-engine-refusal-proof'])expect(f.calls.includes(call)).toBe(false);}finally{await f.close();}
});

for(const runtimeOOM of [true,undefined])test('runtime '+(runtimeOOM?'OOM':'missing OOM evidence')+' cannot prove startup refusal',async()=>{
  const f=await orchestrationFixture('topic-identity',{runtimeRunning:false,runtimeOOM});if(runtimeOOM===undefined){const inspect=f.s.inspectOwned;f.s.inspectOwned=async service=>{const value=await inspect(service);if(service==='runtime')delete value.State.OOMKilled;return value;};}
  try{await expect(runEngineAdverse(f.s,'topic-identity')).rejects.toThrow('runtime startup OOM cannot prove refusal');expect(f.calls.includes('adverse-runtime-startup-log')).toBe(false);expect(f.calls.includes('start-matcher')).toBe(false);}finally{await f.close();}
});

test('zero runtime exit cannot substitute for actual negative refusal',async()=>{
  const f=await orchestrationFixture('topic-identity',{runtimeRunning:false,runtimeExit:0});
  try{await expect(runEngineAdverse(f.s,'topic-identity')).rejects.toThrow('nonzero actual matching process exit required');expect(f.calls.includes('start-matcher')).toBe(false);}finally{await f.close();}
});

for(const fail of [false,true])test('negative runtime full-sample barrier '+(fail?'rejection prevents':'delays')+' matcher startup',async()=>{
  const f=await orchestrationFixture(),entered=deferred(),gate=deferred(),original=f.s.appLifecycleWitness;
  f.s.appLifecycleWitness=async(service,state)=>{const result=await original(service,state);if(service==='runtime'){entered.resolve();await gate.promise;}return result;};
  const outcome=runEngineAdverse(f.s,'topic-identity').then(value=>({value}),error=>({error}));
  try{await entered.promise;expect(f.calls.includes('adverse-runtime-startup-log')).toBe(true);expect(f.calls.includes('start-matcher')).toBe(false);if(fail)gate.reject(Error('runtime full sample rejected'));else gate.resolve();const result=await outcome;if(fail){expect(result.error.message).toBe('runtime full sample rejected');expect(f.calls.includes('start-matcher')).toBe(false);}else expect(result.error).toBeUndefined();}finally{gate.resolve();await outcome;await f.close();}
});

test('actual matcher refusal evidence precedes full exited sample; rejected sample prevents final proof',async()=>{
  const f=await orchestrationFixture('scope'),entered=deferred(),gate=deferred(),original=f.s.appLifecycleWitness;let stops=0;
  f.s.appLifecycleWitness=async(service,state)=>{const result=await original(service,state);if(service==='matcher'&&!state.running&&++stops===2){entered.resolve();await gate.promise;}return result;};
  const outcome=runEngineAdverse(f.s,'scope').then(value=>({value}),error=>({error}));
  try{await entered.promise;expect(f.calls.includes('adverse-process-refusal-state')).toBe(true);expect(f.calls.includes('http')).toBe(true);expect(f.calls.includes('sample')).toBe(false);gate.reject(Error('refused full monitor sample'));const result=await outcome;expect(result.error.message).toBe('refused full monitor sample');expect(f.calls.includes('actual-engine-refusal-proof')).toBe(false);}finally{gate.resolve();await outcome;await f.close();}
});

async function commandHistoryFixture({counterChange=false}={}){
  const f=await orchestrationFixture('command-history'),cut=commandCut(),baseRaw=f.s.probeRaw;
  let mutated=false,snapshots=0;
  f.s.opening.topics=[...cut.opening.topics,{role:'SOURCE',name:'source'},{role:'CAPTURE',name:'capture'}];
  f.s.snapshot=async()=>{const s=snapshot(1);s.run.recordedAttempts=counterChange&&snapshots++?'2':'1';s.run.commandPayloadBytes='100';s.run.attemptRecordBytes='200';return s;};
  f.s.probe=async q=>{
    const observation=mutated?cut.after:cut.before,index=f.s.opening.topics.findIndex(t=>t.name===q.topic);
    if(q.mode==='history'){if(mutated&&index===0)throw Error('ordinary positive COMMAND history used after trim');return structuredClone(observation.histories[index]);}
    if(q.mode==='range')return structuredClone(observation.ranges[index]);
    if(q.mode==='metadata')return structuredClone(cut.after.commandMetadata);
    if(q.mode==='group-offset')return structuredClone(q.topic==='commands'?observation.command:observation.capture);
    throw Error('unexpected positive probe');
  };
  f.s.adverseMutation=async()=>{f.calls.push('mutation');mutated=true;return structuredClone(cut.mutation);};
  f.s.probeRaw=async q=>{if(q.mode==='history'){f.calls.push('typed-command-history');return lostGenesis();}if(q.mode==='matcher-observe'){f.calls.push(q.mode);return mutated?{...observer,status:'refused',code:'history_missing_prefix'}:observer;}return baseRaw(q);};
  return f;
}
test('command-history complete gate keeps exact typed negative separate and retains real observer, process, listener and independent exited witnesses',async()=>{
  const f=await commandHistoryFixture();try{const proof=await runEngineAdverse(f.s,'command-history');expect(proof.direct.code).toBe('history_missing_prefix');expect(proof.after.histories[0]).toBeNull();expect(proof.after.commandHistoryRefusal.code).toBe('history_lost_genesis');expect(f.calls.filter(c=>c==='typed-command-history').length).toBe(2);for(const call of ['witness-runtime-false','adverse-process-refusal-state','http','sample','actual-engine-refusal-proof'])expect(f.calls.includes(call)).toBe(true);expect(f.calls.filter(c=>c==='witness-matcher-false').length).toBe(2);}finally{await f.close();}
});
test('command-history correct negative cannot hide charged counter mutation',async()=>{
  const f=await commandHistoryFixture({counterChange:true});try{await expect(runEngineAdverse(f.s,'command-history')).rejects.toThrow('startup refusal changed charged admission accounting');expect(f.calls.includes('actual-engine-refusal-proof')).toBe(false);}finally{await f.close();}
});
