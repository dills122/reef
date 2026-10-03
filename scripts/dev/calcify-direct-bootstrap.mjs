// Fresh, explicitly scoped C5-ingress + Calcify local capacity setup.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
const root=resolve(import.meta.dirname,'../..');
const id=process.env.CALCIFY_DIRECT_SMOKE_ID;
if(!id || !/^[A-Za-z0-9_-]+$/.test(id)) throw Error('CALCIFY_DIRECT_SMOKE_ID required');
const mode=process.env.CALCIFY_DIRECT_WORKLOAD_MODE ?? 'paired';if(!['paired','aggressor'].includes(mode))throw Error('invalid workload mode');
const generation=Number(process.env.CALCIFY_DIRECT_GENERATION ?? 2);
if(!Number.isSafeInteger(generation)||generation<2) throw Error('generation must be >=2');
const out=resolve(process.env.CALCIFY_DIRECT_SETUP_OUT ?? `/private/tmp/calcify-direct-${id}`);mkdirSync(out,{recursive:true});
const prefix=`REEF_${id.toUpperCase()}`;
const env={...process.env,COMPOSE_PROJECT_NAME:'reef-calcify-direct-20261002',COMPOSE_PROFILES:'redpanda,calcify-phase1,calcify-phase2'};
const common={STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS:'redpanda:9092',RUNTIME_POSTGRES_JDBC_URL:'jdbc:postgresql://postgres:5432/reef?currentSchema=runtime',RUNTIME_POSTGRES_USER:'reef',RUNTIME_POSTGRES_PASSWORD:'reef',CALCIFY_SOURCE_TOPIC:`${prefix}_EVENTS`,CALCIFY_COMMITMENT_TOPIC:`${prefix}_COMMITMENTS`,CALCIFY_VERIFIED_TOPIC:`${prefix}_VERIFIED`,CALCIFY_SOURCE_GENERATION:String(generation),CALCIFY_BROKER_KIND:'REDPANDA',CALCIFY_INTERNAL_TOPIC_REPLICATION_FACTOR:'1',JAVA_TOOL_OPTIONS:'-Xms128m -Xmx1g -XX:ActiveProcessorCount=2'};
const ingress={PLATFORM_SETTLEMENT_FACTS_ENABLED:'false',RUNTIME_POSTGRES_JDBC_URL:'',RUNTIME_PROJECTION_POSTGRES_JDBC_URL:'',RUNTIME_PERSISTENCE:'noop',EXTERNAL_API_IDEMPOTENCY_STORE:'inmemory',EXTERNAL_API_COMMAND_CAPTURE_MODE:'disabled',EXTERNAL_API_COMMAND_LOG_MODE:'disabled',EXTERNAL_API_COMMAND_PROCESSING_MODE:'stream-ack',EXTERNAL_API_ABUSE_BREAKER_MODE:'off',STREAM_ACK_INTAKE_STORE:'inmemory',STREAM_ACK_INMEMORY_INTAKE_MAX_ENTRIES:'100000',STREAM_ACK_INMEMORY_INTAKE_SHARDS:'256',PLATFORM_HTTP_SERVER:'netty',PLATFORM_INTERNAL_HTTP_MODE:'enabled',STREAM_ACK_LOG_PROVIDER:'redpanda',STREAM_ACK_PARTITION_COUNT:'16',STREAM_ACK_COMMAND_STREAM:`${prefix}_COMMANDS`,STREAM_ACK_SUBJECT_PREFIX:`reef.${id}.cmd.v1`,STREAM_ACK_PUBLISH_PIPELINE_ENABLED:'true',STREAM_ACK_PUBLISH_PIPELINE_QUEUE_CAPACITY:'8192',STREAM_ACK_PUBLISH_PIPELINE_MAX_IN_FLIGHT_PER_LANE:'256',STREAM_ACK_PUBLISH_PIPELINE_BATCH_SIZE:'1',STREAM_ACK_PUBLISH_PIPELINE_BATCH_LINGER_MS:'0',STREAM_ACK_WORKER_ENABLED:'false',STREAM_ACK_PROJECTOR_ENABLED:'false',ORDER_LIFECYCLE_PROJECTOR_ENABLED:'false',MARKET_DATA_PROJECTOR_ENABLED:'false',VENUE_EVENT_MATERIALIZER_ENABLED:'false',STREAM_INGRESS_ENABLED:'false',STREAM_ACK_BACKPRESSURE_WORKER_DURABLES:Array.from({length:16},(_,p)=>`reef-engine-direct-p${String(p).padStart(2,'0')}`).join(','),JAVA_TOOL_OPTIONS:process.env.CALCIFY_DIRECT_API_JAVA_OPTS ?? '-Xms128m -Xmx512m -XX:ActiveProcessorCount=2'};
const engine={STREAM_ACK_LOG_PROVIDER:'redpanda',STREAM_ACK_KAFKA_BOOTSTRAP_SERVERS:'redpanda:9092',STREAM_ACK_PARTITION_COUNT:'16',STREAM_ACK_COMMAND_STREAM:`${prefix}_COMMANDS`,STREAM_ACK_SUBJECT_PREFIX:`reef.${id}.cmd.v1`,MATCHING_ENGINE_DIRECT_STREAM_ENABLED:'true',MATCHING_ENGINE_DIRECT_STREAM_PARTITIONS:'0..15',MATCHING_ENGINE_DIRECT_STREAM_BATCH_SIZE:process.env.CALCIFY_DIRECT_ENGINE_BATCH ?? '500',MATCHING_ENGINE_DIRECT_STREAM_FETCH_TIMEOUT_MS:'100',MATCHING_ENGINE_DIRECT_STREAM_POLL_MS:'1',MATCHING_ENGINE_DIRECT_STREAM_MAX_ACK_PENDING:'16000',MATCHING_ENGINE_DIRECT_STREAM_ACK_WAIT_MS:'60000',MATCHING_ENGINE_TERMINAL_ORDER_RETENTION_LIMIT:process.env.CALCIFY_DIRECT_TERMINAL_PER_BOOK ?? '250000',MATCHING_ENGINE_EVENT_STREAM:`${prefix}_EVENTS`,MATCHING_ENGINE_EVENT_SUBJECT_PREFIX:`reef.${id}.events.v1`,MATCHING_ENGINE_KAFKA_MAX_MESSAGE_BYTES:'4194304',MATCHING_ENGINE_KAFKA_COMPRESSION_TYPE:'none'};
const image=process.env.CALCIFY_DIRECT_IMAGE ?? 'reef-platform-runtime:calcify-10k-master461';
const overlay={services:{'platform-api':{image,environment:ingress},'matching-engine':{image:process.env.CALCIFY_DIRECT_MATCHING_IMAGE ?? 'reef-matching-engine:calcify-10k-master461',environment:engine},'calcify-extractor':{image,environment:common},'calcify-verifier':{image,environment:{...common,CALCIFY_VERIFIER_MAX_POLL_RECORDS:process.env.CALCIFY_DIRECT_VERIFIER_POLL ?? '1000'}},'calcify-resolver':{image,environment:{...common,CALCIFY_RESOLVED_TOPIC:`${prefix}_RESOLVED`,CALCIFY_RESOLVER_APPLICATION_ID:`reef-${id}-g${generation}`,CALCIFY_RESOLVER_THREADS:process.env.CALCIFY_DIRECT_RESOLVER_THREADS ?? '2',CALCIFY_RESOLVER_STANDBYS:'0',CALCIFY_RESOLVER_MAX_PENDING:process.env.CALCIFY_DIRECT_RESOLVER_PENDING ?? '1000'}}}};
const config=resolve(out,'compose-profile.json');writeFileSync(config,JSON.stringify(overlay,null,2));
const files=['-f','compose.base.yml','-f','compose.local.yml','-f','compose.calcify.yml','-f',config];
function run(args,input){const result=spawnSync('docker',args,{cwd:root,env,encoding:'utf8',input,stdio:input===undefined?'inherit':['pipe','inherit','inherit']});if(result.status!==0)throw Error(`docker exit ${result.status}: ${args.join(' ')}`);}
function compose(args,input){run(['compose',...files,...args],input);}
compose(['up','-d','--no-deps','--wait','postgres','redpanda']);
compose(['exec','-T','redpanda','rpk','cluster','config','set','write_caching_default','false']);
for(const name of ['0070_calcify_phase1_receipts.sql','0071_calcify_source_topic_identity.sql','0072_calcify_topic_identities.sql']) compose(['exec','-T','postgres','psql','-U','reef','-d','reef','-v','ON_ERROR_STOP=1'],readFileSync(resolve(root,'scripts/dev/db/migrations/runtime',name),'utf8').replace('ADD COLUMN source_topic_id','ADD COLUMN IF NOT EXISTS source_topic_id'));
compose(['exec','-T','postgres','psql','-U','reef','-d','reef','-v','ON_ERROR_STOP=1','-c',`INSERT INTO runtime.calcify_source_generations(source_generation,source_topic) VALUES (${generation},'${prefix}_EVENTS')`]);
compose(['exec','-T','redpanda','rpk','topic','create',`${prefix}_COMMANDS`,`${prefix}_EVENTS`,'-p','16','-r','1','-c','write.caching=false']);
compose(['exec','-T','redpanda','rpk','topic','alter-config',`${prefix}_EVENTS`,'--set','max.message.bytes=4194304']);
compose(['up','-d','--no-deps','--wait','matching-engine','platform-api']);
compose(['up','-d','--no-deps','calcify-extractor','calcify-verifier']);
await new Promise(r=>setTimeout(r,6000));
compose(['up','-d','--no-deps','--wait','calcify-resolver']);
const instruments=[];const lanes=Array(16).fill(0);for(let n=0;instruments.length<64;n++){const candidate=`I${n}-${id}`;const hash=createHash('sha256').update(`${id}|session-${id}|${candidate}`).digest();const lane=Number((hash.readBigUInt64BE() & 0x7fffffffffffffffn)%16n);if(lanes[lane]<4){lanes[lane]++;instruments.push(candidate);}}
async function post(path,body,extraHeaders={}){const response=await fetch(`http://127.0.0.1:8080${path}`,{method:'POST',headers:{'content-type':'application/json','X-Reef-Internal-Route':'true',...extraHeaders},body:JSON.stringify(body)});if(!response.ok)throw Error(`${path} ${response.status} ${await response.text()}`);}
for(const instrumentId of instruments)await post('/reference/instruments',{instrumentId,symbol:instrumentId,assetClass:'US_EQ',currency:'USD'});
for(const party of ['buyer','seller']){await post('/reference/participants',{participantId:`${party}-${id}`,name:party});await post('/reference/accounts',{accountId:`${party}-account-${id}`,participantId:`${party}-${id}`,accountType:'HOUSE'});}
await post('/auth/roles',{roleId:'order_trader',permissions:'order.submit,order.cancel,order.modify'});
for(const party of ['buyer','seller'])await post('/auth/actor-roles',{actorId:`${party}-actor-${id}`,roleId:'order_trader'});
if(mode==='aggressor') {
  const seconds=Number(process.env.CALCIFY_DIRECT_SECONDS ?? 300);const pace=Number(process.env.CALCIFY_DIRECT_PAIRS_PER_SECOND ?? 10500);
  if(!Number.isSafeInteger(seconds)||seconds<1||!Number.isSafeInteger(pace)||pace<1)throw Error('invalid seeded workload budget');
  const quantityUnits=String((Math.ceil(seconds*pace/64)+2048)*100);
  for(const instrumentId of instruments){const commandId=`maker-cmd-${instrumentId}`;await post('/api/v1/orders/submit',{commandId,traceId:`trace-${commandId}`,causationId:`cause-${commandId}`,correlationId:`corr-${commandId}`,actorId:`buyer-actor-${id}`,runId:id,venueSessionId:`session-${id}`,occurredAt:'2026-09-29T15:00:00Z',orderId:`maker-order-${instrumentId}`,instrumentId,participantId:`buyer-${id}`,accountId:`buyer-account-${id}`,side:'BUY',orderType:'LIMIT',quantityUnits,limitPrice:'150250000000',currency:'USD',timeInForce:'DAY'},{'X-Client-Id':`buyer-client-${id}`,'Idempotency-Key':commandId});}
  const deadline=Date.now()+60000;let acked=0;
  while(acked<64&&Date.now()<deadline){const response=await fetch('http://127.0.0.1:8081/internal/stream-direct/stats');const stats=await response.json();if(!Array.isArray(stats.partitions))throw Error('matching stats shape changed');if(stats.partitions.some(p=>p.failed||p.nacked))throw Error('maker preflight matching failed');acked=stats.partitions.reduce((n,p)=>n+(p.acked??0),0);if(acked<64)await new Promise(r=>setTimeout(r,100));}
  if(acked!==64)throw Error(`maker preflight matching ACKs${acked} expected64`);
}
writeFileSync(resolve(out,'setup.json'),JSON.stringify({id,generation,workloadMode:mode,preflightAcceptedOrders:mode==='aggressor'?64:0,preflightTrades:0,source:`${prefix}_EVENTS`,verified:`${prefix}_VERIFIED`,output:`${prefix}_RESOLVED`,instruments,config,profile:'C5 direct in-memory intake + durable Redpanda + Go matching + Calcify extractor/verifier/managed resolver; startup SQL identity registration only; no receipt worker'},null,2));
console.log(`DIRECT_SETUP_READY ${out}`);
