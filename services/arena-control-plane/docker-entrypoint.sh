#!/bin/sh
set -eu

exec /opt/java/openjdk/bin/java ${JAVA_OPTS:-} -cp '/app/platform-runtime/lib/*' com.reef.platform.MainKt "$@"
