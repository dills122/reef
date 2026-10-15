from pathlib import Path
import re,json
root=Path(__file__).parent
summary={}
for path in sorted(root.glob('scale-*.log')):
 if path.name.endswith('.gc.log'):continue
 text=path.read_text();gcpath=path.with_name(path.stem+'.gc.log');groups={}
 for line in gcpath.read_text().splitlines() if gcpath.exists() else []:
  if 'Pause ' not in line:continue
  t=re.search(r' ([0-9.]+)ms$',line)
  if not t:continue
  kind='forced full' if 'Pause Full' in line and ('System.gc()' in line or 'Heap Inspection' in line) else 'automatic full' if 'Pause Full' in line else 'forced young' if 'System.gc()' in line or 'Heap Inspection' in line else 'ordinary young' if 'Pause Young' in line else 'remark/cleanup'
  groups.setdefault(kind,[]).append(float(t[1]))
 summary[path.stem]={'config':next((x for x in text.splitlines() if x.startswith('HEAP-CONFIG')),None),'points':[x for x in text.splitlines() if x.startswith(('HEAP-POINT','HEAP-TRIE'))],'pauses':{k:{'count':len(v),'sum_ms':round(sum(v),3),'max_ms':max(v)} for k,v in groups.items()},'all_pause_ms':round(sum(sum(v) for v in groups.values()),3),'error_lines':[x for x in text.splitlines() if 'Exception' in x or 'OutOfMemory' in x]}
(root/'scale-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps(summary,indent=2))
