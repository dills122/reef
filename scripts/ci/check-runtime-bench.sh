#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNTIME_DIR="${PLATFORM_RUNTIME_DIR:-$ROOT_DIR/services/platform-runtime}"
GRADLE_HOME="${GRADLE_USER_HOME:-/tmp/reef-gradle}"

ITERATIONS="${REEF_RUNTIME_PERF_ITERATIONS:-1500}"
SUBMIT_BUDGET_NS="${REEF_RUNTIME_SUBMIT_NS_PER_OP_BUDGET:-20000000}"
TEST_CLASS="com.reef.platform.application.OrderApplicationServicePerfGuardrailTest"

echo "Running platform-runtime performance guardrail..."
echo "Directory : $RUNTIME_DIR"
echo "Gradle home: $GRADLE_HOME"
echo "Iterations: $ITERATIONS"
echo "Budget ns/op: $SUBMIT_BUDGET_NS"

(
  cd "$RUNTIME_DIR"
  GRADLE_USER_HOME="$GRADLE_HOME" \
  REEF_RUNTIME_PERF_ITERATIONS="$ITERATIONS" \
  REEF_RUNTIME_SUBMIT_NS_PER_OP_BUDGET="$SUBMIT_BUDGET_NS" \
  ./gradlew --no-daemon test --tests "$TEST_CLASS"
)

echo "Platform-runtime performance guardrail passed."
