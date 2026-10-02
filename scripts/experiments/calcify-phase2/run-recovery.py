#!/usr/bin/env python3
"""Real broker EOS/fencing/standby experiment. Broker must be dedicated/disposable."""
import json, pathlib, subprocess, tempfile, time, uuid, os, re
root=pathlib.Path(__file__).resolve().parents[3];exp=pathlib.Path(__file__).resolve().parent
out=root/'docs/evidence/calcify-phase2'/('E3-'+uuid.uuid4().hex[:8]);out.mkdir()
cp=str(exp/'build/classes/java/main')+':'+str(exp/'build/deps/*')
java=['java','-Dorg.slf4j.simpleLogger.defaultLogLevel=warn','-cp',cp,'CalcifyExperiment'];broker='127.0.0.1:29092';fixture=str(root/'docs/evidence/calcify-phase2/source-fixture.jsonl');workers=[];results=[]
def call(*args):
 p=subprocess.run(java+list(args),text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=35)
 with (out/'commands.log').open('a') as f:f.write(' '.join(java+list(args))+'\n'+p.stdout+p.stderr+'\n')
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout

def start(app,prefix,dir,crash='',name='worker'):
 f=(out/(name+'.log')).open('w');p=subprocess.Popen(java+['worker',broker,app,prefix,str(dir),crash],stdout=f,stderr=subprocess.STDOUT);workers.append((p,f));return p

def stop(p,hard=False):
 if p.poll() is None:
  (p.kill if hard else p.terminate)()
  try:p.wait(timeout=15)
  except subprocess.TimeoutExpired:p.kill();p.wait()

def wait_count(prefix,n):
 deadline=time.monotonic()+55
 while time.monotonic()<deadline:
  r=json.loads(call('inspect',broker,prefix,fixture,'1'))
  if r['records']==n:
   assert r['duplicates']==0 and r['fullFactParity'],r
   return r
  time.sleep(.5)
 raise RuntimeError(f'{prefix}: expected {n}, got {r}')

try:
 for crash in ([] if os.getenv('CALCIFY_STANDBY_ONLY') else ['', 'state','forward']):
  prefix='p2-'+uuid.uuid4().hex[:8];app=prefix+'-app';call('seed',broker,prefix,fixture,'1')
  with tempfile.TemporaryDirectory(prefix='calcify-e3-') as tmp:
   p=start(app,prefix,pathlib.Path(tmp)/'disk',crash,name='initial-'+(crash or 'commit'))
   if crash:
    p.wait(timeout=40);assert p.returncode==91,p.returncode
    before=json.loads(call('inspect',broker,prefix,fixture,'1'));assert before['records']==0,before
   else:before=wait_count(prefix,130);stop(p,hard=True)
   started=time.monotonic();p=start(app,prefix,pathlib.Path(tmp)/'disk',name='restart-'+(crash or 'commit'));after=wait_count(prefix,130)
   # Committed count is required BEFORE append; restart then receives fresh source work.
   call('append',broker,prefix,fixture,'-wave2');after=wait_count(prefix,260);elapsed=time.monotonic()-started;stop(p)
   results.append({'boundary':crash or 'after-commit','before':before,'afterRestartAndWave2':after,'restartToWave2Seconds':elapsed,'sameDisk':True});print(json.dumps(results[-1]),flush=True)
 if not os.getenv('CALCIFY_STANDBY_ONLY'):
  # Host/disk loss; restore from replicated Kafka changelog, not full source replay.
  prefix='p2-'+uuid.uuid4().hex[:8];app=prefix+'-app';call('seed',broker,prefix,fixture,'1')
  with tempfile.TemporaryDirectory(prefix='calcify-e3-node-') as tmp:
   p=start(app,prefix,pathlib.Path(tmp)/'old',name='node-old');wait_count(prefix,130);stop(p,hard=True)
   started=time.monotonic();p=start(app,prefix,pathlib.Path(tmp)/'empty',name='node-restored');call('append',broker,prefix,fixture,'-wave2');after=wait_count(prefix,260);elapsed=time.monotonic()-started;stop(p)
   results.append({'boundary':'node-loss-empty-disk','after':after,'restoreAndWave2Seconds':elapsed});print(json.dumps(results[-1]),flush=True)
 # One partition => second instance has only standby; read_committed output unchanged before promotion.
 prefix='p2-'+uuid.uuid4().hex[:8];app=prefix+'-app';call('seed',broker,prefix,fixture,'1')
 with tempfile.TemporaryDirectory(prefix='calcify-e3-standby-') as tmp:
  p=start(app,prefix,pathlib.Path(tmp)/'active',name='active');wait_count(prefix,130)
  shadow=start(app,prefix,pathlib.Path(tmp)/'shadow',name='shadow');deadline=time.monotonic()+45
  while time.monotonic()<deadline:
   a=(out/'active.log').read_text();b=(out/'shadow.log').read_text()
   if 'standbyTasks=[TaskMetadata' in a and re.search(r'offsetLag=[01} ]',a):p,shadow=shadow,p;break
   if 'standbyTasks=[TaskMetadata' in b and re.search(r'offsetLag=[01} ]',b):break
   time.sleep(.5)
  else:raise RuntimeError('caught-up standby not observed')
  assert json.loads(call('inspect',broker,prefix,fixture,'1'))['records']==130
  started=time.monotonic();stop(p,hard=True);call('append',broker,prefix,fixture,'-wave2');after=wait_count(prefix,260);elapsed=time.monotonic()-started;stop(shadow)
  results.append({'boundary':'output-silent-standby-promoted-state-lag-at-most-one-offset','after':after,'failoverAndWave2Seconds':elapsed});print(json.dumps(results[-1]),flush=True)
 results.append(json.loads(call('fence',broker)));(out/'results.json').write_text(json.dumps({'broker':'Redpanda26.2.3 RF1 2CPU 2GiB dedicated; no broker-loss/RF3 claim','kafkaStreams':'4.3.1 exactly_once_v2 standby1; source read_committed','results':results},indent=2))
 print('EVIDENCE '+str(out),flush=True)
finally:
 for p,f in workers:stop(p);f.close()
 (out/'partial-results.json').write_text(json.dumps(results,indent=2))
