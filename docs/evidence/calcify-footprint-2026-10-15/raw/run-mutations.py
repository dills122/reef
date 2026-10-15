from pathlib import Path
import subprocess
root=Path('/private/tmp/reef-footprint-ci/services/platform-runtime')
base=root/'src/main/kotlin/com/reef/platform/calcify/finance'
files={name:(base/name).read_text() for name in ['FlatStateTrie.kt','CapacityLedger.kt']}
mutants=[('chunk-accessor','FlatStateTrie.kt','metaChunks[branch ushr CHUNK_BITS]','metaChunks[branch ushr 16]'),('settled-residual','CapacityLedger.kt','private val SETTLED = Obligation(ObligationStatus.SETTLED, 0, 0)','private val SETTLED = Obligation(ObligationStatus.SETTLED, 1, 0)'),('restore-status','CapacityLedger.kt','if (it == SETTLED) SETTLED else it','if (it.status == ObligationStatus.SETTLED) SETTLED else it'),('restore-trailing','CapacityLedger.kt','.also { value.end() }.let { if (it == SETTLED)', '.let { if (it == SETTLED)')]
try:
 for label,name,old,new in mutants:
  assert old in files[name]
  (base/name).write_text(files[name].replace(old,new))
  with open('/private/tmp/reef-footprint/mutation-'+label+'.log','w') as out:
   result=subprocess.run(['./gradlew','--no-daemon','-Dorg.gradle.jvmargs=-Xmx3g','-Pkotlin.daemon.jvmargs=-Xmx3g','test','--tests','*CapacityFootprintCompatibilityTest*','--tests','*FlatStateTrieTest*','--console=plain'],cwd=root,stdout=out,stderr=subprocess.STDOUT)
  print(label,result.returncode,flush=True)
  (base/name).write_text(files[name])
finally:
 for name,body in files.items(): (base/name).write_text(body)
