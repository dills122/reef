#!/usr/bin/env python3
"""Run command/batch-scope prototype only in disposable matching-module copy."""
import difflib, os, pathlib, shutil, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[3]; exp=pathlib.Path(__file__).resolve().parent; out=root/'docs/evidence/calcify-phase2'
with tempfile.TemporaryDirectory(prefix='calcify-matcher-') as tmp:
 scratch=pathlib.Path(tmp)/'matching-engine';shutil.copytree(root/'services/matching-engine',scratch,ignore=shutil.ignore_patterns('build','.git'));shutil.copyfile(exp/'source_fixture_test.go.txt',scratch/'internal/streamdirect/calcify_experiment_test.go')
 def run(name,env):
  p=subprocess.run(['go','test','./internal/streamdirect','-run','^TestCalcifyExperiment$','-count=1','-v'],cwd=scratch,env={**os.environ,**env},text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/name).write_text(p.stdout)
  if p.returncode:raise RuntimeError(p.stdout)
 run('E1-baseline.log',{})
 p=scratch/'internal/app/service.go';orig=p.read_text();s=orig.replace('import (', 'import (\n \"fmt\"').replace('type BookScope struct {','type BookScope struct {\n RunID string')
 for obj in ['cmd','record','scope','s']:
  s=s.replace(f'bookKey({obj}.VenueSessionID, {obj}.InstrumentID)',f'bookKey({obj}.VenueSessionID, {obj}.InstrumentID, {obj}.RunID)').replace(f's.bookFor({obj}.VenueSessionID, {obj}.InstrumentID)',f's.bookFor({obj}.VenueSessionID, {obj}.InstrumentID, {obj}.RunID)')
 for name in ['loadBook','bookFor','bookKey']:s=s.replace(f'{name}(venueSessionID string, instrumentID string)',f'{name}(venueSessionID string, instrumentID string, runID ...string)')
 s=s.replace('s.loadBook(venueSessionID, instrumentID)','s.loadBook(venueSessionID, instrumentID, runID...)').replace('bookKey(venueSessionID, instrumentID)','bookKey(venueSessionID, instrumentID, runID...)').replace('book, ok := s.loadBook(venueSessionID, instrumentID, runID...)','book, ok := s.loadBook(venueSessionID, instrumentID)')
 s=s.replace('func bookKey(venueSessionID string, instrumentID string, runID ...string) string {','func bookKey(venueSessionID string, instrumentID string, runID ...string) string {\n if len(runID)>0 && runID[0]!="" { return fmt.Sprintf("%d:%s|%d:%s|%s",len(runID[0]),runID[0],len(venueSessionID),venueSessionID,instrumentID) }');p.write_text(s)
 patch=''.join(difflib.unified_diff(orig.splitlines(True),s.splitlines(True),fromfile='a/internal/app/service.go',tofile='b/internal/app/service.go'))
 p=scratch/'internal/streamdirect/processor.go';orig=p.read_text();s=orig.replace('scope := app.BookScope{VenueSessionID:', 'var routeContext struct { RunID string `json:"runId"` }; _ = json.Unmarshal(delivery.Data(), &routeContext)\n scope := app.BookScope{RunID: routeContext.RunID, VenueSessionID:');p.write_text(s);patch+=''.join(difflib.unified_diff(orig.splitlines(True),s.splitlines(True),fromfile='a/internal/streamdirect/processor.go',tofile='b/internal/streamdirect/processor.go'));(exp/'run-scope-prototype.patch').write_text(patch)
 subprocess.run(['gofmt','-w','internal/app/service.go','internal/streamdirect/processor.go','internal/streamdirect/calcify_experiment_test.go'],cwd=scratch,check=True)
 run('E1-aligned.log',{'CALCIFY_ALIGNED':'1','CALCIFY_FIXTURE':str(out/'source-fixture.jsonl')})
