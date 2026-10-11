// Scoped local resource-contention experiment, not a production financial adapter.
import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, readFileSync, writeFileSync, appendFileSync, existsSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { createHash } from 'node:crypto';

const [setupPath, outputPath, mode = 'smoke'] = process.argv.slice(2);
if (!setupPath || !outputPath || !['smoke','aged','control'].includes(mode)) throw Error('Usage: bun run-contention.mjs SETUP_DIR NEW_OUTPUT_DIR smoke|aged|control');
const root=resolve(import.meta.dirname,'../../../..');
const setup=JSON.parse(readFileSync(resolve(setupPath,'setup.json'),'utf8'));
const project='reef-calcify-contention-20261011';
const image='reef-platform-runtime:calcify-contention-20261011';
const sqlSchema=`calcify_contention_${setup.id.replaceAll('-','_')}`;
const out=resolve(outputPath);mkdirSync(out);
const seconds=mode==='smoke'?15:300;
const events=[];const start=performance.now();
function event(kind,detail={}) {
  const row={kind,epochMs:Date.now(),elapsedMs:performance.now()-start,...detail};events.push(row);
  appendFileSync(resolve(out,'events.jsonl'),JSON.stringify(row)+'\n');console.log(JSON.stringify(row));
}
function docker(args, timeout=30000) {
  const r=spawnSync('docker',args,{encoding:'utf8',timeout,maxBuffer:8*1024*1024});
  if(r.status!==0) throw Error(`docker ${args.slice(0,3).join(' ')}: ${r.stderr||r.error||r.stdout}`);
  return args[0]==='logs'?r.stdout+r.stderr:r.stdout;
}
const financial=[];
function startFinancial(index) {
  const name=`${project}-financial-${setup.id}-${index}`;
  docker(['run','-d','--name',name,'--label',`reef.proof=${project}`,'--network',`${project}_default`,'--memory','2g',
    '-v',`${root}/services/platform-runtime/build/classes/kotlin/test:/proof/classes:ro`,'--entrypoint','java',image,
    '-Xms128m','-Xmx1g','-XX:ActiveProcessorCount=2','-cp','/proof/classes:/app/platform-runtime/lib/*',
    'com.reef.platform.calcify.financial.ContentionSqlProbe','run','jdbc:postgresql://postgres:5432/reef',sqlSchema,'400','1000']);
  financial.push(name);event('financial-start',{name});
}
const env={...process.env,CALCIFY_DIRECT_SMOKE_ID:setup.id,CALCIFY_DIRECT_GENERATION:String(setup.generation),
  CALCIFY_DIRECT_SECONDS:String(seconds),CALCIFY_DIRECT_PAIRS_PER_SECOND:String(mode==='smoke'?1000:10500),
  CALCIFY_DIRECT_WORKERS:'1024',CALCIFY_DIRECT_WORKLOAD_MODE:'aggressor',CALCIFY_DIRECT_PREFLIGHT_ORDERS:'64',
  CALCIFY_DIRECT_PREFLIGHT_CONTEXTS:'0',CALCIFY_DIRECT_IMAGE:image,CALCIFY_DIRECT_NETWORK:`${project}_default`,
  CALCIFY_DIRECT_LOAD_BINARY:'/private/tmp/calcify-contention-20261011/calcify-load',CALCIFY_DIRECT_OUT_DIR:resolve(out,'capture'),
  CALCIFY_DIRECT_INSTRUMENT_IDS:setup.instruments.join(','),
  CALCIFY_DIRECT_SCOPE:`Research: retention0; local RF1 v26.2.3; aged capture with independent gross financial/SQL workload; ${mode==='control'?'no cold resolver restart':'cold resolver restore'}; finite lifecycle profile off`};
writeFileSync(resolve(out,'manifest.json'),JSON.stringify({schema:'calcify-contention-run-v1',mode,seconds,setup,project,image,sqlSchema,
  plan:JSON.parse(readFileSync(process.env.CALCIFY_CONTENTION_PLAN ?? '/private/tmp/calcify-contention-20261011/plan.json','utf8')),
  runnerSha256:createHash('sha256').update(readFileSync(import.meta.filename)).digest('hex'),
  images:docker(['image','inspect',image,'reef-matching-engine:calcify-contention-20261011','redpandadata/redpanda:v26.2.3','postgres:16-alpine','--format','{{.Id}}']).trim().split('\n')},null,2));
const log=createWriteStream(resolve(out,'capture-runner.log'));
const child=spawn('bun',['scripts/dev/calcify-direct-capacity.mjs'],{cwd:root,env,detached:true,stdio:['ignore','pipe','pipe']});
child.stdout.pipe(log,{end:false});child.stderr.pipe(log,{end:false});
let done=false,exitCode=null,error=null,loadStart=null,startedFinancial=false,restartedFinancial=false,restartedResolver=false,controlOmissionNoted=false;
child.on('error',e=>{error=String(e);done=true});child.on('close',code=>{done=true;exitCode=code;log.end()});
try {
  while(!done) {
    const now=performance.now();
    if(loadStart===null && existsSync(resolve(out,'capture/load.log'))) {loadStart=now;event('load-detected',{timing:'file creation observed; fault scheduling only, not throughput deadline'});}
    const elapsed=loadStart===null?0:(now-loadStart)/1000;
    const names=docker(['ps','--filter',`label=reef.proof=${project}`,'--format','{{.Names}}']).trim().split('\n').filter(Boolean);
    const stats=docker(['stats','--no-stream','--format','{{json .}}',...names]).trim().split('\n').filter(Boolean).map(s=>JSON.parse(s));
    appendFileSync(resolve(out,'resources.jsonl'),JSON.stringify({epochMs:Date.now(),loadElapsedSeconds:elapsed,stats})+'\n');
    const states=docker(['inspect',...names,'--format','{{json .State}}']).trim().split('\n').map(s=>JSON.parse(s));
    if(states.some(s=>s.OOMKilled)) throw Error('proof container OOM');
    const disk=docker(['exec',`${project}-redpanda`,'df','-B1','/var/lib/redpanda/data']).trim().split('\n').at(-1).split(/\s+/);
    appendFileSync(resolve(out,'disk.jsonl'),JSON.stringify({epochMs:Date.now(),availableBytes:Number(disk[3])})+'\n');
    if(Number(disk[3])<8_000_000_000) throw Error('broker disk reserve below 8GB');
    if(financial.length && docker(['inspect',financial.at(-1),'--format','{{.State.Running}}']).trim()!=='true') throw Error('financial contender exited unexpectedly');
    if(mode!=='smoke' && loadStart!==null) {
      if(elapsed>=60 && !startedFinancial) {startFinancial(1);startedFinancial=true;}
      if(elapsed>=150 && !restartedFinancial) {
        docker(['kill',financial.at(-1)]);event('financial-killed');
        writeFileSync(resolve(out,'financial-before-crash.log'),docker(['logs',financial.at(-1)]));
        startFinancial(2);restartedFinancial=true;
      }
      if(mode==='control' && elapsed>=180 && !controlOmissionNoted) {
        event('resolver-cold-restart-omitted',{reason:'matched control; resolver remains running'});controlOmissionNoted=true;
      }
      if(mode==='aged' && elapsed>=180 && !restartedResolver) {
        const cold=JSON.parse(readFileSync(setup.config,'utf8'));
        cold.services['calcify-resolver'].environment.CALCIFY_RESOLVER_STATE_DIR='/app/calcify-state/cold-recovery-1';
        const coldPath=resolve(out,'cold-compose.json');writeFileSync(coldPath,JSON.stringify(cold,null,2));
        event('resolver-cold-restart-start');
        docker(['compose','-p',project,'--profile','redpanda','--profile','calcify-phase1','--profile','calcify-phase2','-f',resolve(root,'compose.base.yml'),'-f',resolve(root,'compose.local.yml'),'-f',resolve(root,'compose.calcify.yml'),'-f',coldPath,
          'up','-d','--no-deps','--force-recreate','calcify-resolver'],60000);
        event('resolver-cold-restart-submitted');restartedResolver=true;
      }
    }
    if(now-start>900000) throw Error('proof timeout');
    await new Promise(r=>setTimeout(r,1000));
  }
} catch(e) {error=String(e);event('supervisor-failure',{error});try {process.kill(-child.pid,'SIGTERM');}catch{}}
finally {
  if(error && existsSync(resolve(out,'capture/frozen-policy.json'))) {
    const runId=JSON.parse(readFileSync(resolve(out,'capture/frozen-policy.json'),'utf8')).runId;
    const observers=docker(['ps','-a','--filter',`name=calcify-direct-${runId}-`,'--format','{{.Names}}']).trim().split('\n').filter(Boolean);
    if(observers.length) docker(['rm','-f',...observers]);
  }
  for(const name of financial) {
    try {docker(['stop','-t','5',name]);writeFileSync(resolve(out,`${name}.log`),docker(['logs',name]));}catch(e){event('cleanup-error',{name,error:String(e)});}
  }
  for(const role of ['calcify-resolver','calcify-extractor','calcify-verifier','matching-engine','platform-api']) {
    try {writeFileSync(resolve(out,`${role}.log`),docker(['logs',`${project}-${role}`]));}catch(e){event('log-error',{role,error:String(e)});}
  }
  writeFileSync(resolve(out,'supervisor.json'),JSON.stringify({exitCode,error,startedFinancial,restartedFinancial,restartedResolver,events},null,2));
}
console.log(JSON.stringify({out,exitCode,error}));
if(error || exitCode!==0) process.exitCode=1;
