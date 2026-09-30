#!/usr/bin/env python3
"""Broker retention/generation and lane-isolated input-gate probes."""
import json,pathlib,subprocess,tempfile,time,uuid,re
root=pathlib.Path(__file__).resolve().parents[3];exp=pathlib.Path(__file__).resolve().parent;out=root/'docs/evidence/calcify-phase2'/('E5-'+uuid.uuid4().hex[:8]);out.mkdir();cp=str(exp/'build/classes/java/main')+':'+str(exp/'build/deps/*');java=['java','-Dorg.slf4j.simpleLogger.defaultLogLevel=warn','-cp',cp,'CalcifyExperiment'];broker='127.0.0.1:29092';fixture=str(root/'docs/evidence/calcify-phase2/source-fixture.jsonl');workers=[];results=[]
def call(*a):
 p=subprocess.run(java+list(a),capture_output=True,text=True,timeout=35)
 with (out/'commands.log').open('a') as f:f.write(' '.join(a)+'\n'+p.stdout+p.stderr+'\n')
 if p.returncode:raise RuntimeError(p.stderr)
 return p.stdout
def start(prefix,dir,name):
 f=(out/(name+'.log')).open('w');p=subprocess.Popen(java+['worker',broker,prefix+'-app',prefix,str(dir)],stdout=f,stderr=subprocess.STDOUT);workers.append((p,f));return p
def stop(p):
 if p.poll() is None:p.terminate()
 try:p.wait(timeout=15)
 except subprocess.TimeoutExpired:p.kill();p.wait()
def wait(prefix,n,parts=1):
 deadline=time.monotonic()+45
 while time.monotonic()<deadline:
  r=json.loads(call('inspect',broker,prefix,fixture,str(parts)))
  if r['records']==n:return r
  time.sleep(.5)
 raise RuntimeError(str(r))
def fault(name,token):
 deadline=time.monotonic()+35
 while time.monotonic()<deadline:
  text=(out/(name+'.log')).read_text()
  if 'fault='+token in text:return text
  time.sleep(.5)
 raise RuntimeError('fault not observed '+token)
try:
 with tempfile.TemporaryDirectory(prefix='calcify-e5-') as tmp:
  tmp=pathlib.Path(tmp)
  prefix='p2-'+uuid.uuid4().hex[:8];call('seed',broker,prefix,fixture,'1');call('availability',broker,prefix,'truncate');p=start(prefix,tmp/'retention','retention');fault('retention','source retention lost');r=wait(prefix,0);stop(p);results.append({'case':'actual broker prefix deletion','output':r,'fault':'source retention lost'})
  prefix='p2-'+uuid.uuid4().hex[:8];call('seed',broker,prefix,fixture,'1');p=start(prefix,tmp/'generation','generation-before');r=wait(prefix,130);assert r['fullFactParity'];stop(p);identity=call('availability',broker,prefix,'recreate');p=start(prefix,tmp/'generation','generation-after');fault('generation-after','source topic identity changed');r=wait(prefix,130);stop(p);results.append({'case':'same-name source recreation across restart','identity':identity,'output':r,'fault':'source topic identity changed'})
  prefix='p2-'+uuid.uuid4().hex[:8];call('seed',broker,prefix,fixture,'2');p=start(prefix,tmp/'lanes','lanes');r=wait(prefix,260,2);assert r['fullFactParity'];call('poison',broker,prefix);log=fault('lanes','generation/lane mismatch');call('append',broker,prefix,fixture,'-wave2','1');r=wait(prefix,390,2);assert r['fullFactParity'];time.sleep(6);log=(out/'lanes.log').read_text();pending=[int(x) for x in re.findall(r'LOCAL pending=(\d+)',log)];assert max(pending)<=200,pending;stop(p);results.append({'case':'1000 poison links lane0; lane1 fresh wave continues','output':r,'observedMaxPendingAcrossLocalStores':max(pending),'hardLimit':200,'fault':'generation/lane mismatch'})
 (out/'results.json').write_text(json.dumps(results,indent=2));print(json.dumps(results,indent=2));print('EVIDENCE '+str(out))
finally:
 for p,f in workers:stop(p);f.close()
 (out/'partial-results.json').write_text(json.dumps(results,indent=2))
