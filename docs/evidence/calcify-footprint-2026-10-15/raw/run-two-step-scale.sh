#!/bin/zsh
set -e
cd /Users/dsteele/.codex/worktrees/10c2/reef/services/platform-runtime
for collector in G1 Parallel; do
  HF_MODE=two-step HF_CHECKPOINTS=9000000,18000000 HF_TOP=20 /Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java -Xmx31g -XX:+Use${collector}GC -Xlog:gc:file=/private/tmp/reef-footprint/scale-two-step-${collector}.gc.log -cp 'build/classes/kotlin/test:build/classes/kotlin/main:build/classes/java/main:build/resources/test:build/resources/main:build/resolver-probe-deps/*' com.reef.platform.calcify.finance.CapacityHeapFootprintKt > /private/tmp/reef-footprint/scale-two-step-${collector}.log 2>&1
 done
